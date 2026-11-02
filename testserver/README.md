# Jenkins test server

Version 1.0.0 (tag `testserver/v1.0.0`; see `../CHANGELOG.md`).

A local Jenkins that runs real Jenkinsfiles of local repos, so a pipeline change (a jenkinstools tool, a Jenkinsfile, a
Dockerfile's test stage) can be tried end to end before it reaches a shared Jenkins. Generic: it knows no organisation.
An organisation preset adds what makes it look like its own Jenkins (e.g. vbc_deployment `JenkinsTestServerVbc`: IT's
`buildDockerImage` mimic, a Tower mimic, an Artifactory mimic, credential ids).

## What it is

| Part | What |
| --- | --- |
| `controller/` | `jenkins/jenkins:2.580.2-lts-jdk21` with pinned plugins (`plugins.txt`), the docker CLI and buildx, configured entirely by JCasC (`casc/base.yaml` + an overlay folder). No setup wizard, **no security**: the UI is published on 127.0.0.1 only. |
| `dind` | its own docker daemon (`docker:29.9.2-dind`), so builds never touch the host's docker. Shares `jenkins_home` at the same path, which `docker.image().inside` needs. |
| jobs | one multibranch job per entry of `TESTSERVER_REPOS`, reading the repo from the host folder mounted at `/repos` (read-only, `file://`). Branches and tags are discovered every 2 minutes and **never built automatically**: you start the builds you want. |
| git redirects | `TESTSERVER_GIT_REDIRECTS` maps remote URLs to local repos (git `insteadOf`), so a Jenkinsfile that loads `library identifier: 'x@ref', retriever: modernSCM(<github url>)` gets the local checkout, unchanged. |

Only committed state is visible: Jenkins clones the local repos, so commit (a local branch is enough) before building.

## Use

Start the organisation preset (it extends this compose file), then drive it with `testserver.sh`:

```bash
./testserver.sh up                                    # TESTSERVER_COMPOSE_DIR=<preset folder>
./testserver.sh status                                # jobs and their branches
./testserver.sh scan hive.proteomicstime              # pick up a new local branch now
./testserver.sh build hive.proteomicstime my/branch   # build, wait, print result, tests, key log lines
./testserver.sh tests hive.proteomicstime my/branch   # test summary and failing tests of the last build
./testserver.sh log hive.proteomicstime my/branch 3   # full console log of build 3
```

`build` takes `NAME=value` build parameters after the branch. The same operations exist as MCP tools for Claude Code and
other MCP clients: [`mcp/`](mcp/README.md).

The UI is at <http://127.0.0.1:18090> (not 8090: Windows reserves that range for Hyper-V on many machines).

**Trying an unreleased jenkinstools change:** the test server serves the local jenkinstools repo for the GitHub URL, so a
Jenkinsfile can name any local branch or tag. Make a local-only branch of the app repo whose Jenkinsfile pins
`jenkinstools@<your branch>` and build that. Never push such a branch.

## Settings

See the header of `compose.yaml`: `TESTSERVER_REPOS_ROOT`, `TESTSERVER_REPOS`, `TESTSERVER_GIT_REDIRECTS`,
`TESTSERVER_HTTP_HOST`, `TESTSERVER_HTTP_PORT`, `TESTSERVER_INSECURE_REGISTRY`, `TESTSERVER_DIND_DNS`, `TESTSERVER_SYSTEM_MESSAGE`.

`TESTSERVER_DIND_DNS` matters on restricted networks: containers started by the inner daemon cannot use the compose
network's resolver and fall back to 8.8.8.8. On Docker Desktop, `192.168.65.7` is the host's resolver.

Plugins: `freeze-plugins.sh` resolves unversioned entries of `controller/plugins.txt` and pins them.

A preset can make the "agent" closer to its real ones with build args of the `jenkins` service: `DOCKER_CLI_IMAGE`
(with a matching `image:` for `dind`) and `AGENT_APT_PACKAGES` (Debian packages, e.g. `python3 jq`).

## Limits

- One controller, builds on the built-in node. The real agents' OS, tools and docker versions may differ; the
  introspection probe (`../probe/pipeline-introspection`) reports what the real ones have; a preset records and matches
  that (VBC: vbc_deployment `docs/jenkins-vbc.md`).
- The sandbox and script approval behave as on any Jenkins: a dynamically loaded library (`library identifier:`) is
  untrusted, a global library configured by the overlay is trusted.
