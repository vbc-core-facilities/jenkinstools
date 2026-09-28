# jenkinstools

Small, reusable [Jenkins shared library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/) steps. Every `vars/<name>.groovy` file is a pipeline step called `<name>`.

| Step | What it does |
| --- | --- |
| `webhookNotify` | Sends any message to any HTTP(S) URL. An optional secret goes into an auth header. |
| `serviceBusNotify` | Sends any message to an Azure Service Bus queue or topic, given a connection string or a SAS token. |
| `buildEventMessage` | Builds a generic `jenkins-build-event/v1` message describing the current build. |
| `vbc_deployment_cockpit_notify` | Sends a build event to the VBC Deployment Cockpit (fixed topic), so the cockpit shows the build as running. |

**Failures don't break the build by default.** Any problem only prints a warning and the build carries on. `failOnError: true` fails the build instead. Aborting a build is never swallowed.

**Requirements:** the steps run inside `node {}` and use `sh` and `curl` (7.55 or newer). `serviceBusNotify` with a connection string also needs `openssl` on the agent.

## Loading

The repository is public, so no credential or extra configuration is needed. Pin it to a tag or commit:

```groovy
library identifier: 'jenkinstools@v1',
        retriever: modernSCM([$class: 'GitSCMSource', remote: 'https://github.com/<org>/jenkinstools.git'])
```

## VBC Deployment Cockpit build notifications

The only input is the connection string of a Send-only policy on the cockpit's topic:

```groovy
withCredentials([string(credentialsId: '<id>', variable: 'COCKPIT_SB')]) {   // or however you get the value
  node { vbc_deployment_cockpit_notify('started', COCKPIT_SB) }
  try {
    buildDockerImage([ /* ... existing pipeline, unchanged ... */ ])
  } catch (e) {
    currentBuild.result = 'FAILURE'   // so 'finished' reports the real result
    throw e
  } finally {
    node { vbc_deployment_cockpit_notify('finished', COCKPIT_SB) }
  }
}
```

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
