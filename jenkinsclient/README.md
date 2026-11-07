# jenkinsclient

A client of **any** Jenkins, through its public REST API only, as an MCP server (for Claude Code and other MCP clients),
a command line, and a .NET library. It owns nothing on the Jenkins side. That's the difference from `../testserver`,
which runs and configures a Jenkins of its own.

Version line `jenkinsclient-vX.Y.Z` (see the repository README, Versioning). Per language under this folder:
`net/` now; other languages are in [FUTURE_WORK.md](FUTURE_WORK.md).

## What it does

| MCP tool | What |
| --- | --- |
| `list_sites`, `whoami` | the configured sites and who each one sees us as (checks URL, user, token) |
| `list_jobs`, `get_job` | jobs of the root or a folder; a job with its children (a multibranch job's branches, tags, PRs), last builds and parameters |
| `get_build` | result, times, node, causes, parameters (secrets masked), the job's own commit and branch, every checkout (libraries too), artifacts |
| `console_log` | last (or first) N lines, optionally only lines matching a regular expression |
| `test_report` | JUnit counts and the failing cases |
| `queue`, `scan_log` | what waits and why; the last branch discovery log of a multibranch job / organization folder |
| `scan`, `start_build`, `wait_build`, `abort_build` | the only writes: branch discovery, a build (with parameters; waits for the build number), waiting for the result, stopping a build |

Never: job configuration, the script console, credentials, plugin management.

Jobs are addressed as `job` = folder/job names (`team-folder/repo`) or a job URL, plus `branch` taken literally
(`feat/x`; the client handles Jenkins' double encoding of branch names in URLs).

## Sites and credentials

Jenkins authenticates API calls with HTTP Basic: **user + API token**. The client then has that user's rights. A token is
never written into configuration; a site names a **reference** to it:

| Reference | Where the token is |
| --- | --- |
| `wincred:<target>` | Windows Credential Manager (DPAPI, no prompt). `jenkinsclient set-token <site>` stores it: it prompts in your own terminal, nothing is echoed |
| `op://vault/item/field` | 1Password CLI (`op read`); needs the 1Password app unlocked, may prompt |
| `env:NAME`, `file:path` | an environment variable, a file's content |

No reference: anonymous (e.g. a local test server without security). Create a token on your Jenkins user page:
Security, "Add new Token". One token per tool is easiest to revoke.

Settings (last wins): `appsettings.json` next to the program (ships no sites: the tool knows no organisation), the
**user settings file** (`%LOCALAPPDATA%\JenkinsClient\settings.json`, or the path in `JENKINSCLIENT_SETTINGS`),
environment variables, the command line.

```json
{ "JenkinsClient": {
    "DefaultSite": "ci",
    "Sites": {
      "ci":    { "BaseUrl": "https://jenkins.example.org/", "Username": "", "TokenReference": "wincred:JenkinsClient/ci" },
      "local": { "BaseUrl": "http://127.0.0.1:18090/" } } } }
```

`Username` is per site; empty means your OS user name. Override one without editing the file:
`JenkinsClient__Sites__ci__Username=someone`, or `--JenkinsClient:Sites:ci:Username=someone`. An organisation preset
is such a file (VBC: vbc_deployment `JenkinsClientVbc/settings.json`).

## Install (Windows, Claude Code)

```powershell
pwsh -NoProfile -File jenkinsclient/net/tools/Install-JenkinsClientStdio.ps1 -Settings <preset settings.json>
jenkinsclient set-token <site>      # the path is printed by the install; or use the full path of jenkinsclient.exe
jenkinsclient check
```

The script publishes into `%LOCALAPPDATA%\JenkinsClient\versions\<version>_<time>` and registers that copy as the
user-scope MCP server `jenkinsclient` (`--stdio`). Re-run it to update: open sessions keep their copy, new sessions get the
new one, unused old copies are deleted (AzureTools' scheme). The user settings file is never overwritten (`-ForceSettings`
does, keeping a `.bak`).

## Modes of the program

| Command | |
| --- | --- |
| `jenkinsclient --stdio` | MCP over stdin/stdout; the MCP client starts it. Logs go to stderr |
| `jenkinsclient serve [--port 5190]` | MCP over streamable HTTP at `http://127.0.0.1:<port>/mcp`, loopback only, with a Host header check, `/healthz`. It acts with *your* tokens, so it never listens beyond this machine |
| `jenkinsclient check [site]`, `sites`, `set-token <site>` | the command line |

A shared HTTP host with per-caller credentials is in [FUTURE_WORK.md](FUTURE_WORK.md).

## The library

`JenkinsTools.Client` (`net/src/JenkinsTools.Client`, no dependencies beyond the BCL): `JenkinsSites` (configured sites →
clients, token references resolved), `JenkinsClient` (one site), `JobPath`, `SecretReferences`. It is meant to replace
the Jenkins clients in VbcDeploymentCockpit (`JenkinsLib`) and ProteomicsEcosystemDeveloperAgent.

It is the only package here, and it goes to the **private feed** `github_vbc_proteomics`
(`https://nuget.pkg.github.com/vbc-proteomics-org/index.json`), like the other packages of VBC repositories. Package ids
of VBC repositories carry no personal prefix; `Xen.*` packages come only from xeniorn repositories (to nuget.org).
The plain `JenkinsClient` is taken on nuget.org, and our consumers restore from both feeds, hence `JenkinsTools.Client`.
`JenkinsTools.Client.Mcp` and `JenkinsTools.Client.Host` are the application, built from source by the install script,
never packaged.

Publishing a version (push on pack, as the other private-feed repos; see `net/Packaging.props`):

```powershell
# after tagging jenkinsclient-vX.Y.Z on the commit; needs TOKEN_GITHUB_NUGET_VBCPROTEOMICS (a PAT with write:packages)
dotnet pack jenkinsclient/net/src/JenkinsTools.Client -c Release     # builds AND pushes JenkinsTools.Client X.Y.Z
```

`dotnet pack` always pushes: use `dotnet build` to check that it builds. Restore only uses nuget.org (`net/nuget.config`
maps every package there), so building needs no feed token.

## Build and test

```powershell
dotnet test jenkinsclient/net/JenkinsClient.slnx
```

The unit tests run against an in-memory fake Jenkins. Verified against a real one: the jenkinstools test server
(`../testserver`), 2026-11-04: list, job, build, console log with a filter, a missing test report, start → queue → build
number → wait, NotFound.
