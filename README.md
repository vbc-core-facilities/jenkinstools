# jenkinstools

Small, reusable [Jenkins shared library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/) steps. Every `vars/<name>.groovy` file is a pipeline step called `<name>`.

| Step | What it does |
| --- | --- |
| `webhookNotify` | Sends any message to any HTTP(S) URL. An optional secret goes into an auth header. |
| `serviceBusNotify` | Sends any message to an Azure Service Bus queue or topic, given a connection string or a SAS token. |
| `buildEventMessage` | Builds a generic `jenkins-build-event/v1` message describing the current build. |
| `vbcDeploymentCockpitNotify` | Sends a build event to the VBC Deployment Cockpit (fixed topic), so the cockpit shows the build as running. |
| `deployService` | Generic service pipeline wiring with **no defaults**: build args and BuildKit secrets, build-info reporting, in-image tests and OCP smoke, push, Tower, cockpit notification, and pre/post actions. |
| `deployStandardProteomicsService` | The protchem conventions (namespace, branches, standard build args, secrets, tests, notification) on top of `deployService`, all overridable. A standard service needs only its image, Dockerfile and its two Tower job names. |

**Failures don't break the build by default.** Any notification problem only prints a warning and the build carries on. `failOnError: true` fails the build instead. Aborting a build is never swallowed.

**Requirements:** the steps run inside `node {}` and use `sh` and `curl` (7.55 or newer). `serviceBusNotify` with a connection string also needs `openssl` on the agent. `deployService` also needs IT's `buildDockerImage` (the implicitly loaded `vbc-cicd` library).

## Versioning

**Every tool in this library is versioned, independently of the others. There are no unversioned consumers.**

- **Each tool has its own semver version.** It's stated in the header of its `vars/<tool>.groovy`, and each release is an immutable git tag `<tool>/vMAJOR.MINOR.PATCH`, e.g. `deployService/v1.0.0` or `vbcDeploymentCockpitNotify/v1.1.0`. A tag is never moved or deleted once pushed. A fix is a new tag.
- **Semver applies to the tool's arguments and behaviour:**
  - **MAJOR:** anything that could break an existing Jenkinsfile, such as a removed or renamed argument, a changed default, or a changed message schema.
  - **MINOR:** new optional arguments or behaviour.
  - **PATCH:** fixes that don't change the interface.
  - Changing a tool that others use (e.g. `serviceBusNotify`, which `deployService` uses) releases that tool. The tools that depend on it get a new release only when they want to pick the change up.
- **A Jenkinsfile pins the tag of the tool it calls:** `library identifier: 'jenkinstools@deployStandardProteomicsService/v2.0.0', …`.
  - **How loading works:** Jenkins loads the library at that single git ref, so the tools the pinned tool uses come from the same commit. That snapshot was released and tested together.
  - **Calling several tools directly** means you pin one of their tags; the others are whatever they were at that commit.
  - **Never pin `main`** in a real pipeline. It's only for trying a change on one throwaway branch.
- **Roll out gradually:** release a new tag of a tool, then bump the pin one Jenkinsfile at a time. Every other pipeline stays locked to the release it already has. To roll back, revert the pin.
- **Every release updates, in the tagged commit:**
  - the version in the tool's header, plus its `toolVersion()` where it has one (the deploy tools print it in the build log)
  - the tool's section in `CHANGELOG.md`
- **Releasing a tool:** commit, then `git tag -a <tool>/vX.Y.Z -m "<tool> vX.Y.Z" && git push origin main <tool>/vX.Y.Z`.

`v1` is a legacy whole-library tag from before per-tool versioning, kept for existing pins. See CHANGELOG.md.

## Loading

The repository is public, so no credential or extra configuration is needed. Pin the tag of the tool you call:

```groovy
library identifier: 'jenkinstools@deployStandardProteomicsService/v2.0.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])
```

## Service pipelines: two layers

| | `deployService` | `deployStandardProteomicsService` |
| --- | --- | --- |
| **Role** | The wiring. It understands every input and does the build, injection, reporting, testing, push, Tower and notification | The protchem conventions. It fills in defaults and calls `deployService` |
| **Defaults** | **None.** Everything it uses must be passed; optional features are off unless configured | Namespace, branches, Tower staging branch and tag variable, standard build args, NuGet secret, tests, OCP smoke, cockpit notification. All overridable. Tower job names are always given by hand |
| **Use for** | Services that don't follow the conventions, e.g. proteomicshelper.python | Standard protchem .NET services |

Both are used the same way: all configuration is defined as variables at the top of the Jenkinsfile, and the call only wires those variables in.

### `deployStandardProteomicsService`: a standard service

