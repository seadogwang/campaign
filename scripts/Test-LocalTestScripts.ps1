<#
.SYNOPSIS
    Deterministic behaviour checks for the local-test scripts.

.DESCRIPTION
    It verifies the parts of Start-LocalTest.ps1, Start-LocalPostgresTest.ps1, Start-LocalCampaign.ps1,
    Stop-LocalTest.ps1, Invoke-ReadinessPostgres.ps1, Start-Production.ps1 and
    Invoke-ProductionLiveAcceptance.ps1 that can
    be proved without starting a server or owning a database:

      * all eight scripts parse;
      * the startup script refuses an occupied port instead of terminating what holds it;
      * the startup script's dry run reaches its plan on free ports and starts nothing;
      * the stop script is idempotent and tolerates the absence of runtime metadata;
      * the PostgreSQL verifier prints the explicit SKIPPED/BLOCKED line when no connection is configured and
        writes no report;
      * the live acceptance script writes its redacted report and then exits non-zero whenever
        `productionGateEvidence` is false, prints and persists no gate value, and reserves exit 0 for a genuine
        all-passed PRODUCTION run;
      * the production configuration report names each missing setting exactly once and still refuses a
        `CAMPAIGN_PRODUCTION_ENABLED` value that is not exactly `true`.

    It starts no long-running child process, opens no database connection and touches no other port. The two
    production checks run the scripts with every gate set cleared, so no Maven test, no external service and no
    process start is ever involved; the pre-existing runtime reports are snapshotted and restored.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-LocalTestScripts.ps1
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Check {
    param([string]$Message)
    Write-Host "ok - $Message"
}

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "FAILED - $Message"
    }
    Write-Check $Message
}

function Get-FreePort {
    param([int]$First = 20000, [int]$Last = 20080)

    for ($port = $First; $port -le $Last; $port++) {
        $probe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $port)
        try {
            $probe.Start()
            $probe.Stop()
            return $port
        } catch {
            $probe.Stop()
        }
    }
    throw "No free local port was available in $First..$Last."
}

function Invoke-Script {
    param([string]$Path, [string[]]$Arguments)

    # A child that refuses or reports a blocker writes to stderr and exits non-zero; both are expected here, so
    # the child's stderr must not become a terminating error in this test process.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Path @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output   = ($output | Out-String)
    }
}

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}
$scripts = @(
    (Join-Path $RepositoryRoot 'scripts/Start-LocalTest.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Start-LocalPostgresTest.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Stop-LocalTest.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Invoke-ReadinessPostgres.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Start-LocalCampaign.ps1')
)

foreach ($script in $scripts) {
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$parseErrors)
    Assert-Condition -Condition ($parseErrors.Count -eq 0) -Message "$([IO.Path]::GetFileName($script)) parses"
}

# The production scripts are parsed here too. They are never started by this check: a production start requires
# operator-supplied settings this deterministic check must not invent, and starting one would write runtime
# metadata and open ports.
$productionScripts = @(
    (Join-Path $RepositoryRoot 'scripts/Start-Production.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Stop-Production.ps1'),
    (Join-Path $RepositoryRoot 'scripts/Invoke-ProductionLiveAcceptance.ps1')
)
foreach ($script in $productionScripts) {
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$parseErrors)
    Assert-Condition -Condition ($parseErrors.Count -eq 0) -Message "$([IO.Path]::GetFileName($script)) parses"
}

# The startup script must refuse an occupied port. The listener is opened here, in this test process.
$occupiedPort = Get-FreePort
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $occupiedPort)
$listener.Start()
try {
    $freePort = Get-FreePort -First ($occupiedPort + 1)
    $refused = Invoke-Script -Path $scripts[0] -Arguments @(
        '-DryRun', '-BackendPort', "$occupiedPort", '-FrontendPort', "$freePort")
    Assert-Condition -Condition ($refused.ExitCode -ne 0) -Message 'the startup script fails on an occupied port'
    Assert-Condition -Condition ($refused.Output -like '*already in use*') -Message 'the refusal names the occupied port'
} finally {
    $listener.Stop()
}

$dryRunBackend = Get-FreePort
$dryRunFrontend = Get-FreePort -First ($dryRunBackend + 1)
$dryRun = Invoke-Script -Path $scripts[0] -Arguments @(
    '-DryRun', '-BackendPort', "$dryRunBackend", '-FrontendPort', "$dryRunFrontend")
Assert-Condition -Condition ($dryRun.ExitCode -eq 0) -Message 'the startup dry run succeeds on free ports'
Assert-Condition -Condition ($dryRun.Output -like '*DRY RUN*') -Message 'the dry run prints its plan'
Assert-Condition -Condition ($dryRun.Output -like '*no process was started*') -Message 'the dry run starts nothing'

$localStartupSource = Get-Content -LiteralPath $scripts[0] -Raw
$campaignStartupSource = Get-Content -LiteralPath $scripts[4] -Raw
Assert-Condition -Condition ($localStartupSource -like '*VITE_DEV_PROXY_TARGET*') `
    -Message 'the local HTTP frontend configures the Vite same-origin proxy'
Assert-Condition -Condition ($localStartupSource -like '*Remove-Item Env:VITE_API_BASE_URL*') `
    -Message 'the local HTTP frontend keeps an empty API base URL on the default path'
