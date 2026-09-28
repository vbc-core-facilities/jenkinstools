/**
 * Tells the VBC Deployment Cockpit that this build started or finished, so it shows the build as
 * in flight on the matching deployment. Specialised wrapper: sends buildEventMessage(event) to the
 * cockpit's fixed Service Bus topic via serviceBusNotify. Must be called inside a node { } block.
 *
 *   vbc_deployment_cockpit_notify('started', SB_CONN)
 *   vbc_deployment_cockpit_notify('finished', SB_CONN)
 *   vbc_deployment_cockpit_notify('finished', SB_CONN, [failOnError: true])
 *
 * connectionString: the Service Bus connection string *value* of a Send-only policy on the topic
 *                   (Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=...;EntityPath=...).
 * options:          passed through to serviceBusNotify (failOnError, timeoutSeconds, ...).
 *                   By default a failed notification only warns and the build carries on.
 */
def call(String event, String connectionString, Map options = [:]) {
    // The topic every cockpit instance subscribes to (each with its own subscription).
    final String topic = 'jenkins-build-events'

    if (!(event in ['started', 'finished'])) {
        String problem = "vbc_deployment_cockpit_notify: event must be 'started' or 'finished', got '${event}'"
        if (options.failOnError == true) error(problem)
        echo "WARNING: ${problem} - continuing (failOnError is false)"
        return false
    }

    return serviceBusNotify([
        connectionString: connectionString,
        entity          : topic,
        message         : buildEventMessage(event),
        timeToLive      : 86400, // a build event older than a day is useless to the cockpit
    ] + options)
}
