/**
 * Version 1.1.1 - released as tag vbcDeploymentCockpitNotify/v1.1.1 (see CHANGELOG.md). Bump both with every change to this file.
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
    Map optionsWithConnectionString = options + [connectionString: connectionString]
    return send(event, optionsWithConnectionString)
}

def call(String event, Map options) {
    return send(event, options)
}

private boolean send(String event, Map options) {
    boolean failOnError = options.failOnError == true

    String problem = findConfigurationProblem(event, options)
    if (problem == null) {
        try {
            // serviceBusNotify warns (or fails the build) on its own problems.
            return sendBuildEvent(event, options)
        } catch (InterruptedException aborted) {
            throw aborted // build aborted - never swallow
        } catch (Exception failure) {
            if (failOnError) {
                throw failure // serviceBusNotify's own error(), or a missing credential
            }
            problem = failure.message // e.g. the credential doesn't exist
        }
    }

    if (failOnError) {
        error("vbcDeploymentCockpitNotify: ${problem}")
    }
    echo "WARNING: vbcDeploymentCockpitNotify: ${problem} - continuing (failOnError is false)"
    return false
}

private String findConfigurationProblem(String event, Map options) {
    List<String> knownEvents = ['started', 'finished']
    if (!(event in knownEvents)) {
        return "event must be 'started' or 'finished', got '${event}'"
    }
    if (!options.credentialsId && !options.connectionString) {
        return 'pass credentialsId or connectionString'
    }
    return null
}

private boolean sendBuildEvent(String event, Map options) {
    List<String> secretOptionNames = ['credentialsId', 'connectionString']
    Map serviceBusOptions = options.findAll { optionName, optionValue -> !(optionName in secretOptionNames) }
    Map buildEvent = buildEventMessage(event)

    if (options.credentialsId) {
        boolean sent = false
        withCredentials([string(credentialsId: options.credentialsId as String, variable: 'COCKPIT_SB_CONNECTION')]) {
            sent = sendToCockpitTopic("${COCKPIT_SB_CONNECTION}", buildEvent, serviceBusOptions)
        }
        return sent
    }
    return sendToCockpitTopic(options.connectionString as String, buildEvent, serviceBusOptions)
}

private boolean sendToCockpitTopic(String connectionString, Map buildEvent, Map serviceBusOptions) {
    // The topic every cockpit instance subscribes to (each with its own subscription).
    String cockpitTopic = 'jenkins-build-events'
    // A build event older than a day is useless to the cockpit.
    int buildEventTimeToLiveSeconds = 86400

    Map serviceBusArguments = [
        entity          : cockpitTopic,
        message         : buildEvent,
        timeToLive      : buildEventTimeToLiveSeconds,
        connectionString: connectionString,
    ] + serviceBusOptions
    return serviceBusNotify(serviceBusArguments)
}
