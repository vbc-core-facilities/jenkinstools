# jenkinsclient: future work

Not needed now; written down so it is not lost.

- **Publish `Xen.JenkinsClient` to nuget.org** from `jenkinsclient-v*` tags: a GitHub Actions release workflow with
  NuGet Trusted Publishing (no stored key), as in `Xen.Utils.ApiKeyMinting`. To check first: Actions enabled for
  `vbc-core-facilities`. Never push from a developer machine.
- **Move the consumers onto the library**: VbcDeploymentCockpit `JenkinsLib`, ProteomicsEcosystemDeveloperAgent
  `Sources/Jenkins/JenkinsClient.cs`. Each when it next needs something from Jenkins.
- **Python** (`jenkinsclient/python/`): the same client for Python consumers, wherever the .NET library is used.
  Only when there is a Python consumer.
- **Shared HTTP host with per-caller credentials**: each caller sends their own Jenkins user and token (a
  `headersHelper` reading their 1Password or Credential Manager), the server keeps none: the analogue of AzureTools'
  per-session sign-in. Until then `serve` is loopback only and uses the local user's tokens.
- **REST endpoints** (`/api/...`) next to `/mcp` in `serve`, for scripts.
- **The testserver MCP on top of this client**: `testserver/mcp` then keeps only what is specific to a server we run
  (up, down, compose), and its build/log/test tools become this client pointed at the test server.
- **Artifacts**: download a build artifact (today only listed).
- **Progressive console log** (`logText/progressiveText`) for following a running build without re-reading the whole log.
