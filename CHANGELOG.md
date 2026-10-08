# Changelog

Each tool is versioned independently and released as an immutable git tag `<tool>/vMAJOR.MINOR.PATCH`;
see [Versioning](README.md#versioning). Newest first within each tool.

## deployStandardProteomicsService

### deployStandardProteomicsService/v2.2.0 - 2026-10-07
New `highFidelity` (default: true on tag builds, false otherwise), passed on to deployService/v2.1.0.

### deployStandardProteomicsService/v2.1.0 - 2026-10-07
New input `imageRegistry` (default `docker.artifactory.imp.ac.at`), passed on to deployService v2.0.0. Every build that pushes now
gets the always-on image coherence check, which can fail a build whose pushed image was made by another build; see deployService/v2.0.0.

### deployStandardProteomicsService/v2.0.0 - 2026-09-29
Breaking:
- `dockerContext` is required (no longer left to buildDockerImage's default).
- No `ASPNET_PORT` default any more: the port is not ASP.NET-specific. The new optional `dockerHttpPort` is
  passed as the stack-neutral build arg `APP_PORT` (OCP deployment standard), and the Dockerfile maps it
  to its stack. When unset, no port build arg is passed and the Dockerfile default applies.

Also: `testScript` / `ocpSmokeExtraChecks` are dropped when `tests: false`, and the code is
restructured for readability. Otherwise the behaviour is unchanged.

### deployStandardProteomicsService/v1.0.0 - 2026-09-29
First release: the protchem conventions on top of deployService. It sets:
- namespace, branches, the Tower staging branch and tag variable (the Tower job names themselves are
  required inputs, mapped by hand)
- the standard build args and the NuGet BuildKit secret
- the test folder, the OCP smoke, and the cockpit notification

All of them can be overridden, and `null` removes a default build arg.

## deployService

### deployService/v2.1.1 - 2026-10-08
When the image coherence check fails (or marks the build UNSTABLE), the message and the docs now note that this is a known issue caused by a race condition, and that VBC IT stated
they have no plans to fix it, with a reference: https://vbc.atlassian.net/servicedesk/customer/portal/5/ISD-60715

### deployService/v2.1.0 - 2026-10-07
The image coherence check now follows a stricter rule. Every image gets three labels (run GUID, build category, commit). After the pipeline the
pushed image must carry this run's GUID; otherwise another build category (or none) fails the build, the same category and commit only warns,
and the same category with another commit fails with the new optional input `highFidelity` and otherwise marks the build UNSTABLE, saying whether
the image is older or newer. Builds that push nothing are not checked. A check that cannot run only marks the build UNSTABLE.

### deployService/v2.0.0 - 2026-10-07
Breaking: `imageRegistry` is a required input (deployStandardProteomicsService supplies it).

New: an always-on image coherence check. Context: `v2.43.0` of hive.proteomicstime and `v1.33.0` of labvz were released with version
`0.0.0`, although their tag builds passed `MINVER_VERSION_OVERRIDE` correctly. The registry image of both tags was made by the branch
build of the same commit: buildDockerImage names the local image after the commit, so the two builds, started 0.6 s apart on one
agent, overwrote each other's image before the push. Each pipeline run now generates a GUID, puts it on its image as the label
`coherence_guid_5ab99355877948ccbde41c74e4a95bdd` (through extraBuildArgs), and after the pipeline pulls the image it pushed and checks
that it carries that GUID. It is a detector: the push and Tower run inside buildDockerImage, so it reports after the deploy that the
deployed state is most likely wrong and has to be redone. A check that cannot run only marks the build UNSTABLE.

### deployService/v1.0.1 - 2026-09-29
Readability restructuring with no behaviour change: named variables and closure parameters, and the
buildDockerImage parameters built in one place. The build-info report also shows the docker context.

### deployService/v1.0.0 - 2026-09-29
First release: generic service pipeline wiring on top of IT's buildDockerImage, with no defaults.
- build args and arbitrary BuildKit secrets
- build-info reporting
- optional in-image tests, test script and OCP smoke
- push and Tower
- optional cockpit notification
- `beforeBuild` / `afterBuild` / `afterAlways` actions
- configuration validation that lists every problem

## vbcDeploymentCockpitNotify

### vbcDeploymentCockpitNotify/v1.1.1 - 2026-09-29
Readability restructuring, no behaviour change.

### vbcDeploymentCockpitNotify/v1.1.0 - 2026-09-28
- `credentialsId` option: `vbcDeploymentCockpitNotify(event, [credentialsId: ...])`. A missing credential
  only warns (unless `failOnError: true`). The `(event, connectionString)` form is unchanged.

### vbcDeploymentCockpitNotify/v1.0.0 - 2026-09-28
First release (shipped in the whole-library tag `v1`).

## serviceBusNotify

### serviceBusNotify/v1.0.1 - 2026-09-29
Readability restructuring, no behaviour change.

### serviceBusNotify/v1.0.0 - 2026-09-28
First release (shipped in `v1`): send to a queue/topic from a connection string (per-call SAS token)
or a SAS token.

## webhookNotify

### webhookNotify/v1.0.1 - 2026-09-29
Readability restructuring, no behaviour change.

### webhookNotify/v1.0.0 - 2026-09-28
First release (shipped in `v1`).

## buildEventMessage

### buildEventMessage/v1.0.0 - 2026-09-28
First release (shipped in `v1`): `jenkins-build-event/v1` messages.

## Legacy

`v1` (2026-09-28) is a whole-library tag from before per-tool versioning. It contains webhookNotify,
serviceBusNotify, buildEventMessage and vbcDeploymentCockpitNotify at v1.0.0. It stays for existing pins
and is not reused.
