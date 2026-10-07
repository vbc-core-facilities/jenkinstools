/**
 * Version 2.2.0 - released as tag deployStandardProteomicsService/v2.2.0 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * The VBC proteomics (protchem) service conventions on top of deployService: it fills in the
 * standard namespace, branches, build args, secrets, tests and notification, so a standard
 * service's Jenkinsfile needs only a few inputs. Every convention can be overridden; anything not
 * listed here is passed straight through to deployService (e.g. towerJobs, beforeBuild /
 * afterBuild / afterAlways).
 *
 * Required:
 *   imageName               image name, pushed as protchem/<imageName>
 *   dockerFile              path to the Dockerfile
 *   dockerContext           docker build context, e.g. './' (never left to buildDockerImage's default)
 *   towerStagingJobName     the Tower staging job, exactly as named in Tower
 *   towerProductionJobName  the Tower production job, exactly as named in Tower
 *                           Both are mapped by hand (IT names them); not needed with tower: false.
 *
 * Optional:
 *   dockerHttpPort          the port the container listens on, passed as build arg APP_PORT (the
 *                           stack-neutral name from the OCP deployment standard). The Dockerfile maps
 *                           it to its stack, e.g. ASPNETCORE_HTTP_PORTS for Kestrel. null or omitted:
 *                           no build arg, the Dockerfile's own default applies.
 *
 * Conventions (defaults) and how to override them:
 *   imageNamespace          'protchem'
 *   pushBranches            ['master', 'develop']
 *   tower                   [stagingBranch: 'master', imageTagVariable: 'app_generic_image_tag'];
 *                           a Map overrides either; false = no Tower (build and push only).
 *                           Staging runs on pushes to stagingBranch, production on tags.
 *   testResultsFolder       '/app/tests/results' (tests: false for no test step at all)
 *   testScript              none; passed through when tests are on
 *   ocpSmoke                true
 *   ocpSmokeExtraChecks     none; passed through when tests are on
 *   buildArgs               merged over the standard build args below; a null value removes one:
 *                             NUGET_REPO_USER          'vbc-proteomics'
 *                             MINVER_VERSION_OVERRIDE  git tag without a leading 'v' ('' if not a tag build)
 *                             APP_USER                 'app'
 *                             APP_GROUP_GID            '0'
 *                             TEST_RESULTS_FOLDER      <testResultsFolder>
 *                             SEQ_VERSION              '2026.1.17044' (pinned; [SEQ_VERSION: null] for no Seq)
 *   defaultBuildArgs        false to start from no standard build args (dockerHttpPort still applies)
 *   secrets                 merged by id over the standard BuildKit secret:
 *                             [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat',
 *                              kind: 'usernamePassword']
 *   defaultSecrets          false to start from no standard secrets
 *   imageRegistry           'docker.artifactory.imp.ac.at', the registry the image is pushed to. After every build that pushes,
 *                           deployService checks that the pushed image is this run's or at least comes from the same kind of build
 *                           (always on; see deployService). It reports after the deploy that the deployed state is wrong.
 *   highFidelity            true on tag builds, false otherwise; the Map input overrides it. With it, the check fails the build when
 *                           the pushed image holds another commit than the one built, without it the build only goes UNSTABLE.
 *   cockpitNotify           [credentialsId: 'vbc-cockpit-service-bus-send']; a Map replaces it; false = off
 */
def call(Map config = [:]) {
    echo "deployStandardProteomicsService v${toolVersion()} (jenkinstools)"
    failOnInvalidConfiguration(config)

    boolean runTests = config.tests != false
    String testResultsFolder = runTests ? (config.testResultsFolder ?: '/app/tests/results') : null

    Map buildArguments = resolveBuildArguments(config, testResultsFolder)
    List<Map> buildKitSecrets = resolveBuildKitSecrets(config)
    Map tower = resolveTower(config)
    Map cockpitNotification = resolveCockpitNotification(config)

    Map conventionParameters = [
        imageNamespace     : config.imageNamespace ?: 'protchem',
        pushBranches       : config.pushBranches != null ? config.pushBranches : ['master', 'develop'],
        buildArgs          : buildArguments,
        secrets            : buildKitSecrets,
        tower              : tower,
        testResultsFolder  : testResultsFolder,
        testScript         : runTests ? config.testScript : null,
        ocpSmoke           : runTests && config.ocpSmoke != false,
        ocpSmokeExtraChecks: runTests ? config.ocpSmokeExtraChecks : null,
        cockpitNotify      : cockpitNotification,
        imageRegistry      : config.imageRegistry ?: 'docker.artifactory.imp.ac.at',
        highFidelity       : config.highFidelity != null ? config.highFidelity : (env.TAG_NAME != null),
    ]
    List<String> handledInputNames = [
        'imageNamespace',
        'pushBranches',
        'dockerHttpPort',
        'buildArgs',
        'defaultBuildArgs',
        'secrets',
        'defaultSecrets',
        'tower',
        'towerStagingJobName',
        'towerProductionJobName',
        'tests',
        'testResultsFolder',
        'testScript',
        'ocpSmoke',
        'ocpSmokeExtraChecks',
        'cockpitNotify',
        'imageRegistry',
        'highFidelity',
    ]
    Map passThroughParameters = config.findAll { inputName, inputValue -> !(inputName in handledInputNames) }

    Map deployServiceParameters = (passThroughParameters + conventionParameters).findAll { parameterName, parameterValue ->
        parameterValue != null
    }
    deployService(deployServiceParameters)
}

