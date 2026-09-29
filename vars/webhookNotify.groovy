/**
 * Version 1.0.1 - released as tag webhookNotify/v1.0.1 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * Sends an arbitrary message to an arbitrary HTTP(S) endpoint. Knows nothing about the receiver.
 *
 * Must be called inside a node { } block (uses sh + curl >= 7.55 on the agent).
 *
 * Required arguments:
 *   url             target URL
 *   message         Map/List (sent as JSON) or String (sent verbatim)
 *
 * Optional arguments:
 *   credentialsId   Jenkins "Secret text" credential; its value is sent in the auth header (preferred)
 *   secret          the auth header value given directly, for when no Jenkins credential is available.
 *                   It is then in your Jenkinsfile and git history, and Jenkins doesn't mask it in
 *                   logs - only use a narrowly scoped, short-lived value. Ignored if credentialsId is set.
 *   authHeader      header that carries the secret, default 'Authorization'
 *   authScheme      prefix for the secret, e.g. 'Bearer' -> "Authorization: Bearer <secret>"; default none
 *   headers         Map of additional, non-secret headers
 *   contentType     default 'application/json'
 *   method          default 'POST'
 *   timeoutSeconds  default 10
 *   failOnError     default false: any problem (bad arguments, network, HTTP error) only prints a
 *                   warning and the build carries on. true: the step fails the build.
 *
 * Returns true when the endpoint answered with a 2xx status, false otherwise (when not failing).
 */
def call(Map args = [:]) {
    boolean failOnError = args.failOnError == true

    String problem
    try {
        problem = send(args)
    } catch (InterruptedException aborted) {
        throw aborted // build aborted - never swallow
    } catch (Exception failure) {
        problem = failure.message
    }

    String method = args.method ?: 'POST'
    String requestDescription = args.url ? "${method} ${args.url}" : 'webhookNotify'

    if (problem == null) {
        echo "${requestDescription}: sent"
        return true
    }
    if (failOnError) {
        error("${requestDescription}: ${problem}")
    }
    echo "WARNING: ${requestDescription}: ${problem} - continuing (failOnError is false)"
    return false
}

// Returns null on success, otherwise a description of what went wrong.
private String send(Map args) {
    List<String> requiredArgumentNames = ['url', 'message']
    List<String> missingArgumentNames = requiredArgumentNames.findAll { String argumentName ->
        args[argumentName] == null || args[argumentName] == ''
    }
    if (!missingArgumentNames.isEmpty()) {
        return "missing required argument(s): ${missingArgumentNames.join(', ')}"
    }

    String body = args.message instanceof CharSequence ? args.message.toString() : toJson(args.message)
    String method = args.method ?: 'POST'
    int timeoutSeconds = (args.timeoutSeconds ?: 10) as int
    String contentType = args.contentType ?: 'application/json'
    String authHeaderName = args.authHeader ?: 'Authorization'
    String authSchemePrefix = args.authScheme ? "${args.authScheme} " : ''

    // Non-secret headers, one "Name: value" per line; the shell appends the auth header.
    Map extraHeaders = args.headers ?: [:]
    List<String> extraHeaderLines = extraHeaders.collect { headerName, headerValue ->
        "${headerName}: ${headerValue}".toString()
    }
    List<String> headerLines = ["Content-Type: ${contentType}".toString()] + extraHeaderLines
    String headerLinesText = headerLines.join('\n')

    // Body and headers travel as env vars and the shell writes them to mktemp files, so there are
    // no workspace files to name uniquely or clean up (messages are small).
    List<String> requestEnvironment = [
        "NOTIFY_URL=${args.url}",
        "NOTIFY_METHOD=${method}",
        "NOTIFY_TIMEOUT=${timeoutSeconds}",
        "NOTIFY_BODY=${body}",
        "NOTIFY_HEADER_LINES=${headerLinesText}",
        "NOTIFY_AUTH_HEADER=${authHeaderName}",
        "NOTIFY_AUTH_SCHEME=${authSchemePrefix}",
    ]

    // Single-quoted on purpose: the shell expands the secret, Groovy never interpolates it.
    String curlScript = '''
        set +x
        hdrs=$(mktemp) || exit 1
        trap 'rm -f "$hdrs"' EXIT
        printf '%s\\n' "$NOTIFY_HEADER_LINES" > "$hdrs"
        if [ -n "${NOTIFY_SECRET+set}" ]; then
          printf '%s: %s%s\\n' "$NOTIFY_AUTH_HEADER" "$NOTIFY_AUTH_SCHEME" "$NOTIFY_SECRET" >> "$hdrs"
        fi
        printf '%s' "$NOTIFY_BODY" | curl --silent --show-error --fail --max-time "$NOTIFY_TIMEOUT" -o /dev/null \
          -X "$NOTIFY_METHOD" "$NOTIFY_URL" -H @"$hdrs" --data-binary @-
    '''
    String stepLabel = "notify ${args.url}"

    int curlExitCode = 0
    withEnv(requestEnvironment) {
        if (args.credentialsId) {
            withCredentials([string(credentialsId: args.credentialsId, variable: 'NOTIFY_SECRET')]) {
                curlExitCode = sh(label: stepLabel, returnStatus: true, script: curlScript)
            }
        } else if (args.secret) {
            withEnv(["NOTIFY_SECRET=${args.secret}"]) {
                curlExitCode = sh(label: stepLabel, returnStatus: true, script: curlScript)
            }
        } else {
            curlExitCode = sh(label: stepLabel, returnStatus: true, script: curlScript)
        }
    }

    if (curlExitCode == 0) {
        return null
    }
    return "failed (curl exit code ${curlExitCode}; 22 = HTTP error status, 28 = timeout)"
}

// Small JSON encoder (Map/List/String/Number/Boolean/null); groovy.json may need script approval in the sandbox.
private String toJson(Object value) {
    if (value == null) {
        return 'null'
    }
    if (value instanceof Map) {
        List<String> encodedMembers = value.collect { memberName, memberValue ->
            quote(memberName as String) + ':' + toJson(memberValue)
        }
        return '{' + encodedMembers.join(',') + '}'
    }
    if (value instanceof Collection || value instanceof Object[]) {
        List<String> encodedElements = value.collect { element -> toJson(element) }
        return '[' + encodedElements.join(',') + ']'
    }
    if (value instanceof Number || value instanceof Boolean) {
        return value.toString()
    }
    return quote(value.toString())
}

private String quote(String text) {
    String escapedText = text
        .replace('\\', '\\\\')
        .replace('"', '\\"')
        .replace('\n', '\\n')
        .replace('\r', '\\r')
        .replace('\t', '\\t')
    return '"' + escapedText + '"'
}
