---
name: jenkins-testserver
description: Use when a Jenkins pipeline change should be tried locally before it reaches the shared Jenkins - "run this Jenkinsfile locally", "test the pipeline", "does the build pass on the test server", "try the jenkinstools change", "build <repo> <branch> on the test Jenkins", "what do the test results look like in Jenkins". Drives the jenkinstools Jenkins test server (testserver/testserver.sh); for the VBC ecosystem through vbc_deployment/JenkinsTestServerVbc.
---

# Jenkins test server

A local Jenkins (jenkinstools `testserver/`) that runs real Jenkinsfiles of local repos. For the VBC proteomics ecosystem
it is started from `vbc_deployment/JenkinsTestServerVbc`, which adds mimics of IT's `buildDockerImage`, the Ansible Tower
and `docker.artifactory.imp.ac.at`. Nothing deploys; nothing reaches the real registry or cockpit.

Two equivalent ways in; never drive the UI:

- **MCP tools** of the `jenkins-testserver` server (`testserver/mcp/`): `status`, `scan`, `start_build`, `wait_build`,
  `build_status`, `test_report`, `console_log`, `server_up`, `server_down`. Prefer them when they are available.
- **The script** `testserver/testserver.sh` (Git Bash on Windows), same operations, for when the MCP server is not
  registered.

## Procedure

1. **Is it running?** `status` (tool) or `testserver.sh status`. If not reachable: `server_up`, or
   `TESTSERVER_COMPOSE_DIR=<repos>/vbc_deployment/JenkinsTestServerVbc testserver/testserver.sh up`
   (needs `TOKEN_GITHUB_NUGET_VBCPROTEOMICS` in the environment for NuGet restores; first start builds the controller).
2. **Commit what should be tested.** Jenkins clones the local repos: uncommitted changes are invisible. A local branch is
   enough. Then `scan` the job (or wait up to 2 minutes).
3. **Unreleased jenkinstools change?** The Jenkinsfile pins a released tag. Create a local-only branch of the app repo
   whose Jenkinsfile pins `jenkinstools@<jenkinstools branch>` (commit message starting "TEST ONLY (local, never push)").
   The test server serves local jenkinstools for the GitHub URL.
4. **Build:** `start_build`, then `wait_build` until it reports a result (each call waits at most 4 minutes); or
   `testserver.sh build <job> <branch> [NAME=value ...]`, which blocks. The result comes with the test summary, failing
   tests and the key log lines (mimic, Tower, coherence check, aggregation). Builds take minutes (whole image builds).
   `testserver.sh build` exits 0 only on SUCCESS; read the printed result, not just the exit code.
5. **Report** the result faithfully: SUCCESS / UNSTABLE / FAILURE, which tests failed and why, and what the mimic did
   (pushed or not, which Tower job). Say what the test server cannot show (below).

Jobs (VBC preset): `hive.proteomicstime`, `ProteomicsHelper.Python`, `hive.core`, `hive.frontend`, `labvz`; more through
`TESTSERVER_REPOS`. Branch names with `/` are fine.

## What it cannot tell you

- The real `buildDockerImage` is IT's; the mimic follows IT's documentation and observed behaviour. A difference between
  test server and jenkins.vbc.ac.at may be the mimic's.
- The real agents' tools and versions may differ (the probe in `probe/pipeline-introspection` reports them).
- `hive.core`, `hive.frontend`, `hive.proteomicstime` apps need databases at runtime; only their build and in-image tests
  run here, as on the real Jenkins.

## Mimic knobs (VBC preset, environment of `up`)

- `MIMIC_TOWER_FAILURE_RATE=0.01`: known Tower jobs fail at random with that probability.
- `MIMIC_RACE_WINDOW_SECONDS=60`: pause between build and push, so a parallel build of the same commit (branch + tag)
  reproduces the shared-local-image race (ISD-60715); the jenkinstools image coherence check should then catch it.
- An unknown Tower job template always fails (list: `vbc_deployment/JenkinsTestServerVbc/vbc-cicd-mimic/resources/vbc-tower-mimic/known-templates.txt`).
