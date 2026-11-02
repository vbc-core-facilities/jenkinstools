#!/bin/bash
# Builds the controller with controller/plugins.txt and rewrites the file with the versions jenkins-plugin-cli resolved for
# every listed plugin (unversioned entries resolve to the latest compatible release). Run from this folder.
set -eu
docker build -q -t jenkins-testserver-controller:freeze controller >/dev/null
names=$(grep -v '^#' controller/plugins.txt | sed -E 's/:.*//' | grep -v '^$')
header=$(grep '^#' controller/plugins.txt)
pinned=$(docker run --rm --entrypoint "" jenkins-testserver-controller:freeze bash -c "
  for p in ${names//$'\n'/ }; do
    v=\$(unzip -p /usr/share/jenkins/ref/plugins/\$p.jpi META-INF/MANIFEST.MF | grep -i '^Plugin-Version:' | cut -d' ' -f2 | tr -d '\r')
    echo \"\$p:\$v\"
  done")
printf '%s\n%s\n' "${header}" "${pinned}" > controller/plugins.txt
git diff --stat -- controller/plugins.txt || true
