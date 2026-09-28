# jenkinstools

Small, reusable [Jenkins shared library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/) steps. Every `vars/<name>.groovy` file is a pipeline step called `<name>`.

| Step | What it does |
| --- | --- |
| `webhookNotify` | Sends any message to any HTTP(S) URL. An optional secret goes into an auth header. |
| `serviceBusNotify` | Sends any message to an Azure Service Bus queue or topic, given a connection string or a SAS token. |
| `buildEventMessage` | Builds a generic `jenkins-build-event/v1` message describing the current build. |
| `vbcDeploymentCockpitNotify` | Sends a build event to the VBC Deployment Cockpit (fixed topic), so the cockpit shows the build as running. |
| `deployService` | The whole protchem service pipeline, with the Jenkinsfile reduced to its configuration. |

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
- **A Jenkinsfile pins the tag of the tool it calls:** `library identifier: 'jenkinstools@deployService/v1.0.0', …`.
  - **How loading works:** Jenkins loads the library at that single git ref, so the tools the pinned tool uses come from the same commit. That snapshot was released and tested together.
  - **Calling several tools directly** means you pin one of their tags; the others are whatever they were at that commit.
  - **Never pin `main`** in a real pipeline. It's only for trying a change on one throwaway branch.
- **Roll out gradually:** release a new tag of a tool, then bump the pin one Jenkinsfile at a time. Every other pipeline stays locked to the release it already has. To roll back, revert the pin.
- **Every release updates, in the tagged commit:**
  - the version in the tool's header, plus its `toolVersion()` where it has one (`deployService` prints it in the build log)
  - the tool's section in `CHANGELOG.md`
- **Releasing a tool:** commit, then `git tag -a <tool>/vX.Y.Z -m "<tool> vX.Y.Z" && git push origin main <tool>/vX.Y.Z`.

`v1` is a legacy whole-library tag from before per-tool versioning, kept for existing pins. See CHANGELOG.md.

## Loading

The repository is public, so no credential or extra configuration is needed. Pin the tag of the tool you call:

```groovy
library identifier: 'jenkinstools@deployService/v1.0.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])
```

## A service Jenkinsfile with `deployService`

The whole Jenkinsfile. All configuration is defined as variables at the top, and the `deployService` call only wires those variables in. Every repo-specific fact comes from the Jenkinsfile: port, test folder, Seq version (or no Seq at all), users, branches and namespace.

```groovy
library identifier: 'jenkinstools@deployService/v1.0.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])

// ############################## configuration ##############################
def my_image_name               = 'hive.frontend'
def my_dockerfile               = 'Hive.Frontend/Dockerfile'
def my_image_namespace          = 'protchem'
def my_observed_git_branches    = ['master', 'develop']
def my_staging_branch           = 'master'
def my_ansible_job_name_staging = 'App Protchem Hive Frontend Staging'
def my_ansible_job_name_prod    = 'App Protchem Hive Frontend Production'
def test_results_folder_inside_container = '/app/tests/results'

def my_docker_build_args = [
  'ASPNET_PORT': '8080',
  'APP_USER': 'app',
  'APP_GROUP_GID': '0',
  'TEST_RESULTS_FOLDER': test_results_folder_inside_container,
  'SEQ_VERSION': '2026.1.17044',   // leave out for an image without Seq
]
// ###########################################################################

deployService(
  imageName        : my_image_name,
  dockerFile       : my_dockerfile,
  imageNamespace   : my_image_namespace,
  pushBranches     : my_observed_git_branches,
  tower            : [staging: my_ansible_job_name_staging, production: my_ansible_job_name_prod,
                      stagingBranch: my_staging_branch],
  buildArgs        : my_docker_build_args,
  testResultsFolder: test_results_folder_inside_container,
)
```

`deployService` runs these steps:
1. Assembles the build args and BuildKit secrets.
2. Prints build info.
3. Notifies the cockpit that the build started.
4. Calls IT's `buildDockerImage`, whose test step collects in-image JUnit results (optionally running `testScript` first) and runs the OCP arbitrary-UID probe smoke.
5. Pushes the image on `pushBranches`.
6. Runs the Tower staging job on `stagingBranch` and the production job on tags.
7. Notifies the cockpit that the build finished, with the real result.

All options are documented at the top of [`vars/deployService.groovy`](vars/deployService.groovy).

| Option | Default | Meaning |
| --- | --- | --- |
| `imageName`, `dockerFile`, `imageNamespace`, `pushBranches` | required | Image `<imageNamespace>/<imageName>` built from `dockerFile`, pushed on `pushBranches`. `dockerContext` is optional |
| `tower` | none (build and push only) | `[staging: '<job>', production: '<job>', stagingBranch: '<branch>', imageTagVariable: 'app_generic_image_tag']`. `stagingBranch` is required with `staging` |
| `buildArgs` | | The image's build args, any names. They add to or override the defaults; a `null` value removes a default |
| `defaultBuildArgs` | `NUGET_REPO_USER=vbc-proteomics`, `MINVER_VERSION_OVERRIDE=<tag without v>` | The only built-in build args. `false` uses neither |
| `secrets` | | Extra BuildKit secrets: `[[id: '…', credentialsId: '…', kind: 'usernamePassword' \| 'string'], …]`. The same `id` replaces a default |
| `defaultSecrets` | `SECRETS-NUGET-REPO-PW` from `vbc-proteomics-github-pat` | `false` for images without the private NuGet feed |
| `testResultsFolder` | required unless `tests: false` | Where the image keeps its JUnit XML |
| `tests` / `testScript` / `ocpSmoke` / `ocpSmokeExtraChecks` | on / none / on / none | Test and smoke behaviour. `tests: false` skips both |
| `cockpitNotify` | `[credentialsId: 'vbc-cockpit-service-bus-send']` | A *pipeline* secret, never passed to the image. Override with `[credentialsId: …]` or `[connectionString: …]`; `false` disables it. A missing credential only warns |
| `beforeBuild` / `afterBuild` | | Closures for repo-specific extras |

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
