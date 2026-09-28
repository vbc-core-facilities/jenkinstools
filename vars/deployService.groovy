/**
 * The whole protchem service pipeline: build the Docker image with IT's buildDockerImage (vbc-cicd),
 * inject the NuGet feed secret, collect in-image test results, run the OCP arbitrary-UID probe
 * smoke, push, trigger Tower, and notify the VBC Deployment Cockpit. A Jenkinsfile only supplies
 * its configuration:
 *
 *   library identifier: 'jenkinstools@v1.1.0', retriever: modernSCM([$class: 'GitSCMSource',
 *           remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])
 *
 *   deployService(
 *     imageName : 'hive.frontend',
 *     dockerFile: 'Hive.Frontend/Dockerfile',
 *     tower     : [staging: 'App Protchem Hive Frontend Staging', production: 'App Protchem Hive Frontend Production'],
 *   )
 *
 * Configuration (all optional unless marked):
 *   imageName          (required) image name, pushed as <imageNamespace>/<imageName>
 *   dockerFile         (required) path to the Dockerfile
 *   dockerContext      build context, default '.'
 *   imageNamespace     default 'protchem'
 *   pushBranches       branches whose images are pushed, default ['master', 'develop']
 *
 *   tower              [staging: '<job>', production: '<job>', stagingBranch: 'master',
 *                       imageTagVariable: 'app_generic_image_tag']
 *                      staging runs on pushes to stagingBranch, production on tags with
 *                      "<imageTagVariable>: <tag>" as extra vars. Omit for build-and-push only.
 *   towerJobs          raw buildDockerImage tower map; overrides `tower` for unusual setups
 *
 *   buildArgs          Map of docker build args, merged over the defaults below
 *   defaultBuildArgs   false to start from an empty set instead of:
 *                        NUGET_REPO_USER=vbc-proteomics, MINVER_VERSION_OVERRIDE=<tag without v>,
 *                        ASPNET_PORT=8080, APP_USER=app, APP_GROUP_GID=0,
 *                        TEST_RESULTS_FOLDER=<testResultsFolder>, SEQ_VERSION=<pinned below>
 *   nugetSecret        [credentialsId: 'vbc-proteomics-github-pat', secretId: 'SECRETS-NUGET-REPO-PW'];
 *                      the PAT is passed as a BuildKit secret, never as a build arg. false to skip.
 *
 *   tests              false to skip the test closure entirely (no result collection, no smoke)
 *   testResultsFolder  where the image keeps JUnit XML results, default '/app/tests/results'
 *   testScript         optional script to run inside the image first (gets the results folder as
 *                      its argument); a non-zero exit marks the build UNSTABLE
 *   ocpSmoke           false to skip the OCP probe-mechanics smoke
 *   ocpSmokeExtraChecks  List of extra shell lines for the smoke (no single quotes), e.g.
 *                      ['touch /app/data/.smoke-write-test && rm -f /app/data/.smoke-write-test']
 *
 *   cockpitNotify      [credentialsId: '<Secret text id>'] or [connectionString: '<value>'] -
 *                      a Send-only Service Bus connection string; omit to skip. Passed to
 *                      vbcDeploymentCockpitNotify; a failed notification only warns.
 *
 *   beforeBuild        closure run before the pipeline (inside the cockpit-notified section)
 *   afterBuild         closure run after a successful pipeline
 */
def call(Map config = [:]) {
    def missing = ['imageName', 'dockerFile'].findAll { !config[it] }
    if (missing) {
        error("deployService: missing required configuration: ${missing.join(', ')}")
    }

    echo "deployService from jenkinstools ${libraryVersion()}"

    String tagName = env.TAG_NAME ?: 'latest'
    String testResultsFolder = config.testResultsFolder ?: '/app/tests/results'

    // ---- docker build args + BuildKit secrets ----------------------------------------------------
    Map buildArgs = config.defaultBuildArgs == false ? [:] : [
        NUGET_REPO_USER        : 'vbc-proteomics',
        MINVER_VERSION_OVERRIDE: env.TAG_NAME ? env.TAG_NAME.replaceFirst(/^v/, '') : '',
        ASPNET_PORT            : '8080',
        APP_USER               : 'app',
        APP_GROUP_GID          : '0',
        TEST_RESULTS_FOLDER    : testResultsFolder,
        // pinned deliberately; ':latest' would make rebuilds non-reproducible
        SEQ_VERSION            : '2026.1.17044',
    ]
    buildArgs += (config.buildArgs ?: [:])

    List<String> buildArgParts = buildArgs.collect { k, v -> "--build-arg ${k}=\"${v}\"" }
    List<String> secretIds = []

    Map nuget = config.nugetSecret == false ? null :
        ([credentialsId: 'vbc-proteomics-github-pat', secretId: 'SECRETS-NUGET-REPO-PW'] + (config.nugetSecret ?: [:]))
    if (nuget) {
        String envName = 'secret_' + nuget.secretId.replace('-', '_')
        withCredentials([usernamePassword(credentialsId: nuget.credentialsId, usernameVariable: 'NUGET_USER', passwordVariable: 'NUGET_PAT')]) {
            // BuildKit reads the secret from this env var, so it never appears in build args or logs.
            env."${envName}" = "${NUGET_PAT}"
        }
        buildArgParts << "--secret id=${nuget.secretId},type=env,env=${envName}"
        secretIds << nuget.secretId
    }
    String extraBuildArgs = buildArgParts.join(' ')

    // ---- tower ------------------------------------------------------------------------------------
    Map towerJobs = config.towerJobs
    if (towerJobs == null && config.tower) {
        Map t = [stagingBranch: 'master', imageTagVariable: 'app_generic_image_tag'] + config.tower
        towerJobs = [:]
        if (t.staging) {
            towerJobs[t.stagingBranch] = [jobName: t.staging]
        }
        if (t.production) {
            towerJobs.tags = [jobName: t.production, extraVars: "${t.imageTagVariable}: ${tagName}"]
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
        pushRegistryNamespace: config.imageNamespace ?: 'protchem',
        pushBranches         : config.pushBranches ?: ['master', 'develop'],
        extraBuildArgs       : extraBuildArgs,
    ]
    if (config.dockerContext) image.dockerContext = config.dockerContext
    if (towerJobs) image.tower = towerJobs
    if (config.tests != false) image.test = testClosure(config, testResultsFolder)

    notifyCockpit(config.cockpitNotify, 'started')
    try {
        if (config.beforeBuild) config.beforeBuild()
        buildDockerImage(image)
        if (config.afterBuild) config.afterBuild()
    } catch (e) {
        currentBuild.result = 'FAILURE' // so the 'finished' notification reports the real result
        throw e
    } finally {
        notifyCockpit(config.cockpitNotify, 'finished')
    }
}

private String libraryVersion() {
    try {
        return libraryResource('jenkinstools/VERSION').trim()
    } catch (Exception e) {
        return '(unknown version)'
    }
}

private void notifyCockpit(Map cockpit, String event) {
    if (!cockpit) {
        echo "deployService: cockpitNotify not configured - skipping '${event}' notification"
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
