# Changelog

Each tool is versioned independently and released as an immutable git tag `<tool>/vMAJOR.MINOR.PATCH`;
see [Versioning](README.md#versioning). Newest first within each tool.

## deployStandardProteomicsService

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
