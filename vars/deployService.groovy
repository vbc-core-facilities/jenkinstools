/**
 * The whole protchem service pipeline: build the Docker image with IT's buildDockerImage (vbc-cicd),
 * inject the NuGet feed secret, collect in-image test results, run the OCP arbitrary-UID probe
 * smoke, push, trigger Tower, and notify the VBC Deployment Cockpit. A Jenkinsfile only supplies
 * its configuration. Version 1.0.0 - released as tag deployService/v1.0.0.
 *
 *   library identifier: 'jenkinstools@deployService/v1.0.0', retriever: modernSCM([$class: 'GitSCMSource',
 *           remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])
 *
 * Every repo-specific fact (ports, folders, Seq, users, branches, ...) comes from the Jenkinsfile;
 * the only built-in defaults are the two ecosystem-wide build args and the NuGet feed secret below,
 * and each of those can be overridden or removed.
 *
 *   deployService(
 *     imageName        : my_image_name,
 *     dockerFile       : my_dockerfile,
 *     imageNamespace   : my_image_namespace,
 *     pushBranches     : my_observed_git_branches,
 *     tower            : [staging: my_ansible_job_name_staging, production: my_ansible_job_name_prod,
 *                         stagingBranch: 'master'],
 *     buildArgs        : my_docker_build_args,
 *     testResultsFolder: test_results_folder_inside_container,
 *   )
 *
 * Image (required):
 *   imageName          image name, pushed as <imageNamespace>/<imageName>
 *   dockerFile         path to the Dockerfile
 *   imageNamespace     registry namespace, e.g. 'protchem'
 *   pushBranches       branches whose images are pushed, e.g. ['master', 'develop']
 *   dockerContext      (optional) build context; buildDockerImage's default when omitted
 *
 * Tower (optional - omit for build-and-push only):
 *   tower              [staging: '<job>', production: '<job>', stagingBranch: '<branch>',
 *                       imageTagVariable: 'app_generic_image_tag']
 *                      staging runs on pushes to stagingBranch (required with staging), production
 *                      on tags with "<imageTagVariable>: <tag>" as extra vars.
 *   towerJobs          raw buildDockerImage tower map; overrides `tower` for unusual setups
 *
 * Build args - the Jenkinsfile's buildArgs are the image's build args. Built-in defaults, applied
 * first:
 *     NUGET_REPO_USER          'vbc-proteomics' (the ecosystem's NuGet feed user)
 *     MINVER_VERSION_OVERRIDE  the git tag without a leading 'v', '' when not a tag build
 *   buildArgs          Map of any build args: adds new ones and overrides defaults. A null value
 *                      removes that default, e.g. [MINVER_VERSION_OVERRIDE: null].
 *   defaultBuildArgs   false to use none of the defaults.
 *
 * BuildKit secrets - passed with `docker build --secret`, never as build args or in logs. Built-in
 * default: [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat', kind: 'usernamePassword'].
 *   secrets            List of [id: '<secret id>', credentialsId: '<Jenkins credential>',
 *                       kind: 'usernamePassword' (its password is the secret) | 'string' (Secret text)].
 *                      Adds secrets; an entry with a default's id replaces that default.
 *   defaultSecrets     false to use none of the default secrets.
 *
 * Tests:
 *   tests              false to skip the test closure entirely (no result collection, no smoke)
 *   testResultsFolder  (required unless tests: false) where the image keeps JUnit XML results
 *   testScript         optional script to run inside the image first (gets the results folder as
 *                      its argument); a non-zero exit marks the build UNSTABLE
 *   ocpSmoke           false to skip the OCP probe-mechanics smoke
 *   ocpSmokeExtraChecks  List of extra shell lines for the smoke (no single quotes), e.g.
 *                      ['touch /app/data/.smoke-write-test && rm -f /app/data/.smoke-write-test']
 *
 * Cockpit notification - a pipeline secret, used by Jenkins itself and never passed to the image:
 *   cockpitNotify      default [credentialsId: 'vbc-cockpit-service-bus-send'] (Secret text holding a
 *                      Send-only Service Bus connection string). Override with another
 *                      [credentialsId: ...] or [connectionString: '<value>'], plus any
 *                      vbcDeploymentCockpitNotify options; false to disable. A failed notification,
 *                      including a credential that doesn't exist yet, only warns.
 *
 *   beforeBuild        closure run before the pipeline (inside the cockpit-notified section)
 *   afterBuild         closure run after a successful pipeline
 */
