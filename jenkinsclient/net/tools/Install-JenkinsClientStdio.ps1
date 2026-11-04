<#
.SYNOPSIS
    Publishes jenkinsclient into a new versioned folder and registers that copy with Claude Code as the stdio MCP server
    "jenkinsclient".

.DESCRIPTION
    In stdio mode the MCP client starts the server itself, per session, so nothing has to be running beforehand.

    Every Claude session keeps its own server process, and Windows locks a running program's files. So an update never
    overwrites a copy: each install publishes into its own folder under Root (git version + time stamp), points the
    user-scope registration at it, and deletes older copies no process runs from (locked ones go on a later install).
    Running sessions keep their server; new sessions (and /mcp reconnects) start the new one. Same scheme as AzureTools.

    Sites are not part of the install: they live in the user settings file (%LOCALAPPDATA%\JenkinsClient\settings.json),
    which updates never touch. -Settings copies a preset there (e.g. vbc_deployment JenkinsClientVbc\settings.json), but
    only if the file does not exist yet, unless -ForceSettings.

.PARAMETER Root
    Folder holding the versioned copies.

.PARAMETER Settings
    A settings file (organisation preset) to install as the user settings file.

.PARAMETER ForceSettings
    Overwrite an existing user settings file with -Settings (the old one is kept as settings.json.bak).

.PARAMETER Name
    Name of the Claude Code MCP registration.

.PARAMETER NoRegister
    Only publish; print the claude mcp command instead of running it.

.EXAMPLE
    pwsh -NoProfile -File jenkinsclient/net/tools/Install-JenkinsClientStdio.ps1 -Settings ../vbc_deployment/JenkinsClientVbc/settings.json
#>
[CmdletBinding()]
param(
    [string] $Root = (Join-Path $env:LOCALAPPDATA 'JenkinsClient\versions'),
    [string] $Settings,
    [switch] $ForceSettings,
    [string] $Name = 'jenkinsclient',
    [switch] $NoRegister
)

$ErrorActionPreference = 'Stop'
$net = Resolve-Path (Join-Path $PSScriptRoot '..')
$project = Join-Path $net 'src\JenkinsTools.Client.Host'

$version = (& git -C $net describe --tags --match 'jenkinsclient-v*' --always --dirty 2>$null) ?? 'unversioned'
$destination = Join-Path $Root "$version`_$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force $Root | Out-Null

& dotnet publish $project -c Release -o $destination --nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed (exit $LASTEXITCODE)." }
$exe = (Join-Path $destination 'jenkinsclient.exe').Replace('\', '/')
Write-Host ''
Write-Host "Published $version to $destination"

# the user settings file: sites, users, token references. Never overwritten by an update.
$userSettings = Join-Path $env:LOCALAPPDATA 'JenkinsClient\settings.json'
if ($Settings) {
    $source = Resolve-Path $Settings
    if ((Test-Path $userSettings) -and -not $ForceSettings) {
        Write-Host "Kept the existing $userSettings (-ForceSettings replaces it with $source)"
    }
    else {
        if (Test-Path $userSettings) { Copy-Item $userSettings "$userSettings.bak" -Force }
        Copy-Item $source $userSettings -Force
        Write-Host "Installed settings $source -> $userSettings"
    }
}
elseif (-not (Test-Path $userSettings)) {
    Write-Warning "No sites configured yet: create $userSettings (see jenkinsclient/README.md) or pass -Settings <preset>."
}

if ($NoRegister) {
    Write-Host "Register it with Claude Code (replaces an existing ""$Name"" entry):"
    Write-Host "  claude mcp remove --scope user $Name"
    Write-Host "  claude mcp add --scope user $Name -- $exe --stdio"
}
else {
    & claude mcp remove --scope user $Name *> $null   # absent on a first install
    & claude mcp add --scope user $Name -- $exe --stdio
    if ($LASTEXITCODE -ne 0) { throw "claude mcp add failed (exit $LASTEXITCODE)." }
    Write-Host "Registered: $Name -> $exe --stdio"
}

Write-Host "Command line: $exe check | sites | set-token <site>"

# older copies; one a server still runs from is left whole (deleting part of it would break that server's next lazy load)
$running = @(Get-CimInstance Win32_Process -Filter "Name='jenkinsclient.exe'" | ForEach-Object ExecutablePath)
foreach ($dir in @(Get-ChildItem $Root -Directory | Where-Object FullName -ne (Get-Item $destination).FullName)) {
    $prefix = $dir.FullName.TrimEnd('\') + '\'
    if ($running | Where-Object { $_ -and $_.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) }) {
        Write-Host "Kept old copy $($dir.FullName) (a running session uses it; removed by a later install)"
        continue
    }
    Remove-Item $dir.FullName -Recurse -Force
    Write-Host "Removed old copy $($dir.FullName)"
}
