#!/bin/bash
# Drives a running Jenkins test server from the command line (Git Bash, Linux, macOS). Plain script on purpose: usable by
# people, CI and any AI assistant alike; the Claude skill in .claude/skills/jenkins-testserver only wraps it.
#
# Usage: testserver.sh <command> [args]
#   up                         start (or rebuild) the server: docker compose up -d --build in TESTSERVER_COMPOSE_DIR
#   down                       stop it (volumes, i.e. Jenkins home, built images and the registry, are kept)
#   status                     server reachable? jobs and their branches
#   scan <job>                 re-scan a repo's branches and tags now (otherwise every 2 minutes)
#   build <job> <branch|tag> [NAME=value ...]
#                              start a build (with parameters, if given or if the job has any), wait for it, print
#                              result, test summary and the key log lines
#   log <job> <branch> [n]     full console log of build n (default: last)
#   tests <job> <branch> [n]   test summary of build n, with every failing test and its message
#
# Environment:
#   TESTSERVER_URL          default http://127.0.0.1:18090
#   TESTSERVER_COMPOSE_DIR  folder with the compose.yaml to start (up/down); default: this script's folder
#   TESTSERVER_WAIT_SECONDS how long build waits, default 3600

set -u

URL="${TESTSERVER_URL:-http://127.0.0.1:18090}"
URL="${URL%/}"
COMPOSE_DIR="${TESTSERVER_COMPOSE_DIR:-$(cd "$(dirname "$0")" && pwd)}"
WAIT_SECONDS="${TESTSERVER_WAIT_SECONDS:-3600}"
COOKIES="$(mktemp)"
trap 'rm -f "${COOKIES}"' EXIT

die() { echo "testserver: $*" >&2; exit 1; }

# multibranch item names are URL-encoded once by Jenkins and once more in the URL
branch_path() { printf '%s' "$1" | sed -e 's/%/%2525/g' -e 's|/|%252F|g'; }
job_url() { echo "${URL}/job/$1/job/$(branch_path "$2")"; }

json_field() { # json_field <python expression on d>
	python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null ||
		python -c "import sys,json; d=json.load(sys.stdin); print($1)"
}

crumb_header() {
	curl -sf -c "${COOKIES}" "${URL}/crumbIssuer/api/json" | json_field "d['crumbRequestField']+':'+d['crumb']"
}

post() { # post <url> ; prints the Location header
	local crumb
	crumb=$(crumb_header) || die "cannot reach ${URL}"
	curl -s -b "${COOKIES}" -H "${crumb}" -X POST -D - -o /dev/null "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'
}

wait_ready() {
	for _ in $(seq 1 90); do
		curl -sf -o /dev/null "${URL}/api/json" && return 0
		sleep 2
	done
	die "server did not come up at ${URL}"
}

build_number() { # build_number <job> <branch> [n]
	if [ -n "${3:-}" ]; then echo "$3"; return; fi
	curl -sf "$(job_url "$1" "$2")/api/json?tree=lastBuild%5Bnumber%5D" | json_field "d['lastBuild']['number']"
}

print_tests() { # print_tests <build url>
	local summary
	summary=$(curl -sf "$1/testReport/api/json?tree=passCount,failCount,skipCount") || { echo "tests: no test report"; return; }
	echo "tests: $(echo "${summary}" | json_field "'%d passed, %d failed, %d skipped' % (d['passCount'], d['failCount'], d['skipCount'])")"
	curl -sf "$1/testReport/api/json?tree=suites%5Bcases%5BclassName,name,status,errorDetails%5D%5D" | json_field "'\n'.join('  FAILED %s.%s: %s' % (c['className'], c['name'], (c.get('errorDetails') or '').splitlines()[0][:200] if c.get('errorDetails') else '') for s in d['suites'] for c in s['cases'] if c['status'] in ('FAILED','REGRESSION'))"
}

command="${1:-}"
shift || true

case "${command}" in
up)
	(cd "${COMPOSE_DIR}" && docker compose up -d --build) || die "docker compose up failed in ${COMPOSE_DIR}"
	wait_ready
	echo "test server up: ${URL}"
	;;
down)
	(cd "${COMPOSE_DIR}" && docker compose down)
	;;
status)
	curl -sf -o /dev/null "${URL}/api/json" || die "not reachable: ${URL} (testserver.sh up)"
	echo "test server: ${URL}"
	curl -sf "${URL}/api/json?tree=jobs%5Bname,jobs%5Bname,color%5D%5D" |
		json_field "'\n'.join(j['name'] + ': ' + ', '.join(b['name'].replace('%2F','/') + '(' + b.get('color','') + ')' for b in j.get('jobs',[])) for j in d['jobs'])"
	;;
scan)
	[ $# -ge 1 ] || die "usage: scan <job>"
	post "${URL}/job/$1/build?delay=0" >/dev/null
	echo "scan of $1 started"
	;;
build)
	[ $# -ge 2 ] || die "usage: build <job> <branch|tag> [NAME=value ...]"
	job=$1; branch=$2
	shift 2
	branch_json=$(curl -sf "$(job_url "${job}" "${branch}")/api/json?tree=property%5B_class%5D") ||
		die "no branch '${branch}' in job '${job}' (commit it locally, then: scan ${job})"
	# a job with parameters (declared by its Jenkinsfile after its first build) only starts through buildWithParameters;
	# parameters not given keep their defaults
	if [ $# -gt 0 ] || echo "${branch_json}" | grep -q ParametersDefinitionProperty; then
		form=()
		for parameter in "$@"; do form+=(--data-urlencode "${parameter}"); done
		queue=$(post "$(job_url "${job}" "${branch}")/buildWithParameters?delay=0" "${form[@]}")
	else
		queue=$(post "$(job_url "${job}" "${branch}")/build?delay=0")
	fi
	[ -n "${queue}" ] || die "build was not queued"
	number=''
	for _ in $(seq 1 120); do
		number=$(curl -sf "${queue%/}/api/json" | json_field "d.get('executable',{}).get('number','') if d.get('executable') else ''")
		[ -n "${number}" ] && break
		sleep 2
	done
	[ -n "${number}" ] || die "build did not start"
	build_url="$(job_url "${job}" "${branch}")/${number}"
	echo "build ${job} ${branch} #${number}: ${build_url}"
	result=''
	for _ in $(seq 1 $((WAIT_SECONDS / 10))); do
		result=$(curl -sf "${build_url}/api/json?tree=building,result" | json_field "'' if d['building'] else d['result']")
		[ -n "${result}" ] && break
		sleep 10
	done
	[ -n "${result}" ] || die "still running after ${WAIT_SECONDS}s: ${build_url}"
	echo "result: ${result}"
	print_tests "${build_url}"
	echo "key log lines:"
	curl -sf "${build_url}/consoleText" | grep -E "MIMIC|TOWER|coherence check|JUnit aggregation|^  (OK|NONCONFORM) |WARNING|ERROR|unstable|Finished:" | sed 's/^/  /' | head -40
	[ "${result}" = SUCCESS ]
	;;
log)
	[ $# -ge 2 ] || die "usage: log <job> <branch> [n]"
	n=$(build_number "$1" "$2" "${3:-}") || die "no builds"
	curl -sf "$(job_url "$1" "$2")/${n}/consoleText"
	;;
tests)
	[ $# -ge 2 ] || die "usage: tests <job> <branch> [n]"
	n=$(build_number "$1" "$2" "${3:-}") || die "no builds"
	print_tests "$(job_url "$1" "$2")/${n}"
	;;
*)
	sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
	[ -z "${command}" ] || exit 1
	;;
esac
