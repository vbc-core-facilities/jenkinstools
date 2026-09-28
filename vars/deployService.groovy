/**
 * Version 1.0.0 - released as tag deployService/v1.0.0 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * Generic service pipeline wiring on top of IT's buildDockerImage (vbc-cicd), with NO defaults:
 * nothing is assumed, every value comes from the caller, and every optional feature is off unless
 * configured. It does the wiring - build args and BuildKit secret injection, build-info reporting,
 * in-image tests and the OCP probe smoke, push, Tower, cockpit notification, and arbitrary pre/post
 * actions. For the protchem conventions (names, branches, standard build args, ...) use
 * deployStandardProteomicsService, which fills in defaults and calls this.
 *
 * Image (required):
 *   imageName            image name, pushed as <imageNamespace>/<imageName>
 *   dockerFile           path to the Dockerfile
 *   imageNamespace       registry namespace
 *   pushBranches         List of branches whose images are pushed (may be empty)
 *   dockerContext        optional build context; buildDockerImage's own default when omitted
 *
 * Build inputs (optional):
 *   buildArgs            Map of docker build args, passed as --build-arg NAME="value"; null values skipped
 *   secrets              List of BuildKit secrets, passed with `docker build --secret` - never as build
 *                        args or in logs: [id: '<secret id>', credentialsId: '<Jenkins credential>',
 *                        kind: 'usernamePassword' (its password is the secret) | 'string' (Secret text)]
 *
 * Tower (optional; omit for build-and-push only):
 *   tower                [staging: '<job>', stagingBranch: '<branch>', production: '<job>',
 *                         imageTagVariable: '<extra var name>']
 *                        staging runs on pushes to stagingBranch; production runs on tags with
 *                        "<imageTagVariable>: <tag>" as extra vars. Each job needs its companion value.
 *   towerJobs            raw buildDockerImage tower map instead of `tower`
 *
 * Tests (optional; the test step runs only when one of these is set):
 *   testResultsFolder    where the image keeps JUnit XML results; they are copied out and published
 *   testScript           script run inside the image first, with testResultsFolder as its argument
 *                        (needs testResultsFolder); a non-zero exit marks the build UNSTABLE
 *   ocpSmoke             true to run the OCP arbitrary-UID probe-mechanics smoke
 *   ocpSmokeExtraChecks  List of extra shell lines for the smoke (no single quotes)
 *
 * Notification (optional):
 *   cockpitNotify        [credentialsId: '...'] or [connectionString: '...'] plus any
 *                        vbcDeploymentCockpitNotify options - a pipeline secret, never passed to the
 *                        image. Sent on start and finish; a failed notification only warns.
 *
 * Actions (optional; each a Closure or a List of Closures, run in order):
 *   beforeBuild          before buildDockerImage
 *   afterBuild           after a successful buildDockerImage
 *   afterAlways          after buildDockerImage whatever the outcome (before the 'finished' notification)
 */
