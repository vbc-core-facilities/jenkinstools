/**
 * Version 2.1.1 - released as tag deployService/v2.1.1 (see CHANGELOG.md). Bump both with every change to this file.
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



 * Image coherence check (always on; only builds that push an image are checked):
 *   highFidelity         optional, default false. true makes a same-category-but-different-commit finding (see below) fail
 *                        the build instead of marking it UNSTABLE; deployStandardProteomicsService sets it on tag builds.
 *   Every image gets three labels through buildDockerImage's extraBuildArgs, all named with a fixed GUID of this tool:
 *     coherence_guid_<tool GUID>   = <GUID generated for this pipeline run>
 *     provenance_label_<tool GUID> = <build category: the tag name for a tag build, the branch name for a branch build>
 *     coherence_commit_<tool GUID> = <the commit this build builds>
 *   After the pipeline, the image this build pushed (<imageRegistry>/<imageNamespace>/<imageName>:<tag>; for a tag build the
 *   tag, for a branch build the branch tag when the branch is in pushBranches) is pulled and checked. A build that pushes
 *   nothing (pull request, branch not in pushBranches) is not checked at all.
 *   Known issue: the check failing is a known problem, caused by a race condition between builds sharing a local image name.
 *   VBC IT stated they have no plans to fix it (https://vbc.atlassian.net/servicedesk/customer/portal/5/ISD-60715); the
 *   failure and UNSTABLE messages say so.
 *     1. It carries this run's GUID: fine.
 *     2. Another category, or none: FAILS, whatever highFidelity says. The tag holds an image made by a different kind of
 *        build (the tag build's image under master, or the branch build's image under v1.2.3).
 *     3. Same category and same commit: fine, only a warning that it came from a different run.
 *     4. Same category, different commit: highFidelity FAILS. Otherwise the build is UNSTABLE, and the log says whether the
 *        image is older (an older build overwrote this one), newer (a newer build replaced it, e.g. two quick pushes to
 *        master), or of unknown order.
 *   Why: buildDockerImage names the local image after the commit, so two builds of the same commit on one agent (the branch
 *   build and the tag build, in either direction) can overwrite each other's image before the push, and a tag then points to
 *   the other build's image (seen 2026-10-06: tags v2.43.0 / v1.33.0 released as 0.0.0).
 *   A DETECTOR, not a prevention: the push and the Tower deploy happen inside buildDockerImage, so the check runs after them.
 *   A failure says so: the wrong state was found after the pipeline had finished, the deployed state is most likely wrong and
 *   has to be redone. A check that cannot run (pull or inspect fails) only marks the build UNSTABLE.
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
    String buildCategory = buildCategoryOf()
    String buildCommit = pushedImageTag(config) != null ? resolveBuildCommit() : null
    List<String> coherenceFlags = ["--label ${buildGuidLabelName()}=${buildGuid}".toString()]
    if (buildCategory) {
        coherenceFlags << "--label ${buildCategoryLabelName()}=${buildCategory}".toString()
    }
    if (buildCommit) {
        coherenceFlags << "--label ${buildCommitLabelName()}=${buildCommit}".toString()
    }
    String extraBuildArguments = (buildArgumentFlags + secretFlags + coherenceFlags).join(' ')

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
        verifyPushedImageCoherence(config, buildGuid, buildCategory, buildCommit)
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
private String toolVersion() { return '2.1.1' }

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

// Fixed GUID of this tool: it makes the label names unique, so no other label can collide with them.
private String coherenceToolGuid() { return '5ab99355877948ccbde41c74e4a95bdd' }

// Value: the GUID generated for one pipeline run.
private String buildGuidLabelName() { return "coherence_guid_${coherenceToolGuid()}".toString() }

// Value: the build category, the same for every build of one branch or one tag.
private String buildCategoryLabelName() { return "provenance_label_${coherenceToolGuid()}".toString() }

// Value: the commit the build builds.
private String buildCommitLabelName() { return "coherence_commit_${coherenceToolGuid()}".toString() }

// The build category: the tag name for a tag build, the branch name for a branch build. It ends up in a shell command line,
// so it is only used when it consists of docker tag characters (a branch name may contain shell metacharacters). null otherwise.
private String buildCategoryOf() {
    String category = (env.TAG_NAME ?: env.BRANCH_NAME) as String
    return category ==~ /[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}/ ? category : null
}

private boolean isCommitHash(String text) {
    return text != null && text ==~ /[0-9a-f]{40}/
}

// The one image this build pushes: a tag build the tag, a branch build its branch tag. null when this build pushes nothing
// (pull request, branch not in pushBranches): such a build is not checked at all.
private String pushedImageTag(Map config) {
    if (env.TAG_NAME) {
        return env.TAG_NAME as String
    }
    String branch = env.BRANCH_NAME as String
    return branch && (config.pushBranches ?: []).contains(branch) ? branch : null
}

// The commit this build builds. GIT_COMMIT is only set once buildDockerImage has checked out, which is after the labels are
// decided, so this does a checkout of its own. null when it cannot be determined; the check then reports the commit as unknown.
private String resolveBuildCommit() {
    try {
        String commit = null
        node {
            commit = checkout(scm).GIT_COMMIT as String
        }
        return isCommitHash(commit) ? commit : null
    } catch (InterruptedException aborted) {
        throw aborted
    } catch (Exception failure) {
        echo "WARNING: deployService: could not determine the commit being built (${failure.message}); the image coherence check will treat it as unknown"
        return null
    }
}

private String labelOf(String image, String labelName) {
    return sh(
        script: "docker image inspect --format '{{index .Config.Labels \"${labelName}\"}}' ${image}",
        returnStdout: true,
        label: "read the ${labelName} label of the pushed image"
    ).trim()
}

// Where the commit found on the pushed image stands against the commit this build builds, by git ancestry in a checkout of this
// build: 'older', 'newer', 'not-in-checkout' (not reachable from this build's checkout: most likely newer, pushed after this build
// started), 'unrelated' (neither is an ancestor of the other) or 'unknown' (no checkout possible). Both hashes are verified hex.
private String orderOfPushedCommit(String pushedCommit, String buildCommit) {
    try {
        checkout(scm)
        if (sh(script: "git cat-file -e '${pushedCommit}^{commit}'", returnStatus: true) != 0) {
            return 'not-in-checkout'
        }
        if (sh(script: "git merge-base --is-ancestor ${pushedCommit} ${buildCommit}", returnStatus: true) == 0) {
            return 'older'
        }
        if (sh(script: "git merge-base --is-ancestor ${buildCommit} ${pushedCommit}", returnStatus: true) == 0) {
            return 'newer'
        }
        return 'unrelated'
    } catch (InterruptedException aborted) {
        throw aborted
    } catch (Exception failure) {
        return 'unknown'
    }
}

// After the pipeline: what is under the pushed tag must be this run's image, or at least come from the same kind of build.
private void verifyPushedImageCoherence(Map config, String buildGuid, String buildCategory, String buildCommit) {
    String tag = pushedImageTag(config)
    if (tag == null) {
        return
    }
    String pushedImage = "${config.imageRegistry}/${config.imageNamespace}/${config.imageName}:${tag}".toString()
    boolean highFidelity = config.highFidelity == true
    node {
        stage('Verify image coherence') {
            String knownIssue = "KNOWN ISSUE: the image coherence check failing is a known problem, caused by a race condition between builds sharing a local image name, and VBC IT stated they have no plans to fix it. Reference: https://vbc.atlassian.net/servicedesk/customer/portal/5/ISD-60715"

            String labelledGuid
            String labelledCategory
            String labelledCommit
            try {
                sh(script: "docker pull -q ${pushedImage}", label: 'pull the pushed image')
                labelledGuid = labelOf(pushedImage, buildGuidLabelName())
                labelledCategory = labelOf(pushedImage, buildCategoryLabelName())
                labelledCommit = labelOf(pushedImage, buildCommitLabelName())
            } catch (InterruptedException aborted) {
                throw aborted
            } catch (Exception unreadable) {
                unstable("Image coherence could not be checked after the push, verify by hand (${pushedImage}): ${unreadable.message}. ${knownIssue}")
                return
            } finally {
                sh(script: "docker image rm ${pushedImage} || true", label: 'remove the pulled image')
            }

            // 1. this run's own image
            if (labelledGuid == buildGuid) {
                return
            }
            String shown = "${pushedImage} (build category ${labelledCategory ?: 'none'}, commit ${labelledCommit ?: 'none'}, GUID ${labelledGuid ?: 'none'})"
            String afterTheFact = "This was found only now, after the push and the Tower deployment had finished, so whatever was deployed from this tag " +
                "is most likely the wrong image: the deployed state is most likely wrong and has to be redone. " +
                "Cause: another build of the same commit on the same agent re-pointed the shared local image name before the push. " +
                "Redo: let the other build finish, re-run this build, and redeploy. " + knownIssue

            // 2. another category (or none): not acceptable
            if (buildCategory == null || labelledCategory != buildCategory) {
                error("IMAGE COHERENCE CHECK FAILED AFTER THE PIPELINE HAD ALREADY PUSHED AND DEPLOYED. This run (build category ${buildCategory ?: 'unknown'}, " +
                    "commit ${buildCommit ?: 'unknown'}, GUID ${buildGuid}) built its image, but ${shown} was made by a different kind of build. " + afterTheFact)
            }

            // 3. same category, same commit: another run of the same thing
            if (buildCommit != null && labelledCommit == buildCommit) {
                echo "WARNING: image coherence: ${shown} was made by another run of the same build category and commit as this run (GUID ${buildGuid}). " +
                    "It is the same code, but it did not come from this run."
                return
            }

            // 4. same category, different (or unknown) commit
            boolean commitsKnownDifferent = isCommitHash(buildCommit) && isCommitHash(labelledCommit)
            if (commitsKnownDifferent && highFidelity) {
                error("IMAGE COHERENCE CHECK FAILED AFTER THE PIPELINE HAD ALREADY PUSHED AND DEPLOYED. This run built commit ${buildCommit} for build category " +
                    "${buildCategory}, but ${shown} holds a different commit. " + afterTheFact)
            }
            String order = commitsKnownDifferent ? orderOfPushedCommit(labelledCommit, buildCommit) : 'unknown'
            Map orderText = [
                older            : 'an OLDER commit than this build: an older build of the same branch overwrote this build\'s image after it was pushed, so the registry (and whatever was deployed from it) is behind this build',
                newer            : 'a NEWER commit than this build: a newer build of the same branch replaced this build\'s image',
                'not-in-checkout': 'a commit that is not reachable from this build\'s commit: most likely a NEWER one pushed after this build started, otherwise one from unrelated history',
                unrelated        : 'a commit unrelated to this build\'s commit (neither is an ancestor of the other, force-push?)',
                unknown          : 'a commit whose order against this build could not be determined',
            ]
            String message = "image coherence: ${shown} holds ${orderText[order]}. This build (GUID ${buildGuid}, commit ${buildCommit ?: 'unknown'}) is not highFidelity, so this only marks it UNSTABLE. ${knownIssue}"
            echo "WARNING: ${message}"
            unstable(message)
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
