# Jenkins test server controller: runs real Jenkinsfiles of local repos, so pipeline changes can be tried before they reach
# a shared Jenkins. Generic: nothing here knows a particular organisation; an overlay adds libraries, credentials and jobs.
# See ../README.md.

# pinned (dependency pinning); bump deliberately
ARG JENKINS_IMAGE=jenkins/jenkins:2.580.2-lts-jdk21
ARG DOCKER_CLI_IMAGE=docker:29.9.2-cli

FROM ${DOCKER_CLI_IMAGE} AS docker-cli

FROM ${JENKINS_IMAGE}

USER root

# docker CLI and buildx only; the daemon is a separate docker:dind container (DOCKER_HOST)
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

# The repos are bind-mounted from the host and owned by another uid; git refuses those without this.
RUN git config --system --add safe.directory '*'

COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY --chown=jenkins:jenkins casc/ /var/jenkins_conf/base/
COPY --chmod=755 testserver-entrypoint.sh /usr/local/bin/testserver-entrypoint.sh

USER jenkins

# - no setup wizard: the configuration comes from JCasC
# - local file:// git remotes are what this server is for (Git plugin blocks them by default)
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
ENV CASC_JENKINS_CONFIG=/var/jenkins_conf/base,/var/jenkins_conf/overlay

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/testserver-entrypoint.sh"]
