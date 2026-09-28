# Changelog

Every release is an immutable git tag `vMAJOR.MINOR.PATCH`; see [Versioning](README.md#versioning).

## v1.1.0 - 2026-09-28

- **`deployService`**: the whole protchem service pipeline (build args, NuGet BuildKit secret,
  in-image tests, OCP probe smoke, push, Tower, cockpit notification) behind one config map, so a
  Jenkinsfile is only its configuration.
- **`vbcDeploymentCockpitNotify`**: `credentialsId` option, `vbcDeploymentCockpitNotify(event, [credentialsId: ...])`.
  A missing credential only warns (unless `failOnError: true`). The `(event, connectionString)` form is unchanged.
- `resources/jenkinstools/VERSION`, printed by `deployService` so a build log shows which release it ran.

## v1 - 2026-09-28

Initial release (tag `v1`, predates the three-part scheme; treat it as v1.0.0): `webhookNotify`,
`serviceBusNotify`, `buildEventMessage`, `vbcDeploymentCockpitNotify`.