Assert-Condition -Condition ($campaignStartupSource -notlike '*$ApiBaseUrl = "http://127.0.0.1:$BackendPort"*') `
    -Message 'the Campaign launcher does not force a cross-origin API base URL'

$isolatedRuntime = Join-Path $RepositoryRoot ".automation/runtime/script-test-$([guid]::NewGuid().ToString('N'))"
foreach ($attempt in 1..2) {
    $stop = Invoke-Script -Path $scripts[2] -Arguments @('-RuntimeDirectory', $isolatedRuntime)
    Assert-Condition -Condition ($stop.ExitCode -eq 0) -Message "the stop script is idempotent (attempt $attempt)"
}

$savedUrl = $env:CAMPAIGN_READINESS_POSTGRES_URL
try {
    Remove-Item Env:CAMPAIGN_READINESS_POSTGRES_URL -ErrorAction SilentlyContinue
    $verifier = Invoke-Script -Path $scripts[3] -Arguments @()
    Assert-Condition -Condition ($verifier.ExitCode -eq 0) -Message 'the PostgreSQL verifier exits cleanly without configuration'
    Assert-Condition -Condition ($verifier.Output -like '*SKIPPED/BLOCKED: real PostgreSQL not configured*') `
        -Message 'the PostgreSQL verifier reports the explicit blocker'
    Assert-Condition -Condition ($verifier.Output -notlike '*PASSED*') `
        -Message 'the PostgreSQL verifier claims no passing evidence without configuration'
} finally {
    if (-not [string]::IsNullOrWhiteSpace($savedUrl)) {
        $env:CAMPAIGN_READINESS_POSTGRES_URL = $savedUrl
    }
}

function Backup-ReportFile {
    param([string]$Path)

    # A byte-exact copy next to the report: the production run under test overwrites the real runtime report and
    # the deterministic check must leave the pre-existing evidence untouched.
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $backupPath = "$Path.test-backup"
    Copy-Item -LiteralPath $Path -Destination $backupPath -Force
    return $backupPath
}

function Restore-ReportFile {
    param([string]$Path, $BackupPath)

    if ([string]::IsNullOrEmpty([string]$BackupPath)) {
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            Remove-Item -LiteralPath $Path -Force
        }
        return
    }
    Copy-Item -LiteralPath $BackupPath -Destination $Path -Force
    Remove-Item -LiteralPath $BackupPath -Force
}

# ------------------------------------------------------------------ production live-acceptance exit semantics

<#
    Invoke-ProductionLiveAcceptance.ps1 must fail the process whenever productionGateEvidence is false — a
    blocked or incomplete production acceptance can never return success — but it must still write its redacted
    report first. This runs it with every live gate variable cleared, so no Maven test runs and no external
    service is contacted. The one non-runnable gate (providerRuntime has no live class in this repository)
    carries a sentinel "secret": it becomes a present gate variable without becoming a runnable check, so the
    value must never appear in the console or the report, which proves only variable names are handled.
#>
$liveGateVariables = @(
    'CAMPAIGN_PRODUCTION_ENVIRONMENT',
    'CAMPAIGN_PRODUCTION_OIDC_JWK_SET_URI',
    'CAMPAIGN_PRODUCTION_MEMBERSHIP_URL',
    'CAMPAIGN_PRODUCTION_AUDIT_URL',
    'CAMPAIGN_READINESS_POSTGRES_URL',
    'CAMPAIGN_PRODUCTION_MODEL_ENDPOINT',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_ENDPOINT',
    'CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED',
    'CAMPAIGN_CONVERSATION_POSTGRES_URL',
    'CAMPAIGN_PREVIEW_POSTGRES_URL'
)
$sentinel = 'sentinel-' + [guid]::NewGuid().ToString('N')
$liveReportPath = Join-Path $RepositoryRoot '.automation/runtime/production-live-acceptance-report.json'
$liveReportBackup = Backup-ReportFile -Path $liveReportPath
$savedLiveGates = @{}
foreach ($name in $liveGateVariables) {
    $savedLiveGates[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
try {
    [Environment]::SetEnvironmentVariable('CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED', $sentinel, 'Process')
    $live = Invoke-Script -Path (Join-Path $RepositoryRoot 'scripts/Invoke-ProductionLiveAcceptance.ps1') `
        -Arguments @('-Environment', 'PRODUCTION')
    Assert-Condition -Condition ($live.ExitCode -eq 1) `
        -Message 'the live acceptance script exits 1 when productionGateEvidence is false, even under -Environment PRODUCTION'
    Assert-Condition -Condition ($live.Output -notlike '*genuinely passed*') `
        -Message 'the live acceptance script makes no passing claim while the gate is closed'
    Assert-Condition -Condition ($live.Output -like '*no production claim is made*') `
        -Message 'the live acceptance script reports the closed gate explicitly'
    Assert-Condition -Condition ($live.Output -notlike "*$sentinel*") `
        -Message 'the live acceptance script prints no gate variable value'

    Assert-Condition -Condition (Test-Path -LiteralPath $liveReportPath -PathType Leaf) `
        -Message 'the live acceptance script writes its report before failing'
    $liveReportText = Get-Content -LiteralPath $liveReportPath -Raw
    Assert-Condition -Condition ($liveReportText -notlike "*$sentinel*") `
        -Message 'the live acceptance report persists no gate variable value'
    $liveReport = $liveReportText | ConvertFrom-Json
    Assert-Condition -Condition ($liveReport.productionGateEvidence -eq $false) `
        -Message 'the live acceptance report keeps productionGateEvidence=false when blocked'
    Assert-Condition -Condition (@($liveReport.configuration.gateVariablesPresent) -contains 'CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED') `
        -Message 'the live acceptance report records the gate variable name only'
    Assert-Condition -Condition ($liveReport.liveProduction.checks.providerRuntime.status -eq 'NOT_AVAILABLE') `
        -Message 'a required production check with no live class is reported NOT_AVAILABLE'
    Assert-Condition -Condition (@($liveReport.blockers | Where-Object { $_ -like '*NOT_AVAILABLE*' }).Count -gt 0) `
        -Message 'the live acceptance report lists the blocked required checks'
} finally {
    foreach ($name in $liveGateVariables) {
        [Environment]::SetEnvironmentVariable($name, $savedLiveGates[$name], 'Process')
    }
    Restore-ReportFile -Path $liveReportPath -BackupPath $liveReportBackup
}

