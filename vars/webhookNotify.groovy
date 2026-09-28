/**
 * Version 1.0.0 - released as tag webhookNotify/v1.0.0 (see CHANGELOG.md). Bump both with every change to this file.
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
    String problem = null

    try {
        problem = send(args)
    } catch (InterruptedException e) {
        throw e // build aborted - never swallow
    } catch (Exception e) {
        problem = e.message
    }

    String what = args.url ? "${args.method ?: 'POST'} ${args.url}" : 'webhookNotify'
    if (problem == null) {
        echo "${what}: sent"
        return true
    }
    if (failOnError) {
        error("${what}: ${problem}")
    }
    echo "WARNING: ${what}: ${problem} - continuing (failOnError is false)"
    return false
}

// Returns null on success, otherwise a description of what went wrong.
private String send(Map args) {
    def missing = ['url', 'message'].findAll { args[it] == null || args[it] == '' }
    if (missing) {
        return "missing required argument(s): ${missing.join(', ')}"
    }

    String body = args.message instanceof CharSequence ? args.message.toString() : toJson(args.message)
    String method = (args.method ?: 'POST') as String
    int timeoutSeconds = (args.timeoutSeconds ?: 10) as int

    // Non-secret headers, one "Name: value" per line; the shell adds the auth header.
    List<String> headerLines = ["Content-Type: ${args.contentType ?: 'application/json'}"]
    (args.headers ?: [:]).each { k, v -> headerLines << "${k}: ${v}" }

    // Body and headers travel as env vars and the shell writes them to mktemp files, so there are
    // no workspace files to name uniquely or clean up (messages are small).
    List<String> envVars = [
        "NOTIFY_URL=${args.url}", "NOTIFY_METHOD=${method}", "NOTIFY_TIMEOUT=${timeoutSeconds}",
        "NOTIFY_BODY=${body}", "NOTIFY_HEADER_LINES=${headerLines.join('\n')}",
        "NOTIFY_AUTH_HEADER=${args.authHeader ?: 'Authorization'}",
        "NOTIFY_AUTH_SCHEME=${args.authScheme ? args.authScheme + ' ' : ''}",
    ]

    // Single-quoted on purpose: the shell expands the secret, Groovy never interpolates it.
    String script = '''
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

    int status = 0
    withEnv(envVars) {
        if (args.credentialsId) {
            withCredentials([string(credentialsId: args.credentialsId, variable: 'NOTIFY_SECRET')]) {
                status = sh(label: "notify ${args.url}", returnStatus: true, script: script)
            }
        } else if (args.secret) {
            withEnv(["NOTIFY_SECRET=${args.secret}"]) {
                status = sh(label: "notify ${args.url}", returnStatus: true, script: script)
            }
        } else {
            status = sh(label: "notify ${args.url}", returnStatus: true, script: script)
        }
    }
    return status == 0 ? null : "failed (curl exit code ${status}; 22 = HTTP error status, 28 = timeout)"
}

// Small JSON encoder (Map/List/String/Number/Boolean/null); groovy.json may need script approval in the sandbox.
private String toJson(Object value) {
    if (value == null) return 'null'
    if (value instanceof Map) {
        return '{' + value.collect { k, v -> quote(k as String) + ':' + toJson(v) }.join(',') + '}'
    }
    if (value instanceof Collection || value instanceof Object[]) {
        return '[' + value.collect { toJson(it) }.join(',') + ']'
    }
    if (value instanceof Number || value instanceof Boolean) return value.toString()
    return quote(value.toString())
}

private String quote(String s) {
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t') + '"'
}
