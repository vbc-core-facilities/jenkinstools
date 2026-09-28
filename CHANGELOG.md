# Changelog

Each tool is versioned independently and released as an immutable git tag `<tool>/vMAJOR.MINOR.PATCH`;
see [Versioning](README.md#versioning). Newest first within each tool.

## deployService

### deployService/v1.0.0 - 2026-09-28
First release: the whole protchem service pipeline behind one config map. It covers:
- build args and the NuGet BuildKit secret
- in-image tests and the OCP probe smoke
- push and Tower
- cockpit notification

A Jenkinsfile becomes configuration only.

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
