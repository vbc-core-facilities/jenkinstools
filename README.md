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

**Every tool in this library is versioned. There are no unversioned consumers.**

- **Releases are immutable git tags `vMAJOR.MINOR.PATCH`**, e.g. `v1.1.0`. A tag is never moved or deleted once pushed. A fix is a new tag.
- **Semver applies to every step's arguments and behaviour:**
  - **MAJOR:** anything that could break an existing Jenkinsfile, such as a removed or renamed argument, a changed default, or a changed message schema.
  - **MINOR:** new steps or new optional arguments.
  - **PATCH:** fixes that don't change the interface.
- **Jenkinsfiles pin an exact release:** `library identifier: 'jenkinstools@v1.1.0', …`. Never pin `main` in a real pipeline; `main` is for testing a change on one throwaway branch.
- **Roll out gradually** by bumping the pin one Jenkinsfile at a time. Every other pipeline stays locked to the version it already has until you bump it too. To roll back, revert the pin.
- **Every release updates `resources/jenkinstools/VERSION` and `CHANGELOG.md`** in the tagged commit. `deployService` prints the version at the start of each build, so the log shows which release a job ran.
- **Releasing:** commit, update `VERSION` and `CHANGELOG.md`, then `git tag -a vX.Y.Z -m vX.Y.Z && git push origin main vX.Y.Z`.

`v1` is the first release; it predates the three-part scheme and is equivalent to `v1.0.0`.

## Loading

The repository is public, so no credential or extra configuration is needed:

```groovy
library identifier: 'jenkinstools@v1.1.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])
```

## A service Jenkinsfile with `deployService`

The whole Jenkinsfile:

```groovy
library identifier: 'jenkinstools@v1.1.0',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/vbc-core-facilities/jenkinstools.git'])

deployService(
  imageName    : 'hive.frontend',
  dockerFile   : 'Hive.Frontend/Dockerfile',
  tower        : [staging   : 'App Protchem Hive Frontend Staging',
                  production: 'App Protchem Hive Frontend Production'],
  cockpitNotify: [credentialsId: 'vbc-cockpit-service-bus-send'],
)
```

`deployService` runs these steps:
1. Assembles the default build args plus `buildArgs`, and passes the NuGet PAT as a BuildKit secret.
2. Prints build info.
3. Notifies the cockpit that the build started.
4. Calls IT's `buildDockerImage`, whose test step collects in-image JUnit results (optionally running `testScript` first) and runs the OCP arbitrary-UID probe smoke.
5. Pushes the image on `pushBranches`.
6. Runs the Tower staging job on `master` and the production job on tags.
7. Notifies the cockpit that the build finished, with the real result.

All options are documented at the top of [`vars/deployService.groovy`](vars/deployService.groovy). The common ones:

| Option | Default | Meaning |
| --- | --- | --- |
| `imageName`, `dockerFile` | required | Image `protchem/<imageName>` built from `dockerFile` |
| `tower` | none | `[staging: '<job>', production: '<job>', stagingBranch: 'master', imageTagVariable: 'app_generic_image_tag']` |
| `pushBranches` | `['master', 'develop']` | Branches whose images are pushed |
| `buildArgs` | | Extra or overriding build args. `defaultBuildArgs: false` starts from an empty set |
| `nugetSecret` | `vbc-proteomics-github-pat` → `SECRETS-NUGET-REPO-PW` | `false` for images without the private NuGet feed |
| `tests` / `testScript` / `ocpSmoke` / `ocpSmokeExtraChecks` | on / none / on / none | Test and smoke behaviour. `tests: false` skips both |
| `cockpitNotify` | skipped | `[credentialsId: '…']` or `[connectionString: '…']` |
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
