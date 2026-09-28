# Changelog

Each tool is versioned independently and released as an immutable git tag `<tool>/vMAJOR.MINOR.PATCH`;
see [Versioning](README.md#versioning). Newest first within each tool.

## deployStandardProteomicsService

### deployStandardProteomicsService/v1.0.0 - 2026-09-29
First release: the protchem conventions on top of deployService. It sets:
- namespace, branches, the Tower staging branch and tag variable (the Tower job names themselves are
  required inputs, mapped by hand)
- the standard build args and the NuGet BuildKit secret
- the test folder, the OCP smoke, and the cockpit notification

All of them can be overridden, and `null` removes a default build arg.

## deployService

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

### vbcDeploymentCockpitNotify/v1.1.0 - 2026-09-28
- `credentialsId` option: `vbcDeploymentCockpitNotify(event, [credentialsId: ...])`. A missing credential
  only warns (unless `failOnError: true`). The `(event, connectionString)` form is unchanged.

### vbcDeploymentCockpitNotify/v1.0.0 - 2026-09-28
First release (shipped in the whole-library tag `v1`).

## serviceBusNotify

### serviceBusNotify/v1.0.0 - 2026-09-28
First release (shipped in `v1`): send to a queue/topic from a connection string (per-call SAS token)
or a SAS token.

## webhookNotify

### webhookNotify/v1.0.0 - 2026-09-28
First release (shipped in `v1`).

## buildEventMessage

### buildEventMessage/v1.0.0 - 2026-09-28
First release (shipped in `v1`): `jenkins-build-event/v1` messages.

## Legacy

`v1` (2026-09-28) is a whole-library tag from before per-tool versioning. It contains webhookNotify,
serviceBusNotify, buildEventMessage and vbcDeploymentCockpitNotify at v1.0.0. It stays for existing pins
and is not reused.
