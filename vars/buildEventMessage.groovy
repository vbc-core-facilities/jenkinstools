/**
 * Version 1.0.0 - released as tag buildEventMessage/v1.0.0 (see CHANGELOG.md). Bump both with every change to this file.
 *
 * Builds a generic "a Jenkins build started/finished" message (schema jenkins-build-event/v1) from
 * the current build, for use as the message of webhookNotify / serviceBusNotify. Receiver-agnostic:
 * it only describes the build.
 *
 *   buildEventMessage('started')
 *   buildEventMessage('finished')                  // result = currentBuild.currentResult
 *   buildEventMessage('finished', [result: 'FAILURE', extra: 'anything'])   // overrides / additions
 *
 * Fields: schema, event, jobName (JOB_NAME, branch names still %2F-encoded), buildNumber, branch
 * (BRANCH_NAME), tag (TAG_NAME or ''), result ('' for started), buildUrl, startedAtMillis.
 * All values are strings.
 */
def call(String event, Map extra = [:]) {
    if (!(event in ['started', 'finished'])) {
        error("buildEventMessage: event must be 'started' or 'finished', got '${event}'")
    }
    return [
        schema         : 'jenkins-build-event/v1',
        event          : event,
        jobName        : env.JOB_NAME ?: '',
        buildNumber    : env.BUILD_NUMBER ?: '',
        branch         : env.BRANCH_NAME ?: '',
        tag            : env.TAG_NAME ?: '',
        result         : event == 'finished' ? currentBuild.currentResult : '',
        buildUrl       : env.BUILD_URL ?: '',
        // epoch millis: formatting dates needs script approval in the sandbox
        startedAtMillis: "${currentBuild.startTimeInMillis}",
    ] + extra
}
