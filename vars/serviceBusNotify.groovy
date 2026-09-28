/**
 * Version 1.0.0 - released as tag serviceBusNotify/v1.0.0 (see CHANGELOG.md). Bump both with every change to this file.
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
    Map target = [:]
    String problem

    try {
        problem = resolveTarget(args, target)
    } catch (InterruptedException e) {
        throw e // build aborted - never swallow
    } catch (Exception e) {
        problem = e.message
    }

    if (problem != null) {
        if (failOnError) {
            error("serviceBusNotify: ${problem}")
        }
        echo "WARNING: serviceBusNotify: ${problem} - continuing (failOnError is false)"
        return false
    }

    Map headers = [:]
    // Any non-standard header becomes a custom message property; string values must be JSON-quoted.
    (args.properties ?: [:]).each { k, v -> headers[k] = '"' + (v as String).replace('\\', '\\\\').replace('"', '\\"') + '"' }
    if (args.timeToLive) {
        headers['BrokerProperties'] = "{\"TimeToLive\":${args.timeToLive as int}}"
    }

    return webhookNotify(
        url           : target.url,
        message       : args.message,
        secret        : target.token,
        headers       : headers,
        contentType   : args.contentType,
        timeoutSeconds: args.timeoutSeconds,
        failOnError   : failOnError,
    )
}

// Fills target.url / target.token; returns null on success, otherwise what's wrong.
private String resolveTarget(Map args, Map target) {
    String host = args.namespace ? "${args.namespace}.servicebus.windows.net" : null
    String entity = args.entity
    String token = args.sasToken

    if (args.connectionString) {
        Map cs = parseConnectionString(args.connectionString as String)
        if (!cs.SharedAccessKeyName || !cs.SharedAccessKey) {
            return 'connectionString has no SharedAccessKeyName/SharedAccessKey'
        }
        host = host ?: endpointHost(cs.Endpoint)
        entity = entity ?: cs.EntityPath
        if (!host || !entity) {
            return 'connectionString has no Endpoint/EntityPath - pass namespace/entity'
        }
        int ttl = (args.tokenTtlSeconds ?: 600) as int
        token = mintSasToken("https://${host}/${entity}", cs.SharedAccessKeyName, cs.SharedAccessKey, ttl)
    }

    if (!host || !entity || !token) {
        return 'pass connectionString, or sasToken + namespace + entity'
    }
    target.url = "https://${host}/${entity}/messages"
    target.token = token
    return null
}

private Map parseConnectionString(String s) {
    Map values = [:]
    for (String part : s.split(';')) {
        int eq = part.indexOf('=')
        if (eq > 0) {
            values[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
    }
    return values
}

// "sb://ns.servicebus.windows.net/" -> "ns.servicebus.windows.net"
private String endpointHost(String endpoint) {
    if (!endpoint) return null
    String host = endpoint.startsWith('sb://') ? endpoint.substring(5) : endpoint
    int slash = host.indexOf('/')
    return slash >= 0 ? host.substring(0, slash) : host
}

// SAS token = HMAC-SHA256 over "<url-encoded resource uri>\n<expiry>" with the policy key (used as
// text, not base64-decoded). Done in the shell with openssl because javax.crypto isn't available in
// the Groovy sandbox without script approval. The key is briefly visible in the agent's process list.
private String mintSasToken(String resourceUri, String keyName, String key, int ttlSeconds) {
    withEnv(["SB_URI=${resourceUri}", "SB_KEY_NAME=${keyName}", "SB_KEY=${key}", "SB_TTL=${ttlSeconds}"]) {
        return sh(label: 'mint Service Bus SAS token', returnStdout: true, script: '''
            set +x
            command -v openssl >/dev/null 2>&1 || { echo "openssl is not installed on this agent" >&2; exit 3; }
            se=$(( $(date +%s) + SB_TTL ))
            sr=$(printf '%s' "$SB_URI" | sed -e 's/%/%25/g' -e 's/:/%3A/g' -e 's#/#%2F#g')
            sig=$(printf '%s\\n%s' "$sr" "$se" | openssl dgst -sha256 -hmac "$SB_KEY" -binary | base64 | tr -d '\\n' \
                  | sed -e 's/+/%2B/g' -e 's#/#%2F#g' -e 's/=/%3D/g')
            printf 'SharedAccessSignature sr=%s&sig=%s&se=%s&skn=%s' "$sr" "$sig" "$se" "$SB_KEY_NAME"
        ''').trim()
    }
}
