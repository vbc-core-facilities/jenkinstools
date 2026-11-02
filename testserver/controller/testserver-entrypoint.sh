#!/bin/bash
# Applies the test server's git redirects, then starts Jenkins.
#
# TESTSERVER_GIT_REDIRECTS: semicolon-separated "<remote url>=<local path>" pairs. Every git operation of Jenkins (checkouts,
# shared library loads) that names the remote url uses the local repo instead, through git's url.<base>.insteadOf. That is
# how a Jenkinsfile that loads `library identifier: 'x@ref', retriever: modernSCM(<github url>)` gets the local checkout of
# x, unchanged. The local repo must have the ref (branch or tag) the Jenkinsfile names.

set -eu

if [ -n "${TESTSERVER_GIT_REDIRECTS:-}" ]; then
	IFS=';' read -ra redirects <<<"${TESTSERVER_GIT_REDIRECTS}"
	for redirect in "${redirects[@]}"; do
		[ -z "${redirect}" ] && continue
		remote="${redirect%%=*}"
		local_path="${redirect#*=}"
		git config --global "url.file://${local_path}.insteadOf" "${remote}"
		echo "testserver: git redirect ${remote} -> file://${local_path}"
	done
fi

mkdir -p /var/jenkins_conf/overlay 2>/dev/null || true

exec /usr/local/bin/jenkins.sh "$@"