// This tool's own version; bump it (and tag deployStandardProteomicsService/vX.Y.Z) with every change to this file.
private String toolVersion() { return '2.2.0' }

private void failOnInvalidConfiguration(Map config) {
    List<String> requiredInputNames = [
        'imageName',
        'dockerFile',
        'dockerContext',
    ]
    boolean usesTowerConvention = config.tower != false && !config.towerJobs
    List<String> towerJobInputNames = usesTowerConvention ? ['towerStagingJobName', 'towerProductionJobName'] : []

    List<String> missingInputNames = requiredInputNames.findAll { String inputName -> !config[inputName] }
    List<String> missingTowerJobInputNames = towerJobInputNames.findAll { String inputName -> !config[inputName] }

    List<String> missingInputProblems = missingInputNames.collect { String inputName ->
        "${inputName} is required".toString()
    }
    List<String> missingTowerJobProblems = missingTowerJobInputNames.collect { String inputName ->
        "${inputName} is required (or tower: false for no Tower)".toString()
    }
    boolean towerJobsInTowerMap = config.tower instanceof Map && (config.tower.staging || config.tower.production)
    List<String> towerMapProblems = towerJobsInTowerMap
        ? ['set the Tower jobs with towerStagingJobName / towerProductionJobName, not tower.staging / tower.production']
        : []

    List<String> problems = missingInputProblems + missingTowerJobProblems + towerMapProblems
    if (!problems.isEmpty()) {
        String problemList = problems.join('\n  - ')
        error("deployStandardProteomicsService: invalid configuration:\n  - ${problemList}")
    }
}

// Standard build args, then APP_PORT from dockerHttpPort, then the repo's own; a null value removes one.
private Map resolveBuildArguments(Map config, String testResultsFolder) {
    String minVerVersion = env.TAG_NAME ? env.TAG_NAME.replaceFirst(/^v/, '') : ''
    Map standardBuildArguments = config.defaultBuildArgs == false ? [:] : [
        NUGET_REPO_USER        : 'vbc-proteomics',
        MINVER_VERSION_OVERRIDE: minVerVersion,
        APP_USER               : 'app',
        APP_GROUP_GID          : '0',
        TEST_RESULTS_FOLDER    : testResultsFolder,
        // pinned deliberately; ':latest' would make rebuilds non-reproducible
        SEQ_VERSION            : '2026.1.17044',
    ]
    Map portBuildArguments = config.dockerHttpPort != null ? [APP_PORT: config.dockerHttpPort as String] : [:]
    Map repositoryBuildArguments = config.buildArgs ?: [:]

    Map mergedBuildArguments = standardBuildArguments + portBuildArguments + repositoryBuildArguments
    return mergedBuildArguments.findAll { argumentName, argumentValue -> argumentValue != null }
}

// The standard NuGet feed secret, replaced by a repo secret with the same id; repo secrets added.
private List<Map> resolveBuildKitSecrets(Map config) {
    List<Map> standardSecrets = config.defaultSecrets == false ? [] : [
        [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat', kind: 'usernamePassword'],
    ]
    List<Map> repositorySecrets = config.secrets ?: []
    List<String> repositorySecretIds = repositorySecrets.collect { Map repositorySecret -> repositorySecret.id as String }
    List<Map> keptStandardSecrets = standardSecrets.findAll { Map standardSecret ->
        !(standardSecret.id in repositorySecretIds)
    }
    return keptStandardSecrets + repositorySecrets
}

private Map resolveTower(Map config) {
    boolean usesTowerConvention = config.tower != false && !config.towerJobs
    if (!usesTowerConvention) {
        return null
    }
    Map towerOverrides = config.tower instanceof Map ? config.tower : [:]
    return [
        staging         : config.towerStagingJobName,
        stagingBranch   : towerOverrides.stagingBranch ?: 'master',
        production      : config.towerProductionJobName,
        imageTagVariable: towerOverrides.imageTagVariable ?: 'app_generic_image_tag',
    ]
}

private Map resolveCockpitNotification(Map config) {
    if (config.cockpitNotify == false) {
        return null
    }
    return config.cockpitNotify ?: [credentialsId: 'vbc-cockpit-service-bus-send']
}