def call(Map config = [:]) {
    validate(config)
    echo "deployService v${toolVersion()} (jenkinstools)"

    String tagName = env.TAG_NAME ?: 'latest'

    // ---- build args + BuildKit secrets --------------------------------------------------------------
    Map buildArgs = (config.buildArgs ?: [:]).findAll { k, v -> v != null }
    List<String> buildArgParts = buildArgs.collect { k, v -> "--build-arg ${k}=\"${v}\"" }

    List<String> secretIds = []
    (config.secrets ?: []).each { Map s ->
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

    // ---- tower ----------------------------------------------------------------------------------------
    Map towerJobs = config.towerJobs
    if (towerJobs == null && config.tower) {
        Map t = config.tower
        towerJobs = [:]
        if (t.staging) {
            towerJobs[t.stagingBranch as String] = [jobName: t.staging]
        }
        if (t.production) {
            towerJobs.tags = [jobName: t.production, extraVars: "${t.imageTagVariable}: ${tagName}".toString()]
        }
    }

    // ---- build info -----------------------------------------------------------------------------------
    node {
        stage('Build info') {
            sh 'docker version'
            sh 'docker buildx version'
            echo "Image: ${config.imageNamespace}/${config.imageName} from ${config.dockerFile}, pushed on ${config.pushBranches}"
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

    // ---- pipeline -------------------------------------------------------------------------------------
    Map image = [
        imageName            : config.imageName,
        dockerFile           : config.dockerFile,
        pushRegistryNamespace: config.imageNamespace,
        pushBranches         : config.pushBranches,
    ]
    if (extraBuildArgs) image.extraBuildArgs = extraBuildArgs
    if (config.dockerContext) image.dockerContext = config.dockerContext
    if (towerJobs) image.tower = towerJobs
    if (config.testResultsFolder || config.testScript || config.ocpSmoke == true) {
        image.test = testClosure(config)
    }

    Map cockpit = config.cockpitNotify ?: null
    notifyCockpit(cockpit, 'started')
    try {
        runActions(config.beforeBuild)
        buildDockerImage(image)
        runActions(config.afterBuild)
    } catch (e) {
        currentBuild.result = 'FAILURE' // so the 'finished' notification reports the real result
        throw e
    } finally {
        try {
            runActions(config.afterAlways)
        } finally {
            notifyCockpit(cockpit, 'finished')
        }
    }
}

// This tool's own version; bump it (and tag deployService/vX.Y.Z) with every change to this file.
private String toolVersion() { return '1.0.0' }

private void validate(Map config) {
    List<String> problems = ['imageName', 'dockerFile', 'imageNamespace'].findAll { !config[it] }
        .collect { "${it} is required".toString() }
    if (!(config.pushBranches instanceof List)) problems << 'pushBranches is required (a List, may be empty)'

    Map t = config.tower ?: [:]
    if (t.staging && !t.stagingBranch) problems << 'tower.staging needs tower.stagingBranch'
    if (t.stagingBranch && !t.staging) problems << 'tower.stagingBranch needs tower.staging'
    if (t.production && !t.imageTagVariable) problems << 'tower.production needs tower.imageTagVariable'
    if (config.tower && config.towerJobs) problems << 'pass tower or towerJobs, not both'

    if (config.testScript && !config.testResultsFolder) problems << 'testScript needs testResultsFolder'
    if (config.ocpSmokeExtraChecks && config.ocpSmoke != true) problems << 'ocpSmokeExtraChecks needs ocpSmoke: true'

    (config.secrets ?: []).each { Map s ->
        if (!s.id || !s.credentialsId || !(s.kind in ['usernamePassword', 'string'])) {
            problems << "secret needs id, credentialsId and kind 'usernamePassword' or 'string': ${s}".toString()
        }
    }
    List ids = (config.secrets ?: []).collect { it.id }
    if (ids.size() != (ids as Set).size()) problems << "duplicate secret ids: ${ids}".toString()

    ['beforeBuild', 'afterBuild', 'afterAlways'].each { name ->
        def a = config[name]
        if (a != null && !(a instanceof Closure) && !(a instanceof List && a.every { it instanceof Closure })) {
            problems << "${name} must be a Closure or a List of Closures".toString()
        }
    }

    if (problems) {
        error("deployService: invalid configuration:\n  - ${problems.join('\n  - ')}")
    }
}

private void runActions(def actions) {
    if (actions == null) return
    (actions instanceof List ? actions : [actions]).each { Closure action -> action() }
}

private void notifyCockpit(Map cockpit, String event) {
    if (!cockpit) {
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
private Closure testClosure(Map config) {
    String testResultsFolder = config.testResultsFolder as String
    return { defaultImageName, allBuilds ->
        def built = allBuilds[defaultImageName]
        String resultsInWorkspace = 'test_results'

        if (testResultsFolder) {
            sh "mkdir -p ${resultsInWorkspace}; chmod 777 ${resultsInWorkspace}"
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
        }

        if (config.ocpSmoke == true) {
            ocpProbeSmoke(built.image.id, config.ocpSmokeExtraChecks ?: [])
        }

        if (testResultsFolder) {
            junit skipPublishingChecks: true, allowEmptyResults: true, testResults: "${resultsInWorkspace}/*.xml"
        }
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