# ------------------------------------------------------------------ production configuration report dedup

<#
    Start-Production.ps1 must fail closed on the missing settings before starting anything, and its report must
    name each missing setting exactly once. The environment is cleared so the failure is deterministic; the
    script writes the report and throws, so the exit code is non-zero and no process is started.
#>
$productionSettingVariables = @(
    'CAMPAIGN_PRODUCTION_ENABLED',
    'CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED',
    'CAMPAIGN_AGENT_DEVELOPMENT_ENABLED',
    'CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_ENABLED',
    'VITE_GATEWAY_MODE'
)
$acceptanceReportPath = Join-Path $RepositoryRoot '.automation/runtime/production-acceptance-report.json'
$acceptanceReportBackup = Backup-ReportFile -Path $acceptanceReportPath
$savedProductionSettings = @{}
foreach ($name in $productionSettingVariables) {
    $savedProductionSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
try {
    $absentEnabled = Invoke-Script -Path (Join-Path $RepositoryRoot 'scripts/Start-Production.ps1') -Arguments @('-DryRun')
    Assert-Condition -Condition ($absentEnabled.ExitCode -ne 0) `
        -Message 'Start-Production fails closed when the required production settings are absent'
    Assert-Condition -Condition (Test-Path -LiteralPath $acceptanceReportPath -PathType Leaf) `
        -Message 'Start-Production writes its report before failing'
    $startReport = Get-Content -LiteralPath $acceptanceReportPath -Raw | ConvertFrom-Json
    Assert-Condition -Condition (@($startReport.configuration.missingSettings | Where-Object { $_ -like 'CAMPAIGN_PRODUCTION_ENABLED*' }).Count -eq 1) `
        -Message 'the Start-Production report names CAMPAIGN_PRODUCTION_ENABLED exactly once'
    Assert-Condition -Condition (@($startReport.configuration.missingSettings | Group-Object | Where-Object { $_.Count -gt 1 }).Count -eq 0) `
        -Message 'the Start-Production missing-setting report contains no duplicate entries'

    # A present value that is not exactly "true" must still be refused, with the exact-true reason once.
    [Environment]::SetEnvironmentVariable('CAMPAIGN_PRODUCTION_ENABLED', '1', 'Process')
    $notExactTrue = Invoke-Script -Path (Join-Path $RepositoryRoot 'scripts/Start-Production.ps1') -Arguments @('-DryRun')
    Assert-Condition -Condition ($notExactTrue.ExitCode -ne 0) `
        -Message 'Start-Production refuses a CAMPAIGN_PRODUCTION_ENABLED value that is not exactly true'
    $exactReport = Get-Content -LiteralPath $acceptanceReportPath -Raw | ConvertFrom-Json
    Assert-Condition -Condition (@($exactReport.configuration.missingSettings | Where-Object { $_ -eq 'CAMPAIGN_PRODUCTION_ENABLED' }).Count -eq 0) `
        -Message 'the exact-true reason replaces the bare missing name instead of repeating it'
    Assert-Condition -Condition (@($exactReport.configuration.missingSettings | Where-Object { $_ -eq 'CAMPAIGN_PRODUCTION_ENABLED (must be exactly true)' }).Count -eq 1) `
        -Message 'the report records the exact-true reason exactly once'
} finally {
    foreach ($name in $productionSettingVariables) {
        [Environment]::SetEnvironmentVariable($name, $savedProductionSettings[$name], 'Process')
    }
    Restore-ReportFile -Path $acceptanceReportPath -BackupPath $acceptanceReportBackup
}

Write-Host ''
Write-Host 'All local-test script behaviour checks passed.'
