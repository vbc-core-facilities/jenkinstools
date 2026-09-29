/**
 * Version 1.0.1 - released as tag serviceBusNotify/v1.0.1 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * Sends an arbitrary message to an Azure Service Bus queue or topic via its REST API. Knows nothing
 * about the receiver. Thin wrapper over webhookNotify; must be called inside a node { } block.
 *
 *   serviceBusNotify(connectionString: SB_CONN, message: [hello: 'world'])
 *
 * Auth - one of:
 *   connectionString  "Endpoint=sb://<ns>.servicebus.windows.net/;SharedAccessKeyName=<policy>;
 *                     SharedAccessKey=<key>[;EntityPath=<queue or topic>]" - the value, not a credential id.
 *                     A short-lived SAS token is minted from it for each call (needs openssl on the agent).
 *                     Endpoint and EntityPath supply the target, so nothing else is needed.
 *   sasToken          a ready SAS token ("SharedAccessSignature sr=...&sig=...&se=...&skn=..."),
 *                     together with namespace + entity.
 *
 * Target (only needed when the connection string doesn't carry it, or with sasToken):
 *   namespace         Service Bus namespace name (the part before .servicebus.windows.net)
 *   entity            queue or topic name (both use the same send endpoint); overrides EntityPath
 *
 * Required:
 *   message           Map/List (sent as JSON) or String (sent verbatim)
 *
 * Optional:
 *   properties        Map of custom message properties (subscriptions can filter on these), string values
 *   timeToLive        message TTL in seconds (default: the entity's default)
 *   tokenTtlSeconds   lifetime of the SAS token minted from a connection string, default 600
 *   contentType, timeoutSeconds, failOnError   as for webhookNotify (failOnError defaults to false)
 *
 * Returns true when Service Bus accepted the message.
 */
def call(Map args = [:]) {
    boolean failOnError = args.failOnError == true

    Map sendTarget
    try {
        sendTarget = resolveSendTarget(args)
    } catch (InterruptedException aborted) {
        throw aborted // build aborted - never swallow
    } catch (Exception failure) {
        sendTarget = [problem: failure.message]
    }

    if (sendTarget.problem != null) {
        if (failOnError) {
            error("serviceBusNotify: ${sendTarget.problem}")
        }
        echo "WARNING: serviceBusNotify: ${sendTarget.problem} - continuing (failOnError is false)"
        return false
    }

    Map messagePropertyHeaders = toMessagePropertyHeaders(args.properties ?: [:])
    Map brokerPropertyHeaders = args.timeToLive
        ? [BrokerProperties: "{\"TimeToLive\":${args.timeToLive as int}}".toString()]
        : [:]
    Map requestHeaders = messagePropertyHeaders + brokerPropertyHeaders

    return webhookNotify(
        url           : sendTarget.url,
        message       : args.message,
        secret        : sendTarget.sasToken,
        headers       : requestHeaders,
        contentType   : args.contentType,
        timeoutSeconds: args.timeoutSeconds,
        failOnError   : failOnError,
    )
}

// Returns [url, sasToken] to send with, or [problem] describing why that isn't possible.
private Map resolveSendTarget(Map args) {
    if (args.connectionString) {
        return resolveSendTargetFromConnectionString(args)
    }
    if (args.sasToken && args.namespace && args.entity) {
        String url = "https://${args.namespace}.servicebus.windows.net/${args.entity}/messages"
        return [url: url, sasToken: args.sasToken]
    }
    return [problem: 'pass connectionString, or sasToken + namespace + entity']
}

private Map resolveSendTargetFromConnectionString(Map args) {
    Map connectionStringParts = parseConnectionString(args.connectionString as String)
    String keyName = connectionStringParts.SharedAccessKeyName
    String key = connectionStringParts.SharedAccessKey
    if (!keyName || !key) {
        return [problem: 'connectionString has no SharedAccessKeyName/SharedAccessKey']
    }

    String host = args.namespace
        ? "${args.namespace}.servicebus.windows.net"
        : endpointHost(connectionStringParts.Endpoint)
    String entity = args.entity ?: connectionStringParts.EntityPath
    if (!host || !entity) {
        return [problem: 'connectionString has no Endpoint/EntityPath - pass namespace/entity']
    }

    String resourceUri = "https://${host}/${entity}"
    int tokenTtlSeconds = (args.tokenTtlSeconds ?: 600) as int
    String sasToken = mintSasToken(resourceUri, keyName, key, tokenTtlSeconds)
    return [url: "${resourceUri}/messages".toString(), sasToken: sasToken]
}

// "Endpoint=sb://ns/;SharedAccessKeyName=p;SharedAccessKey=k" -> [Endpoint: 'sb://ns/', SharedAccessKeyName: 'p', ...]
private Map parseConnectionString(String connectionString) {
    List<String> parts = connectionString.split(';') as List
    List<String> keyValueParts = parts.findAll { String part -> part.indexOf('=') > 0 }
    return keyValueParts.collectEntries { String part ->
        int separatorIndex = part.indexOf('=')
        String partName = part.substring(0, separatorIndex).trim()
        String partValue = part.substring(separatorIndex + 1).trim()
        [(partName): partValue]
    }
}

// "sb://ns.servicebus.windows.net/" -> "ns.servicebus.windows.net"
private String endpointHost(String endpoint) {
    if (!endpoint) {
        return null
    }
    String hostAndPath = endpoint.startsWith('sb://') ? endpoint.substring('sb://'.length()) : endpoint
    int pathStart = hostAndPath.indexOf('/')
    return pathStart >= 0 ? hostAndPath.substring(0, pathStart) : hostAndPath
}

// Any non-standard header becomes a custom message property; string values must be JSON-quoted.
private Map toMessagePropertyHeaders(Map messageProperties) {
    return messageProperties.collectEntries { propertyName, propertyValue ->
        String escapedValue = (propertyValue as String)
            .replace('\\', '\\\\')
            .replace('"', '\\"')
        [(propertyName): '"' + escapedValue + '"']
    }
}

// SAS token = HMAC-SHA256 over "<url-encoded resource uri>\n<expiry>" with the policy key (used as
// text, not base64-decoded). Done in the shell with openssl because javax.crypto isn't available in
// the Groovy sandbox without script approval. The key is briefly visible in the agent's process list.
private String mintSasToken(String resourceUri, String keyName, String key, int tokenTtlSeconds) {
    List<String> signingEnvironment = [
        "SB_URI=${resourceUri}",
        "SB_KEY_NAME=${keyName}",
        "SB_KEY=${key}",
        "SB_TTL=${tokenTtlSeconds}",
    ]
    String mintScript = '''
        set +x
        command -v openssl >/dev/null 2>&1 || { echo "openssl is not installed on this agent" >&2; exit 3; }
        se=$(( $(date +%s) + SB_TTL ))
        sr=$(printf '%s' "$SB_URI" | sed -e 's/%/%25/g' -e 's/:/%3A/g' -e 's#/#%2F#g')
        sig=$(printf '%s\\n%s' "$sr" "$se" | openssl dgst -sha256 -hmac "$SB_KEY" -binary | base64 | tr -d '\\n' \
              | sed -e 's/+/%2B/g' -e 's#/#%2F#g' -e 's/=/%3D/g')
        printf 'SharedAccessSignature sr=%s&sig=%s&se=%s&skn=%s' "$sr" "$sig" "$se" "$SB_KEY_NAME"
    '''
    withEnv(signingEnvironment) {
        String mintedToken = sh(label: 'mint Service Bus SAS token', returnStdout: true, script: mintScript)
        return mintedToken.trim()
    }
}
