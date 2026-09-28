/**
 * Version 1.1.0 - released as tag vbcDeploymentCockpitNotify/v1.1.0 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * Tells the VBC Deployment Cockpit that this build started or finished, so it shows the build as
 * in flight on the matching deployment. Specialised wrapper: sends buildEventMessage(event) to the
 * cockpit's fixed Service Bus topic via serviceBusNotify. Must be called inside a node { } block.
 *
 *   vbcDeploymentCockpitNotify('started', [credentialsId: 'vbc-cockpit-service-bus-send'])
 *   vbcDeploymentCockpitNotify('started', SB_CONN)            // connection string value
 *   vbcDeploymentCockpitNotify('finished', SB_CONN, [failOnError: true])
 *
 * The secret is a Service Bus connection string of a Send-only policy on the topic
 * (Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=...;EntityPath=...), given either as
 *   credentialsId     id of a Jenkins "Secret text" credential holding it (preferred), or
 *   connectionString  the value itself.
 * Other options are passed through to serviceBusNotify (failOnError, timeoutSeconds, ...).
 * By default any failure - including a credential that doesn't exist yet - only warns.
 */
def call(String event, String connectionString, Map options = [:]) {
    return send(event, options + [connectionString: connectionString])
}

def call(String event, Map options) {
    return send(event, options)
}

private boolean send(String event, Map options) {
    // The topic every cockpit instance subscribes to (each with its own subscription).
    final String topic = 'jenkins-build-events'
    boolean failOnError = options.failOnError == true

    String problem = null
    if (!(event in ['started', 'finished'])) {
        problem = "event must be 'started' or 'finished', got '${event}'"
    } else if (!options.credentialsId && !options.connectionString) {
        problem = 'pass credentialsId or connectionString'
    }

    if (problem == null) {
        Map args = [
            entity    : topic,
            message   : buildEventMessage(event),
            timeToLive: 86400, // a build event older than a day is useless to the cockpit
        ] + options.findAll { k, v -> !(k in ['credentialsId', 'connectionString']) }
        boolean sent = false
        try {
            if (options.credentialsId) {
                withCredentials([string(credentialsId: options.credentialsId as String, variable: 'COCKPIT_SB_CONNECTION')]) {
                    sent = serviceBusNotify(args + [connectionString: "${COCKPIT_SB_CONNECTION}"])
                }
            } else {
                sent = serviceBusNotify(args + [connectionString: options.connectionString as String])
            }
            // serviceBusNotify already warned (or failed the build) on its own problems.
            return sent
        } catch (InterruptedException e) {
            throw e // build aborted - never swallow
        } catch (Exception e) {
            if (failOnError) throw e // serviceBusNotify's own error(), or a missing credential
            problem = e.message      // e.g. the credential doesn't exist
        }
    }

    if (failOnError) {
        error("vbcDeploymentCockpitNotify: ${problem}")
    }
    echo "WARNING: vbcDeploymentCockpitNotify: ${problem} - continuing (failOnError is false)"
    return false
}
