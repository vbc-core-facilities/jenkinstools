/**
 * Version 2.0.0 - released as tag deployService/v2.0.0 (see CHANGELOG.md). Bump both with every change to this file.
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
 *   imageRegistry        registry host the image is pushed to, e.g. docker.artifactory.imp.ac.at (the coherence check pulls from it)
 *   pushBranches         List of branches whose images are pushed (may be empty)
 *   dockerContext        optional build context; buildDockerImage's own default ('.') when omitted
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

 * Image coherence check (always on, nothing to configure):
 *   Each pipeline run generates a GUID and has it put on its image as the label coherence_guid_5ab99355877948ccbde41c74e4a95bdd (through
 *   buildDockerImage's extraBuildArgs). After the pipeline, the image this build pushed (<imageRegistry>/<imageNamespace>/
 *   <imageName>:<tag>, for a tag build the tag, for a branch build the branch tag when the branch is in pushBranches) is
 *   pulled and must carry that same GUID. If it does not, the image under that tag was made by another build.
 *   Why: buildDockerImage names the local image after the commit, so two builds of the same commit on one agent (the branch
 *   build and the tag build, in either direction) can overwrite each other's image before the push, and a tag then points to
 *   the other build's image (seen 2026-10-06: tags v2.43.0 / v1.33.0 released as 0.0.0).
 *   A DETECTOR, not a prevention: the push and the Tower deploy happen inside buildDockerImage, so the check runs after them.
 *   When it fails the build says so: the wrong state was found after the pipeline had finished, the deployed state is most
 *   likely wrong and has to be redone. A check that cannot run (pull or inspect fails) only marks the build UNSTABLE.
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
    failOnInvalidConfiguration(config)
    echo "deployService v${toolVersion()} (jenkinstools)"

    String imageTag = env.TAG_NAME ?: 'latest'

    // ---- build args + BuildKit secrets ----------------------------------------------------------------
    Map buildArguments = (config.buildArgs ?: [:]).findAll { argumentName, argumentValue -> argumentValue != null }
    List<Map> buildKitSecrets = config.secrets ?: []

    buildKitSecrets.each { Map buildKitSecret -> exposeSecretToBuildKit(buildKitSecret) }

    List<String> buildArgumentFlags = buildArguments.collect { argumentName, argumentValue ->
        "--build-arg ${argumentName}=\"${argumentValue}\"".toString()
    }
    List<String> secretFlags = buildKitSecrets.collect { Map buildKitSecret -> buildKitSecretFlag(buildKitSecret) }
    List<String> secretIds = buildKitSecrets.collect { Map buildKitSecret -> buildKitSecret.id as String }
    String buildGuid = UUID.randomUUID().toString()
    String buildGuidFlag = "--label ${buildGuidLabelName()}=${buildGuid}".toString()
    String extraBuildArguments = (buildArgumentFlags + secretFlags + [buildGuidFlag]).join(' ')

    // ---- tower ------------------------------------------------------------------------------------------
    Map towerJobs = config.towerJobs ?: toTowerJobs(config.tower, imageTag)

    // ---- build info -------------------------------------------------------------------------------------
    reportBuildInfo(config, buildArguments, secretIds, towerJobs)

    // ---- pipeline ---------------------------------------------------------------------------------------
    Closure testStep = needsTestStep(config) ? inImageTestStep(config) : null

    // Parameters that are not set are left out entirely, so buildDockerImage applies its own defaults.
    Map buildDockerImageParameters = [
        imageName            : config.imageName,
        dockerFile           : config.dockerFile,
        dockerContext        : config.dockerContext,
        pushRegistryNamespace: config.imageNamespace,
        pushBranches         : config.pushBranches,
        extraBuildArgs       : extraBuildArguments ?: null,
        tower                : towerJobs,
        test                 : testStep,
    ].findAll { parameterName, parameterValue -> parameterValue != null }

    Map cockpitNotification = config.cockpitNotify ?: null

    notifyCockpit(cockpitNotification, 'started')
    try {
        runActions(config.beforeBuild)
        buildDockerImage(buildDockerImageParameters)
        verifyPushedImageCoherence(config, buildGuid)
        runActions(config.afterBuild)
    } catch (buildFailure) {
        currentBuild.result = 'FAILURE' // so the 'finished' notification reports the real result
        throw buildFailure
    } finally {
        try {
            runActions(config.afterAlways)
        } finally {
            notifyCockpit(cockpitNotification, 'finished')
        }
    }
}

// This tool's own version; bump it (and tag deployService/vX.Y.Z) with every change to this file.
private String toolVersion() { return '2.0.0' }

// ---- validation --------------------------------------------------------------------------------------

private void failOnInvalidConfiguration(Map config) {
    List<String> problems = requiredInputProblems(config) +
        towerProblems(config) +
        testProblems(config) +
        secretProblems(config) +
        actionProblems(config)
    if (!problems.isEmpty()) {
        String problemList = problems.join('\n  - ')
        error("deployService: invalid configuration:\n  - ${problemList}")
    }
}

private List<String> requiredInputProblems(Map config) {
    List<String> requiredInputNames = [
        'imageName',
        'dockerFile',
        'imageNamespace',
        'imageRegistry',
    ]
    List<String> missingInputNames = requiredInputNames.findAll { String inputName -> !config[inputName] }
    List<String> missingInputProblems = missingInputNames.collect { String inputName -> "${inputName} is required".toString() }
    String pushBranchesProblem = config.pushBranches instanceof List ? null : 'pushBranches is required (a List, may be empty)'
    return withoutNulls(missingInputProblems + [pushBranchesProblem])
}

private List<String> towerProblems(Map config) {
    Map tower = config.tower ?: [:]
    List<String> candidateProblems = [
        tower.staging && !tower.stagingBranch ? 'tower.staging needs tower.stagingBranch' : null,
        tower.stagingBranch && !tower.staging ? 'tower.stagingBranch needs tower.staging' : null,
        tower.production && !tower.imageTagVariable ? 'tower.production needs tower.imageTagVariable' : null,
        config.tower && config.towerJobs ? 'pass tower or towerJobs, not both' : null,
    ]
    return withoutNulls(candidateProblems)
}

private List<String> testProblems(Map config) {
    List<String> candidateProblems = [
        config.testScript && !config.testResultsFolder ? 'testScript needs testResultsFolder' : null,
        config.ocpSmokeExtraChecks && config.ocpSmoke != true ? 'ocpSmokeExtraChecks needs ocpSmoke: true' : null,
    ]
    return withoutNulls(candidateProblems)
}

private List<String> secretProblems(Map config) {
    List<Map> buildKitSecrets = config.secrets ?: []
    List<String> secretKinds = ['usernamePassword', 'string']

    List<Map> malformedSecrets = buildKitSecrets.findAll { Map buildKitSecret ->
        !buildKitSecret.id || !buildKitSecret.credentialsId || !(buildKitSecret.kind in secretKinds)
    }
    List<String> malformedSecretProblems = malformedSecrets.collect { Map buildKitSecret ->
        "secret needs id, credentialsId and kind 'usernamePassword' or 'string': ${buildKitSecret}".toString()
    }

    List<String> secretIds = buildKitSecrets.collect { Map buildKitSecret -> buildKitSecret.id as String }
    Set<String> distinctSecretIds = secretIds as Set
    String duplicateIdProblem = secretIds.size() != distinctSecretIds.size() ? "duplicate secret ids: ${secretIds}".toString() : null

    return withoutNulls(malformedSecretProblems + [duplicateIdProblem])
}

private List<String> actionProblems(Map config) {
    List<String> actionNames = [
        'beforeBuild',
        'afterBuild',
        'afterAlways',
    ]
    List<String> invalidActionNames = actionNames.findAll { String actionName ->
        def action = config[actionName]
        boolean isClosure = action instanceof Closure
        boolean isListOfClosures = action instanceof List && action.every { listElement -> listElement instanceof Closure }
        action != null && !isClosure && !isListOfClosures
    }
    return invalidActionNames.collect { String actionName -> "${actionName} must be a Closure or a List of Closures".toString() }
}

private List<String> withoutNulls(List<String> candidates) {
    return candidates.findAll { String candidate -> candidate != null }
}

// ---- build inputs ----------------------------------------------------------------------------------------

private String secretEnvironmentVariableName(Map buildKitSecret) {
    String sanitizedId = (buildKitSecret.id as String).replaceAll(/[^A-Za-z0-9_]/, '_')
    return "secret_${sanitizedId}"
}

// BuildKit reads the secret from this env var, so it never appears in build args or logs.
private void exposeSecretToBuildKit(Map buildKitSecret) {
    String credentialsId = buildKitSecret.credentialsId as String
    def credentialBinding = buildKitSecret.kind == 'string'
        ? string(credentialsId: credentialsId, variable: 'DEPLOY_SECRET')
        : usernamePassword(credentialsId: credentialsId, usernameVariable: 'DEPLOY_SECRET_USER', passwordVariable: 'DEPLOY_SECRET')
    String environmentVariableName = secretEnvironmentVariableName(buildKitSecret)
    withCredentials([credentialBinding]) {
        env."${environmentVariableName}" = "${DEPLOY_SECRET}"
    }
}

private String buildKitSecretFlag(Map buildKitSecret) {
    String environmentVariableName = secretEnvironmentVariableName(buildKitSecret)
    return "--secret id=${buildKitSecret.id},type=env,env=${environmentVariableName}".toString()
}

// [staging, stagingBranch, production, imageTagVariable] -> buildDockerImage's tower map, or null.
private Map toTowerJobs(Map tower, String imageTag) {
    if (!tower) {
        return null
    }
    Map stagingJobs = tower.staging
        ? [(tower.stagingBranch as String): [jobName: tower.staging]]
        : [:]
    Map productionJobs = tower.production
        ? [tags: [jobName: tower.production, extraVars: "${tower.imageTagVariable}: ${imageTag}".toString()]]
        : [:]
    return stagingJobs + productionJobs
}

// ---- reporting ---------------------------------------------------------------------------------------------

private void reportBuildInfo(Map config, Map buildArguments, List<String> secretIds, Map towerJobs) {
    node {
        stage('Build info') {
            sh 'docker version'
            sh 'docker buildx version'
            echo "Image: ${config.imageNamespace}/${config.imageName} from ${config.dockerFile} (context ${config.dockerContext ?: '<buildDockerImage default>'}), pushed on ${config.pushBranches}"
            echo "Build args:\n${buildArguments}"
            echo "Secret ids injected into docker:\n${secretIds}"
            echo "Tower jobs:\n${towerJobs ?: 'none (build and push only)'}"
            echo "GIT_COMMIT=${env.GIT_COMMIT}"
            echo "GIT_PREVIOUS_SUCCESSFUL_COMMIT=${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
            currentBuild.changeSets.each { changeSet ->
                changeSet.items.each { changeEntry ->
                    echo "Change: ${changeEntry.commitId} ${changeEntry.author} ${changeEntry.msg}"
                }
            }
        }
    }
}

// ---- actions and notification ------------------------------------------------------------------------------

private void runActions(def actions) {
    if (actions == null) {
        return
    }
    List<Closure> actionList = actions instanceof List ? actions : [actions]
    actionList.each { Closure action -> action() }
}

private void notifyCockpit(Map cockpitNotification, String event) {
    if (!cockpitNotification) {
        return
    }
    try {
        node {
            vbcDeploymentCockpitNotify(event, cockpitNotification) // credentialsId or connectionString, + options
        }
    } catch (InterruptedException aborted) {
        throw aborted
    } catch (Exception failure) {
        // e.g. no agent available - never let the notification break a build unless asked to
        if (cockpitNotification.failOnError == true) {
            throw failure
        }
        echo "WARNING: deployService: cockpit '${event}' notification failed: ${failure.message} - continuing"
    }
}

// ---- image coherence check -----------------------------------------------------------------------------------

// The label this library puts on its own image (through buildDockerImage's extraBuildArgs), carrying the pipeline run's GUID.
private String buildGuidLabelName() { return 'coherence_guid_5ab99355877948ccbde41c74e4a95bdd' }

// The one image this build pushed: a tag build the tag, a branch build its branch tag. null when this build pushes nothing.
private String pushedImageTag(Map config) {
    if (env.TAG_NAME) {
        return env.TAG_NAME as String
    }
    String branch = env.BRANCH_NAME as String
    return branch && (config.pushBranches ?: []).contains(branch) ? branch : null
}

// After the pipeline: the image under the pushed tag must carry the GUID this run put on its own image.
private void verifyPushedImageCoherence(Map config, String buildGuid) {
    String tag = pushedImageTag(config)
    if (tag == null) {
        return
    }
    String pushedImage = "${config.imageRegistry}/${config.imageNamespace}/${config.imageName}:${tag}".toString()
    node {
        stage('Verify image coherence') {
            String labelledGuid
            try {
                sh(script: "docker pull -q ${pushedImage}", label: 'pull the pushed image')
                labelledGuid = sh(
                    script: "docker image inspect --format '{{index .Config.Labels \"${buildGuidLabelName()}\"}}' ${pushedImage}",
                    returnStdout: true,
                    label: 'read the build GUID the pushed image carries'
                ).trim()
            } catch (InterruptedException aborted) {
                throw aborted
            } catch (Exception unreadable) {
                unstable("Image coherence could not be checked after the push, verify by hand (${pushedImage}): ${unreadable.message}")
                return
            } finally {
                sh(script: "docker image rm ${pushedImage} || true", label: 'remove the pulled image')
            }
            if (labelledGuid != buildGuid) {
                error("IMAGE COHERENCE CHECK FAILED AFTER THE PIPELINE HAD ALREADY PUSHED AND DEPLOYED. This run put the build GUID ${buildGuid} on its image, " +
                    "but ${pushedImage} carries ${labelledGuid ?: 'none'}: it was made by another build. " +
                    "This was found only now, after the push and the Tower deployment had finished, so whatever was deployed from this tag is most likely " +
                    "the wrong image: the deployed state is most likely wrong and has to be redone. " +
                    "Cause: another build of the same commit on the same agent re-pointed the shared local image name before the push. " +
                    "Redo: let the other build finish, re-run this build, and redeploy.")
            }
        }
    }
}

// ---- tests -------------------------------------------------------------------------------------------------

private boolean needsTestStep(Map config) {
    return config.testResultsFolder || config.testScript || config.ocpSmoke == true
}

// buildDockerImage calls this with (defaultImageName, allBuilds) after building the image.
private Closure inImageTestStep(Map config) {
    String testResultsFolder = config.testResultsFolder as String
    String workspaceResultsFolder = 'test_results'

    return { defaultImageName, allBuilds ->
        def builtImage = allBuilds[defaultImageName]

        if (testResultsFolder) {
            sh "mkdir -p ${workspaceResultsFolder}; chmod 777 ${workspaceResultsFolder}"
            try {
                builtImage.image.inside() {
                    if (config.testScript) {
                        int testScriptExitCode = sh(
                            script: "${config.testScript} ${testResultsFolder}",
                            returnStatus: true,
                            label: 'in-image tests'
                        )
                        if (testScriptExitCode > 0) {
                            unstable('Test script returned a non-zero exit code.')
                        }
                    }
                    sh """
                    if [ -d ${testResultsFolder} ]; then
                      cp -r ${testResultsFolder}/. ${env.WORKSPACE}/${workspaceResultsFolder}
                    else
                      echo "No test results found in ${testResultsFolder}"
                    fi
                    """
                }
            } catch (testFailure) {
                echo "Error occurred while running/collecting in-image tests: ${testFailure}"
                unstable('Exception raised while running/collecting in-image tests.')
            }
        }

        if (config.ocpSmoke == true) {
            List<String> extraSmokeChecks = config.ocpSmokeExtraChecks ?: []
            ocpProbeSmoke(builtImage.image.id as String, extraSmokeChecks)
        }

        if (testResultsFolder) {
            junit skipPublishingChecks: true, allowEmptyResults: true, testResults: "${workspaceResultsFolder}/*.xml"
        }
    }
}

// Container-mechanics smoke, NOT an application-readiness test: the fixed OCP probe paths exist and
// are executable by an arbitrary UID whose only group is 0, /logs is writable, curl is present, the
// entrypoint honours a passed-in command, and - via the negative control - the probes can report
// failure at all. It never starts the app (one-shot docker run), which is what makes the negative
// control meaningful. A failure marks the build UNSTABLE, never FAILED: an image-mechanics
// regression must be loud without blocking an otherwise shippable artifact.
private void ocpProbeSmoke(String imageId, List<String> extraSmokeChecks) {
    try {
        String repoSpecificChecks = extraSmokeChecks
            ? '\n# repo-specific checks\n' + extraSmokeChecks.join('\n') + '\n'
            : ''
        String mechanicsScriptStart = '''
set -e
id

# Fixed-path contract + permission model under the arbitrary UID.
for p in startup live ready; do
  test -x /ocp/probes/$p || { echo "[smoke] not executable by arbitrary UID: /ocp/probes/$p" >&2; exit 1; }
done
command -v curl >/dev/null 2>&1 || { echo "[smoke] curl missing from runtime image" >&2; exit 1; }
touch /logs/.smoke-write-test && rm -f /logs/.smoke-write-test
'''
        String mechanicsScriptEnd = '''
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
'''
        String mechanicsScript = (mechanicsScriptStart + repoSpecificChecks + mechanicsScriptEnd).trim()

        int smokeExitCode = sh(
            script: "docker run --rm --user 123456:0 ${imageId} '${mechanicsScript}'",
            returnStatus: true,
            label: 'ocp arbitrary uid probe mechanics smoke'
        )
        if (smokeExitCode > 0) {
            unstable('OCP arbitrary-UID probe mechanics smoke returned non-zero exit code.')
        }
    } catch (smokeFailure) {
        echo "Error occurred while running OCP arbitrary-UID probe smoke: ${smokeFailure}"
        unstable('Exception raised while running OCP arbitrary-UID probe smoke.')
    }
}
