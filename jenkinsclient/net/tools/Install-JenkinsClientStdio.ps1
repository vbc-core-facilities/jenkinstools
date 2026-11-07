<#
.SYNOPSIS
    Publishes jenkinsclient into a new versioned folder, points the junction "current" at it and registers
    current\jenkinsclient.exe with Claude Code as the stdio MCP server "jenkinsclient".

.DESCRIPTION
    In stdio mode the MCP client starts the server itself, per session, so nothing has to be running beforehand.

    Every Claude session keeps its own server process, and Windows locks a running program's files. So an update never
    overwrites a copy: each install publishes into its own folder under Root (git version + time stamp), re-points the
    directory junction Current (%LOCALAPPDATA%\JenkinsClient\current) at it, and deletes older copies no process runs
    from (locked ones go on a later install). The user-scope registration always names Current\jenkinsclient.exe, a
    command that never changes. Running sessions keep their server; new sessions (and /mcp reconnects) start the new one.

    A process started through the junction opens every file by its ...\current\... path, also assemblies .NET loads
    lazily, long after start, so an old server could load the new version's after a re-point (a single-file publish
    does not help: the runtime reopens the bundle by path). So jenkinsclient.exe, started through a link, only relays:
    it starts the copy the link points to now (by its version folder path) with the same arguments and stdio, and
    returns its exit code.

    Sites are not part of the install: they live in the user settings file (%LOCALAPPDATA%\JenkinsClient\settings.json),
    which updates never touch. -Settings copies a preset there (e.g. vbc_deployment JenkinsClientVbc\settings.json), but
    only if the file does not exist yet, unless -ForceSettings.

.PARAMETER Root
    Folder holding the versioned copies.

.PARAMETER Current
    The directory junction pointing at the newest copy; the registered command and the printed command line go
    through it.

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
    [string] $Current = (Join-Path $env:LOCALAPPDATA 'JenkinsClient\current'),
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
Write-Host ''
Write-Host "Published $version to $destination"

# re-point the junction: remove the link itself (never anything through it; running servers keep their folder), then
# recreate it. A session starting in between fails once; /mcp reconnects.
if (Test-Path -LiteralPath $Current) {
    if ((Get-Item -LiteralPath $Current -Force).LinkType -ne 'Junction') { throw "$Current exists and is not a junction." }
    [IO.Directory]::Delete($Current, $false)
}
New-Item -ItemType Junction -Path $Current -Target $destination | Out-Null
$exe = (Join-Path $Current 'jenkinsclient.exe').Replace('\', '/')
Write-Host "Junction $Current -> $destination"

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
    # the command is stable, so after the first install with the junction there is nothing to change
    $registered = (& claude mcp get $Name 2>$null) -join "`n"
    if ($LASTEXITCODE -eq 0 -and $registered -match "(?m)^\s*Command: $([regex]::Escape($exe))\s*$" -and
        $registered -match '(?m)^\s*Args: --stdio\s*$') {
        Write-Host "Already registered: $Name -> $exe --stdio"
    }
    else {
        & claude mcp remove --scope user $Name *> $null   # absent on a first install
        & claude mcp add --scope user $Name -- $exe --stdio
        if ($LASTEXITCODE -ne 0) { throw "claude mcp add failed (exit $LASTEXITCODE)." }
        Write-Host "Registered: $Name -> $exe --stdio"
    }
}

Write-Host "Command line: $exe check | sites | set-token <site>"

# older copies. Path matching against running processes does not work: a server started through the junction reports
# ...\current\jenkinsclient.exe as its image. Instead each old copy is first renamed, which Windows refuses while a
# process runs from it; only a renamed (so unused) copy is deleted. A copy in use is left whole (deleting part of it
# would break that server's next lazy load) and goes on a later install. The junction target is never touched.
$keep = (Get-Item -LiteralPath $destination).FullName.TrimEnd('\')
foreach ($dir in @(Get-ChildItem -LiteralPath $Root -Directory -Force)) {
    if ($dir.LinkType -or $dir.FullName.TrimEnd('\') -eq $keep) { continue }
    $doomed = $dir.FullName
    if ($dir.Name -notlike '*.deleting') {   # *.deleting: an earlier install renamed it but could not finish deleting
        $doomed = "$($dir.FullName).deleting"
        try { Rename-Item -LiteralPath $dir.FullName -NewName "$($dir.Name).deleting" -ErrorAction Stop }
        catch {
            Write-Host "Kept old copy $($dir.FullName) (in use, e.g. by a running session; removed by a later install)"
            continue
        }
    }
    try {
        Remove-Item -LiteralPath $doomed -Recurse -Force -ErrorAction Stop
        Write-Host "Removed old copy $($dir.FullName)"
    }
    catch { Write-Warning "Could not fully remove $doomed ($($_.Exception.Message)); a later install retries." }
}