def call(Map config = [:]) {
    List<String> missing = ['imageName', 'dockerFile', 'imageNamespace', 'pushBranches'].findAll { !config[it] }
    if (config.tests != false && !config.testResultsFolder) {
        missing << 'testResultsFolder (or tests: false)'
    }
    if (config.tower?.staging && !config.tower?.stagingBranch) {
        missing << 'tower.stagingBranch'
    }
    if (missing) {
        error("deployService: missing required configuration: ${missing.join(', ')}")
    }

    echo "deployService v${toolVersion()} (jenkinstools)"

    String tagName = env.TAG_NAME ?: 'latest'

    // ---- docker build args -------------------------------------------------------------------------
    Map buildArgs = config.defaultBuildArgs == false ? [:] : [
        NUGET_REPO_USER        : 'vbc-proteomics',
        MINVER_VERSION_OVERRIDE: env.TAG_NAME ? env.TAG_NAME.replaceFirst(/^v/, '') : '',
    ]
    buildArgs += (config.buildArgs ?: [:])
    buildArgs = buildArgs.findAll { k, v -> v != null } // null removes a default

    List<String> buildArgParts = buildArgs.collect { k, v -> "--build-arg ${k}=\"${v}\"" }

    // ---- BuildKit secrets --------------------------------------------------------------------------
    Map<String, Map> secretsById = [:]
    if (config.defaultSecrets != false) {
        secretsById['SECRETS-NUGET-REPO-PW'] =
            [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat', kind: 'usernamePassword']
    }
    (config.secrets ?: []).each { Map s -> secretsById[s.id as String] = s }

    List<String> secretIds = []
    secretsById.values().each { Map s ->
        if (!s.id || !s.credentialsId || !(s.kind in ['usernamePassword', 'string'])) {
            error("deployService: secret needs id, credentialsId and kind 'usernamePassword' or 'string': ${s.findAll { k, v -> k != 'value' }}")
        }
        String envName = 'secret_' + (s.id as String).replaceAll(/[^A-Za-z0-9_]/, '_')
        def binding = s.kind == 'string'
            ? string(credentialsId: s.credentialsId as String, variable: 'DEPLOY_SECRET')
            : usernamePassword(credentialsId: s.credentialsId as String, usernameVariable: 'DEPLOY_SECRET_USER', passwordVariable: 'DEPLOY_SECRET')
        withCredentials([binding]) {
            // BuildKit reads the secret from this env var, so it never appears in build args or logs.
            env."${envName}" = "${DEPLOY_SECRET}"
        }
        buildArgParts << "--secret id=${s.id},type=env,env=${envName}"
        secretIds << (s.id as String)
    }
    String extraBuildArgs = buildArgParts.join(' ')

    // ---- tower ------------------------------------------------------------------------------------
    Map towerJobs = config.towerJobs
    if (towerJobs == null && config.tower) {
        Map t = [imageTagVariable: 'app_generic_image_tag'] + config.tower
        towerJobs = [:]
        if (t.staging) {
            towerJobs[t.stagingBranch] = [jobName: t.staging]
        }
        if (t.production) {
            towerJobs.tags = [jobName: t.production, extraVars: "${t.imageTagVariable}: ${tagName}".toString()]
        }
    }

    // ---- build info -------------------------------------------------------------------------------
    node {
        stage('Build info') {
            sh 'docker version'
            sh 'docker buildx version'
            echo "Build args:\n${buildArgs}"
            echo "Secret ids injected into docker:\n${secretIds}"
            echo "Tower jobs:\n${towerJobs ?: 'none (build and push only)'}"
            echo "GIT_COMMIT=${env.GIT_COMMIT}"
            echo "GIT_PREVIOUS_SUCCESSFUL_COMMIT=${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
            currentBuild.changeSets.each { changeSet ->
                changeSet.items.each { entry -> echo "Change: ${entry.commitId} ${entry.author} ${entry.msg}" }
            }
        }
    }

    // ---- pipeline ---------------------------------------------------------------------------------
    Map image = [
        imageName            : config.imageName,
        dockerFile           : config.dockerFile,
        pushRegistryNamespace: config.imageNamespace,
        pushBranches         : config.pushBranches,
    ]
    // Only when there is something to pass, like Jenkinsfiles that never set it.
    if (extraBuildArgs) image.extraBuildArgs = extraBuildArgs
    if (config.dockerContext) image.dockerContext = config.dockerContext
    if (towerJobs) image.tower = towerJobs
    if (config.tests != false) image.test = testClosure(config, config.testResultsFolder as String)

    Map cockpit = config.cockpitNotify == false ? null
        : (config.cockpitNotify ?: [credentialsId: 'vbc-cockpit-service-bus-send'])

    notifyCockpit(cockpit, 'started')
    try {
        if (config.beforeBuild) config.beforeBuild()
        buildDockerImage(image)
        if (config.afterBuild) config.afterBuild()
    } catch (e) {
        currentBuild.result = 'FAILURE' // so the 'finished' notification reports the real result
        throw e
    } finally {
        notifyCockpit(cockpit, 'finished')
    }
}

// This tool's own version; bump it (and tag deployService/vX.Y.Z) with every change to this file.
private String toolVersion() { return '1.0.0' }

private void notifyCockpit(Map cockpit, String event) {
    if (!cockpit) {
        echo "deployService: cockpitNotify disabled - skipping '${event}' notification"
        return
    }
    try {
        node {
            vbcDeploymentCockpitNotify(event, cockpit) // credentialsId or connectionString, + options
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        // e.g. no agent available - never let the notification break a build unless asked to
        if (cockpit.failOnError == true) throw e
        echo "WARNING: deployService: cockpit '${event}' notification failed: ${e.message} - continuing"
    }
}

// buildDockerImage calls this with (defaultImageName, allBuilds) after building the image.
private Closure testClosure(Map config, String testResultsFolder) {
    return { defaultImageName, allBuilds ->
        String resultsInWorkspace = 'test_results'
        sh "mkdir -p ${resultsInWorkspace}; chmod 777 ${resultsInWorkspace}"
        def built = allBuilds[defaultImageName]

        try {
            built.image.inside() {
                if (config.testScript) {
                    int testStatus = sh(script: "${config.testScript} ${testResultsFolder}", returnStatus: true, label: 'in-image tests')
                    if (testStatus > 0) {
                        unstable('Test script returned a non-zero exit code.')
                    }
                }
                sh """
                if [ -d ${testResultsFolder} ]; then
                  cp -r ${testResultsFolder}/. ${env.WORKSPACE}/${resultsInWorkspace}
                else
                  echo "No test results found in ${testResultsFolder}"
                fi
                """
            }
        } catch (exc) {
            echo "Error occurred while running/collecting in-image tests: ${exc}"
            unstable('Exception raised while running/collecting in-image tests.')
        }

        if (config.ocpSmoke != false) {
            ocpProbeSmoke(built.image.id, config.ocpSmokeExtraChecks ?: [])
        }

        junit skipPublishingChecks: true, allowEmptyResults: true, testResults: "${resultsInWorkspace}/*.xml"
    }
}

// Container-mechanics smoke, NOT an application-readiness test: the fixed OCP probe paths exist and
// are executable by an arbitrary UID whose only group is 0, /logs is writable, curl is present, the
// entrypoint honours a passed-in command, and - via the negative control - the probes can report
// failure at all. It never starts the app (one-shot docker run), which is what makes the negative
// control meaningful. A failure marks the build UNSTABLE, never FAILED: an image-mechanics
// regression must be loud without blocking an otherwise shippable artifact.
private void ocpProbeSmoke(String imageId, List extraChecks) {
    try {
        String extra = extraChecks ? ('\n# repo-specific checks\n' + extraChecks.join('\n') + '\n') : ''
        String mechanicsScript = '''
set -e
id

# Fixed-path contract + permission model under the arbitrary UID.
for p in startup live ready; do
  test -x /ocp/probes/$p || { echo "[smoke] not executable by arbitrary UID: /ocp/probes/$p" >&2; exit 1; }
done
command -v curl >/dev/null 2>&1 || { echo "[smoke] curl missing from runtime image" >&2; exit 1; }
touch /logs/.smoke-write-test && rm -f /logs/.smoke-write-test
''' + extra + '''
# Positive path: the scripts run and succeed when told to (also parse-checks all four files).
HEALTH_STARTUP_CHECK=pass /ocp/probes/startup
HEALTH_LIVE_CHECK=pass   /ocp/probes/live
HEALTH_READY_CHECK=pass  /ocp/probes/ready

# Negative control: nothing is listening here, so the real default spec must fail.
if HEALTH_READY_CHECK=http:/healthz /ocp/probes/ready >/dev/null 2>&1; then
  echo "[smoke] negative control FAILED: probe reported success with nothing listening" >&2
  exit 1
fi

echo "[smoke] OCP mechanics OK"
'''.trim()

        int status = sh(
            script: "docker run --rm --user 123456:0 ${imageId} '${mechanicsScript}'",
            returnStatus: true,
            label: 'ocp arbitrary uid probe mechanics smoke'
        )
        if (status > 0) {
            unstable('OCP arbitrary-UID probe mechanics smoke returned non-zero exit code.')
        }
    } catch (exc) {
        echo "Error occurred while running OCP arbitrary-UID probe smoke: ${exc}"
        unstable('Exception raised while running OCP arbitrary-UID probe smoke.')
    }
}
