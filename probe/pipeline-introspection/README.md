# Pipeline introspection probe

A Jenkinsfile that reports what a pipeline on the VBC Jenkins can see of its own surroundings, without admin rights. Made
2026-11-02 to learn what IT's `buildDockerImage` (vbc-cicd) does and what the agents offer, after access to the vbc-cicd
source was lost, and to see aggregated test results in the real Jenkins UI before releasing them.

It never calls `deployService` or `buildDockerImage`: nothing is built for release, pushed or deployed. It uses no
credentials; Jenkins REST calls are anonymous.

## What it reports

| Stage | What | Why |
| --- | --- | --- |
| Agent and tools | host, OS, user, which tools exist (python3? java? jq?), docker and buildx versions, docker daemon info | what jenkinstools may rely on, and what the test server mimic must offer |
| Environment | all env vars, secret-looking values masked | what Jenkins and the agent set (`BRANCH_NAME`, `TAG_NAME`, `JENKINS_URL`, ...) |
| Workspace layout | the workspace and its `@tmp`, `@libs`, `@script` siblings | `@libs` holds library checkouts when they live on this node; that may include vbc-cicd's source |
| Jenkins REST (anonymous) | `whoAmI`, the plugin list, this build's libraries (`trusted` or not), `wfapi/describe`; with `INSPECT_BUILD_URL` also another build's step list (`flowGraphTable`) | the step list of a real `deployStandardProteomicsService` run shows every `sh` that buildDockerImage issues, with its arguments |
| JUnit step parameters | whether `keepLongStdio` and `stdioRetention` are accepted | tells the junit plugin generation; deployService uses `keepLongStdio` |
| Aggregation demo | runs `aggregate_junit.py` from `JENKINSTOOLS_REF` in the pinned Python image over the real xUnit/pytest fixtures and publishes the result | shows how aggregated tests look in this Jenkins (classic Test Result page, Blue Ocean, Pipeline Graph View) before a release; proves Docker Hub pulls and `docker.image().inside` work |
| Groovy introspection | off by default (`GROOVY_INTROSPECTION`) | **every sandbox rejection lands in the admins' script approval queue.** Turn it on only after telling IT |

The demo fixtures contain deliberately failing tests, so a probe build ends UNSTABLE. That is expected.

## How to run it

A multibranch job that scans the repo creates a branch job for any branch with a `Jenkinsfile` at the root, not only for
`pushBranches` (those only decide what gets pushed to the registry). Whether it also *builds* a newly discovered branch
depends on the job's configuration; on jenkins.vbc.ac.at it does not. So:

1. In a repo Jenkins already scans, create a branch (e.g. `feat/probe-pipeline-introspection`) and put this file there as
   the root `Jenkinsfile`. Nothing else on the branch matters.
2. Push the branch. If no build starts, run "Scan Repository Now" on the multibranch job (its scan log says whether the
   branch met the criteria and whether a build was triggered), then "Build Now" on the branch job. The first build
   registers the parameters; "Build with Parameters" runs again with other values (`INSPECT_BUILD_URL`, `JENKINSTOOLS_REF`).
3. Read the console log and the Test Result page. Delete the branch afterwards; the multibranch job drops it on its next scan.

The REST section is anonymous. Where anonymous read is denied (jenkins.vbc.ac.at), it only shows `whoAmI`, and
`INSPECT_BUILD_URL` cannot fetch anything; the console log of a real build shows every `sh` command of the library steps
anyway.

Pushing this file to jenkinstools itself triggers nothing: jenkinstools has no root `Jenkinsfile` and no Jenkins job.

Prefer the local test server (vbc_deployment `JenkinsTestServerVbc`) for everything that does not need the real Jenkins.

## Where it has run

- **Test server, 2026-11-02** (local branch of hive.proteomicstime with this file as its root `Jenkinsfile`,
  `JENKINSTOOLS_REF=feat/test-result-aggregation`): every section works. Anonymous REST answers 200 there because the test
  server has no security; the build's libraries show `vbc-cicd` trusted and `jenkinstools` untrusted; the junit plugin
  accepts both `keepLongStdio` and `stdioRetention`; the aggregation demo publishes 12 tests (7 passed, 5 failed, 2
  skipped) as intended. That run only proves the probe itself.
- **jenkins.vbc.ac.at, 2026-11-02** (ProteomicsHelper.Python, `feat/probe-pipeline-introspection` #1, jenkinstools
  `main`): every section ran; the build ended UNSTABLE as intended. What it found is about that Jenkins, not about the
  probe, so it is recorded with the VBC preset: vbc_deployment `docs/jenkins-vbc.md`.