```groovy
library identifier: 'jenkinstools@deployStandardProteomicsService/v2.0.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])

// ############################## configuration ##############################
def my_image_name               = 'hive.core'
def my_dockerfile               = 'Hive.Core.WebAPI/Dockerfile'
def my_docker_context           = './'
def my_ansible_job_name_staging = 'App Protchem Hive Core Staging'
def my_ansible_job_name_prod    = 'App Protchem Hive Core Production'
// ###########################################################################

deployStandardProteomicsService(
  imageName             : my_image_name,
  dockerFile            : my_dockerfile,
  dockerContext         : my_docker_context,
  towerStagingJobName   : my_ansible_job_name_staging,
  towerProductionJobName: my_ansible_job_name_prod,
)
```

**Required inputs:**
- **`imageName`, `dockerFile`, `dockerContext`:** the context is always stated, typically `'./'`, rather than left to `buildDockerImage`'s default.
- **`towerStagingJobName`, `towerProductionJobName`:** IT names the Tower jobs, so they're mapped by hand and never derived. Neither is needed with `tower: false`.

**Optional `dockerHttpPort`:** the port the container listens on. It's passed as the build arg `APP_PORT`, the stack-neutral name from the OCP deployment standard. The Dockerfile maps it to its stack (`ASPNETCORE_HTTP_PORTS` for Kestrel, the bind address of a Python server, and so on) and uses it for `EXPOSE`. Leave it unset, or `null`, and the Dockerfile's own default applies.

| Convention | Default | Override |
| --- | --- | --- |
| `imageNamespace` | `protchem` | any value |
| `pushBranches` | `['master', 'develop']` | any list |
| `tower` | staging job on pushes to `master`; production job on tags with `app_generic_image_tag: <tag>` | A Map with `stagingBranch` and/or `imageTagVariable`. `false` for no Tower (build and push only) |
| `testResultsFolder` | `/app/tests/results` | any path. `tests: false` means no test step at all |
| `ocpSmoke` | on | `false` |
| `buildArgs` | `NUGET_REPO_USER=vbc-proteomics`, `MINVER_VERSION_OVERRIDE=<tag without v>`, `APP_USER=app`, `APP_GROUP_GID=0`, `TEST_RESULTS_FOLDER=<testResultsFolder>`, `SEQ_VERSION=2026.1.17044` | Merged over the defaults; any new names work, and `null` removes one (e.g. `[SEQ_VERSION: null]` for no Seq). `defaultBuildArgs: false` starts empty (`dockerHttpPort` still applies) |
| `secrets` | `SECRETS-NUGET-REPO-PW` from `vbc-proteomics-github-pat` | Merged by `id`; any new secrets work. `defaultSecrets: false` starts empty |
| `cockpitNotify` | `[credentialsId: 'vbc-cockpit-service-bus-send']` | Another Map, or `false` |

`testScript` and `ocpSmokeExtraChecks` pass through when tests are on. Anything else is passed straight to `deployService`, e.g. `towerJobs` and the actions.

### `deployService`: the wiring, no defaults

```groovy
deployService(
  imageName        : my_image_name,
  dockerFile       : my_dockerfile,
  dockerContext    : my_docker_context,
  imageNamespace   : my_image_namespace,
  pushBranches     : my_observed_git_branches,
  tower            : [staging: my_tower_staging_job, stagingBranch: my_staging_branch,
                      production: my_tower_production_job, imageTagVariable: my_tower_image_tag_variable],
  buildArgs        : my_docker_build_args,
  secrets          : my_docker_secrets,
  testResultsFolder: my_test_results_folder,
  ocpSmoke         : true,
  cockpitNotify    : my_cockpit_notify,
  beforeBuild      : my_before_build,
)
```

It runs these steps:
1. Validates the configuration. Missing or half-configured inputs fail with a list of every problem.
2. Injects the build args and BuildKit secrets.
3. Prints build info.
4. Notifies the cockpit that the build started, if configured.
5. Runs `beforeBuild`.
6. Calls IT's `buildDockerImage`. Its test step, if any, runs `testScript`, collects JUnit results from `testResultsFolder`, and runs the OCP smoke when `ocpSmoke: true`.
7. Pushes the image on `pushBranches`.
8. Runs the Tower staging job on `stagingBranch` and the production job on tags.
9. Runs `afterBuild` if the build succeeded, and `afterAlways` in every case.
10. Notifies the cockpit that the build finished, with the real result.

| Input | Required | Meaning |
| --- | --- | --- |
| `imageName`, `dockerFile`, `imageNamespace`, `pushBranches` | yes | Image `<imageNamespace>/<imageName>`, pushed on `pushBranches` (a list, which may be empty). `dockerContext` is optional |
| `buildArgs` | no | Map of any build args |
| `secrets` | no | BuildKit secrets `[[id: '…', credentialsId: '…', kind: 'usernamePassword' \| 'string'], …]`, never passed as build args or shown in logs |
| `tower` | no | `[staging, stagingBranch, production, imageTagVariable]`. Each job needs its companion value. `towerJobs` passes a raw `buildDockerImage` map instead |
| `testResultsFolder`, `testScript`, `ocpSmoke`, `ocpSmokeExtraChecks` | no | The test step runs only when one of these is set. `testScript` needs `testResultsFolder`, and the extra checks need `ocpSmoke: true` |
| `cockpitNotify` | no | `[credentialsId: …]` or `[connectionString: …]`. A pipeline secret, never passed to the image |
| `beforeBuild`, `afterBuild`, `afterAlways` | no | A Closure or a List of Closures |

