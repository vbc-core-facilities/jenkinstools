/**
 * Version 1.0.0 - released as tag deployStandardProteomicsService/v1.0.0 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * The VBC proteomics (protchem) service conventions on top of deployService: it fills in the
 * standard names, branches, build args, secrets, tests and notification, so a standard service's
 * Jenkinsfile needs only a few inputs. Every convention can be overridden; anything not listed
 * here is passed straight through to deployService (e.g. testScript, dockerContext, towerJobs,
 * beforeBuild / afterBuild / afterAlways).
 *
 * Required:
 *   imageName            image name, pushed as protchem/<imageName>
 *   dockerFile           path to the Dockerfile
 *   towerName            the service's name in Tower job names: 'Hive Frontend' gives
 *                        'App Protchem Hive Frontend Staging' / 'App Protchem Hive Frontend Production'.
 *                        Not needed when `tower` is given in full, or with tower: false.
 *
 * Conventions (defaults) and how to override them:
 *   imageNamespace       'protchem'
 *   pushBranches         ['master', 'develop']
 *   tower                [staging: 'App Protchem <towerName> Staging', stagingBranch: 'master',
 *                         production: 'App Protchem <towerName> Production',
 *                         imageTagVariable: 'app_generic_image_tag'].
 *                        A Map is merged over these (e.g. just [production: '...']); false = no Tower.
 *   testResultsFolder    '/app/tests/results' (tests: false for no test step at all)
 *   ocpSmoke             true
 *   buildArgs            merged over the standard build args below; a null value removes one:
 *                          NUGET_REPO_USER          'vbc-proteomics'
 *                          MINVER_VERSION_OVERRIDE  git tag without a leading 'v' ('' if not a tag build)
 *                          ASPNET_PORT              '8080'
 *                          APP_USER                 'app'
 *                          APP_GROUP_GID            '0'
 *                          TEST_RESULTS_FOLDER      <testResultsFolder>
 *                          SEQ_VERSION              '2026.1.17044' (pinned; [SEQ_VERSION: null] for no Seq)
 *   defaultBuildArgs     false to start from no standard build args
 *   secrets              merged by id over the standard BuildKit secret:
 *                          [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat',
 *                           kind: 'usernamePassword']
 *   defaultSecrets       false to start from no standard secrets
 *   cockpitNotify        [credentialsId: 'vbc-cockpit-service-bus-send']; a Map replaces it; false = off
 */
def call(Map config = [:]) {
    echo "deployStandardProteomicsService v${toolVersion()} (jenkinstools)"

    List<String> problems = ['imageName', 'dockerFile'].findAll { !config[it] }.collect { "${it} is required".toString() }
    boolean towerGivenInFull = config.tower instanceof Map && config.tower.staging && config.tower.production
    if (config.tower != false && !config.towerName && !towerGivenInFull) {
        problems << 'towerName is required (or pass tower in full, or tower: false)'
    }
    if (problems) {
        error("deployStandardProteomicsService: invalid configuration:\n  - ${problems.join('\n  - ')}")
    }

    boolean tests = config.tests != false
    String testResultsFolder = tests ? (config.testResultsFolder ?: '/app/tests/results') : null

    // ---- build args: standard set, then the repo's additions/overrides; null removes ----------------
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
    buildArgs = buildArgs.findAll { k, v -> v != null }

    // ---- secrets: standard set, then the repo's, merged by id -----------------------------------------
    Map<String, Map> secretsById = [:]
    if (config.defaultSecrets != false) {
        secretsById['SECRETS-NUGET-REPO-PW'] =
            [id: 'SECRETS-NUGET-REPO-PW', credentialsId: 'vbc-proteomics-github-pat', kind: 'usernamePassword']
    }
    (config.secrets ?: []).each { Map s -> secretsById[s.id as String] = s }

    // ---- tower: naming convention, merged with the repo's overrides ------------------------------------
    Map tower = null
    if (config.tower != false && !config.towerJobs) {
        tower = [stagingBranch: 'master', imageTagVariable: 'app_generic_image_tag']
        if (config.towerName) {
            tower.staging = "App Protchem ${config.towerName} Staging".toString()
            tower.production = "App Protchem ${config.towerName} Production".toString()
        }
        tower += (config.tower instanceof Map ? config.tower : [:])
    }

    // ---- everything else passes through ----------------------------------------------------------------
    Set<String> handled = ['towerName', 'tower', 'tests', 'testResultsFolder', 'buildArgs', 'defaultBuildArgs',
                           'secrets', 'defaultSecrets', 'imageNamespace', 'pushBranches', 'ocpSmoke', 'cockpitNotify'] as Set
    Map passThrough = config.findAll { k, v -> !(k in handled) }

    Map resolved = passThrough + [
        imageNamespace: config.imageNamespace ?: 'protchem',
        pushBranches  : config.pushBranches != null ? config.pushBranches : ['master', 'develop'],
        buildArgs     : buildArgs,
        secrets       : secretsById.values() as List,
        ocpSmoke      : tests && config.ocpSmoke != false,
    ]
    if (tower) resolved.tower = tower
    if (testResultsFolder) resolved.testResultsFolder = testResultsFolder
    if (!tests) {
        resolved.remove('testScript')
        resolved.remove('ocpSmokeExtraChecks')
    }
    if (config.cockpitNotify != false) {
        resolved.cockpitNotify = config.cockpitNotify ?: [credentialsId: 'vbc-cockpit-service-bus-send']
    }

    deployService(resolved)
}

// This tool's own version; bump it (and tag deployStandardProteomicsService/vX.Y.Z) with every change to this file.
private String toolVersion() { return '1.0.0' }