All inputs are documented at the top of [`vars/deployService.groovy`](vars/deployService.groovy) and [`vars/deployStandardProteomicsService.groovy`](vars/deployStandardProteomicsService.groovy).

## VBC Deployment Cockpit build notifications

Outside `deployService`, the step takes a credential id or the connection string value of a Send-only policy on the cockpit's topic:

```groovy
node { vbcDeploymentCockpitNotify('started', [credentialsId: 'vbc-cockpit-service-bus-send']) }
try {
  buildDockerImage([ /* ... */ ])
} catch (e) {
  currentBuild.result = 'FAILURE'   // so 'finished' reports the real result
  throw e
} finally {
  node { vbcDeploymentCockpitNotify('finished', [credentialsId: 'vbc-cockpit-service-bus-send']) }
}
```

If the credential doesn't exist, it only prints a warning (unless `failOnError: true`). `vbcDeploymentCockpitNotify('started', '<connection string>')` also works.

## Service Bus: key vs connection string vs SAS token

These are three forms of the same credential:

| | Looks like | Is |
| --- | --- | --- |
| **Key** | `x8Kq…=` | The secret of one *shared access policy* (policy name + rights such as Send/Listen, on a namespace, queue or topic). |
| **Connection string** | `Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=jenkins-send;SharedAccessKey=x8Kq…=;EntityPath=jenkins-build-events` | The key packaged with the address: the endpoint, the policy name and, for queue/topic-level policies, the entity. It's exactly as secret as the key. |
| **SAS token** | `SharedAccessSignature sr=…&sig=…&se=…&skn=jenkins-send` | A time-limited signature *made from* the key. It's what REST calls actually send. `serviceBusNotify` mints one per call (valid 10 minutes) from a connection string. |

In the Azure portal, go to the topic, then **Shared access policies**, then **Add**. Tick only **Send** and copy the *Primary connection string*.
- **Never use `RootManageSharedAccessKey`.** That's namespace-wide admin.
- **Revoking:** regenerate the policy key. That invalidates the old connection string and every token made from it.

## Step reference

**`serviceBusNotify`**

| Argument | Required | Default | Meaning |
| --- | --- | --- | --- |
| `connectionString` | one of | | Connection string value; target and credentials come from it |
| `sasToken` | one of | | Ready SAS token; then also pass `namespace` + `entity` |
| `namespace` | no* | from connection string | Namespace name (`<ns>.servicebus.windows.net`) |
| `entity` | no* | `EntityPath` | Queue or topic name; the same send endpoint covers both |
| `message` | yes | | Map or List (sent as JSON), or a String (sent verbatim) |
| `properties` | no | | Custom message properties; subscriptions can filter on these |
| `timeToLive` | no | entity default | Message time-to-live in seconds |
| `tokenTtlSeconds` | no | `600` | Lifetime of the minted SAS token |
| `contentType`, `timeoutSeconds`, `failOnError` | no | | As for `webhookNotify` |

**`webhookNotify`**

| Argument | Required | Default | Meaning |
| --- | --- | --- | --- |
| `url` | yes | | Target URL |
| `message` | yes | | Map or List (sent as JSON), or a String (sent verbatim) |
| `secret` | no | | Auth header value, e.g. a token |
| `credentialsId` | no | | Alternatively, a Secret-text credential ID to read the secret from |
| `authHeader` | no | `Authorization` | Header that carries the secret |
| `authScheme` | no | none | Prefix, e.g. `Bearer`, giving `Authorization: Bearer <secret>` |
| `headers` | no | | Additional, non-secret headers |
| `contentType` | no | `application/json` | |
| `method` | no | `POST` | |
| `timeoutSeconds` | no | `10` | |
| `failOnError` | no | `false` | |

**`buildEventMessage(event, extra = [:])`**: `event` is `'started'` or `'finished'`. It returns this map, which `extra` can extend or override:

```json
{"schema":"jenkins-build-event/v1","event":"started","jobName":"Folder/repo/feature%2Fx","buildNumber":"42",
 "branch":"feature/x","tag":"","result":"","buildUrl":"https://jenkins/...","startedAtMillis":"1790600000000"}
```

**Sandbox:** dynamically loaded libraries run in the Groovy sandbox. That's why these steps avoid `groovy.json`, `javax.crypto`, `UUID` and date formatting, and do the crypto and HTTP in the shell.
