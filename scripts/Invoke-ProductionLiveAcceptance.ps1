<#
.SYNOPSIS
    Runs the environment-gated production live acceptance set and writes a redacted evidence report.

.DESCRIPTION
    This is the only entry point for genuine live production evidence. It runs the environment-gated Maven tests
    for OIDC, PostgreSQL membership/readiness/audit, the production model and the production Message/Reward
    Providers, plus the development conversation-store and development Preview checks for local diagnostics, then
    reads their Surefire reports and writes .automation/runtime/production-live-acceptance-report.json.

    The report separates four things and never conflates them:

      * configuration        — which gate variables were present (names only, never a value);
      * localIntegration     — the literal Maven test counts for every runnable class;
      * liveProduction       — PASSED only when a required PRODUCTION class genuinely ran with zero skips,
                               failures and errors;
      * developmentDiagnostics — development-only classes (the development conversation store and development
                               Preview) reported for local completeness and never counted as production evidence.

    `productionGateEvidence` is true only when every required production check PASSED, the environment is
    explicitly PRODUCTION, and the Provider Runtime check has a real live test class. This repository ships no
    durable Runtime/Action store and therefore no live Runtime host test, so the honest value here remains false.
    A development package or test can never make this value true: the development conversation store and the
    development Preview are excluded from the required production set.

    The process exit code follows the same value: the script writes the redacted report first, then exits 0 only
    when `productionGateEvidence` is true. Every closed gate — no runnable check, a missing required check, a
    skipped test, a Maven failure, or any BLOCKED/FAILED/NOT_AVAILABLE status — exits non-zero, so a blocked
    production acceptance can never look successful to CI or an operator.

.PARAMETER Environment
    LOCAL (default), CI, STAGING or PRODUCTION. Only PRODUCTION may ever set productionGateEvidence=true.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-ProductionLiveAcceptance.ps1

.EXAMPLE
    $env:CAMPAIGN_PRODUCTION_ENVIRONMENT = 'STAGING'
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-ProductionLiveAcceptance.ps1 -Environment STAGING
#>
[CmdletBinding()]
param(
    [string]$JdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7',

    [string]$MavenRepoLocal = 'C:/Users/WangL/.m2/repository',

    [ValidateSet('LOCAL', 'CI', 'STAGING', 'PRODUCTION')]
    [string]$Environment = 'LOCAL',

    [ValidateRange(5, 240)]
    [int]$TimeoutMinutes = 60
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "[production-live] $Message"
}

$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$RuntimeDirectory = Join-Path $RepositoryRoot '.automation\runtime'
$LogDirectory = Join-Path $RuntimeDirectory 'logs'
$ReportDirectory = Join-Path $RepositoryRoot 'target\surefire-reports'
$ReportPath = Join-Path $RuntimeDirectory 'production-live-acceptance-report.json'

<#
    The live acceptance set. Every check names the class it runs and the gate variable that class itself reads.
    Only a check marked `production = $true` may contribute to `productionGateEvidence`; a development check is
    reported for local completeness and can never be production evidence. A check with no gate variable present
    cannot run; a check whose class is empty has no live test in this repository at all. Only names are printed.

    The gate variable of a verifier is deliberately not the application's own configuration variable: the audit
    verifier reads `CAMPAIGN_PRODUCTION_AUDIT_URL` (its own isolated schema), while `Start-Production.ps1`
    configures the application ledger with `CAMPAIGN_PRODUCTION_AUDIT_LEDGER_URL`, and the development
    conversation-store verifier reads `CAMPAIGN_CONVERSATION_POSTGRES_URL` (a development variable), not the
    production host store variable `CAMPAIGN_PRODUCTION_CAMPAIGN_AGENT_STORE_URL`.
#>
$Checks = @(
    # --- required production checks -------------------------------------------------------------------------
    @{ name = 'oidc';                     production = $true;  class = 'com.company.campaign.production.security.ProductionOidcLiveIntegrationTest';             gate = 'CAMPAIGN_PRODUCTION_OIDC_JWK_SET_URI'; note = '' },
    @{ name = 'membershipPostgresql';     production = $true;  class = 'com.company.campaign.production.security.ProductionMembershipPostgresLiveIntegrationTest'; gate = 'CAMPAIGN_PRODUCTION_MEMBERSHIP_URL'; note = '' },
    @{ name = 'auditPostgresql';          production = $true;  class = 'com.company.campaign.production.audit.ProductionAuditPostgresLiveIntegrationTest';       gate = 'CAMPAIGN_PRODUCTION_AUDIT_URL'; note = 'The verifier-owned audit PostgreSQL gate; it is separate from the application ledger variable CAMPAIGN_PRODUCTION_AUDIT_LEDGER_URL that Start-Production.ps1 requires.' },
    @{ name = 'readinessPostgresql';      production = $true;  class = 'com.company.campaign.readiness.postgres.ReadinessPostgresIntegrationTest';               gate = 'CAMPAIGN_READINESS_POSTGRES_URL'; note = '' },
    @{ name = 'model';                    production = $true;  class = 'com.company.campaign.production.model.ProductionModelLiveContractTest';                  gate = 'CAMPAIGN_PRODUCTION_MODEL_ENDPOINT'; note = '' },
    @{ name = 'provider';                 production = $true;  class = 'com.company.campaign.production.provider.ProductionProviderLiveContractTest';            gate = 'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_ENDPOINT'; note = '' },
    @{ name = 'providerRuntime';          production = $true;  class = ''; gate = 'CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED'; note = 'No live Runtime host test exists in this repository; host-supplied durable Runtime/Action stores are required.' },

    # --- development-only checks: reported, never counted as production evidence ----------------------------
    @{ name = 'developmentConversationStore';      production = $false; class = 'com.company.campaign.conversation.postgres.ConversationPostgresIntegrationTest';          gate = 'CAMPAIGN_CONVERSATION_POSTGRES_URL'; note = 'Development conversation store (campaign.agent.development.store), NOT the production host store CAMPAIGN_PRODUCTION_CAMPAIGN_AGENT_STORE_*; development evidence only.' },
    @{ name = 'developmentPreviewPostgresql';      production = $false; class = 'com.company.campaign.dev.preview.postgres.PreviewPostgresIntegrationTest';                gate = 'CAMPAIGN_PREVIEW_POSTGRES_URL'; note = 'Development Preview (campaign.agent.development.preview.postgres); development evidence only.' },
    @{ name = 'developmentPreviewHttpEndToEnd';    production = $false; class = 'com.company.campaign.dev.preview.postgres.EnabledPreviewPostgresHttpEndToEndTest';         gate = 'CAMPAIGN_PREVIEW_POSTGRES_URL'; note = 'Development Preview HTTP end-to-end; development evidence only.' }
)

$ProductionChecks = @($Checks | Where-Object { $_.production })
$DevelopmentChecks = @($Checks | Where-Object { -not $_.production })

function Get-ProcessVariable {
    param([string]$Name)
    return [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Get-SurefireResult {
    param([string]$Class)

    $path = Join-Path $ReportDirectory "TEST-$Class.xml"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $null
    }
    try {
        [xml]$xml = Get-Content -LiteralPath $path -Raw
    } catch {
        return $null
    }
    $suite = $xml.testsuite
    return [ordered]@{
        tests    = [int]$suite.tests
        skipped  = [int]$suite.skipped
        failures = [int]$suite.failures
        errors   = [int]$suite.errors
    }
}

function Get-CheckStatus {
    param($Check, $Result)

    if ([string]::IsNullOrWhiteSpace([string]$Check.class)) {
        return 'NOT_AVAILABLE'
    }
    if ([string]::IsNullOrWhiteSpace((Get-ProcessVariable ([string]$Check.gate)))) {
        return 'BLOCKED'
    }
    if ($null -eq $Result) {
        return 'BLOCKED'
    }
    if ($Result.tests -gt 0 -and $Result.skipped -eq 0 -and $Result.failures -eq 0 -and $Result.errors -eq 0) {
        return 'PASSED'
    }
    return 'FAILED'
}

New-Item -ItemType Directory -Force -Path $RuntimeDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null

$observedAt = (Get-Date).ToUniversalTime().ToString('o')

$runnable = @($Checks | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.class) -and
    -not [string]::IsNullOrWhiteSpace((Get-ProcessVariable ([string]$_.gate))) })

$mavenExit = $null
$mavenError = $null
if ($runnable.Count -eq 0) {
    Write-Step 'SKIPPED/BLOCKED: no live gate variable is configured; no test was run.'
} else {
    $testFilter = ($runnable | ForEach-Object { $_.class }) -join ','
    $maven = Join-Path $RepositoryRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        $mavenCommand = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
        if ($null -eq $mavenCommand) {
            # An un-runnable acceptance is never a pass: the redacted report is still written below and the
            # process still fails. The report, not an early exception, is what an operator reads.
            $maven = $null
        } else {
            $maven = $mavenCommand.Source
        }
    }

    if ($null -eq $maven) {
        $mavenError = 'Neither mvnw.cmd nor mvn.cmd is available to run the live acceptance set.'
        Write-Step "SKIPPED/BLOCKED: $mavenError"
    } else {
        Write-Step "Running $($runnable.Count) environment-gated live test class(es) under JDK 21."
        $savedJavaHome = $env:JAVA_HOME
        $savedPath = $env:Path
        $savedEnvironment = $env:CAMPAIGN_PRODUCTION_ENVIRONMENT
        try {
            $env:JAVA_HOME = $JdkHome
            $env:Path = (Join-Path $JdkHome 'bin') + ';' + $savedPath
            $env:CAMPAIGN_PRODUCTION_ENVIRONMENT = $Environment
            & $maven -q "-Dmaven.repo.local=$MavenRepoLocal" `
                "-Dtest=$testFilter" -DfailIfNoTests=false test
            $mavenExit = $LASTEXITCODE
        } finally {
            $env:JAVA_HOME = $savedJavaHome
            $env:Path = $savedPath
            $env:CAMPAIGN_PRODUCTION_ENVIRONMENT = $savedEnvironment
        }
    }
}

$results = [ordered]@{}
$developmentResults = [ordered]@{}
$allProductionPassed = $true
foreach ($check in $Checks) {
    $result = if ([string]::IsNullOrWhiteSpace([string]$check.class)) { $null } else { Get-SurefireResult ([string]$check.class) }
    $status = Get-CheckStatus $check $result
    $entry = [ordered]@{
        status = $status
        gate   = [string]$check.gate
        class  = [string]$check.class
        note   = [string]$check.note
        counts = $result
    }
    if ($check.production) {
        $results[[string]$check.name] = $entry
        if ($status -ne 'PASSED') {
            $allProductionPassed = $false
        }
    } else {
        $developmentResults[[string]$check.name] = $entry
    }
}

# Only production checks, an explicit PRODUCTION environment and a genuinely passing Maven run can ever set the
# gate. A development check never contributes to it, and a required production check that is BLOCKED, FAILED or
# NOT_AVAILABLE (such as the live Runtime host, which this repository does not ship) keeps it false.
$productionGateEvidence = $allProductionPassed -and $Environment -eq 'PRODUCTION' -and $mavenExit -eq 0

$blockers = [System.Collections.Generic.List[string]]::new()
foreach ($check in $ProductionChecks) {
    $status = $results[[string]$check.name].status
    if ($status -ne 'PASSED') {
        $blockers.Add("$($check.name): $status")
    }
}
if (-not [string]::IsNullOrEmpty([string]$mavenError)) {
    $blockers.Add("The live acceptance Maven run could not be executed: $mavenError")
}
if (-not $productionGateEvidence) {
    $blockers.Add('productionGateEvidence requires every required production check PASSED, -Environment PRODUCTION, and a passing Maven run; that has not been genuinely achieved.')
}
if ($DevelopmentChecks.Count -gt 0) {
    $blockers.Add('The development conversation-store and development Preview checks are diagnostic only and can never count as production evidence.')
}

$report = [ordered]@{
    reportVersion = 'campaign-production-live-acceptance/1.0'
    observedAt    = $observedAt
    environment   = $Environment
    configuration = [ordered]@{
        gateVariablesPresent = @($Checks | Where-Object { -not [string]::IsNullOrWhiteSpace((Get-ProcessVariable ([string]$_.gate))) } | ForEach-Object { [string]$_.gate })
        javaHome             = $JdkHome
    }
    localIntegration = [ordered]@{
        mavenExitCode = $mavenExit
        runnableClasses = $runnable.Count
        logs = 'redacted; see .automation/runtime/logs'
    }
    liveProduction = [ordered]@{
        checks = $results
        allRequiredPassed = $allProductionPassed
    }
    developmentDiagnostics = [ordered]@{
        countedAsProductionEvidence = $false
        checks = $developmentResults
    }
    productionGateEvidence = $productionGateEvidence
    blockers = @($blockers)
}

$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
Write-Step "Live acceptance report written to $ReportPath (productionGateEvidence=$($productionGateEvidence.ToString().ToLowerInvariant()))."

# The process exit code is part of the contract: CI and an operator must treat any closed gate as a failure.
# The report above is already written, so a failing run is still auditable. Exit 0 is reserved for a genuine
# all-passed PRODUCTION run; no runnable check, a missing required check, a skip, a Maven failure or any
# BLOCKED/FAILED/NOT_AVAILABLE status exits non-zero.
if ($productionGateEvidence) {
    Write-Step 'PASSED: the required live production acceptance set genuinely passed.'
    exit 0
}
Write-Step 'SKIPPED/BLOCKED: the required live production acceptance set did not fully pass; no production claim is made.'
exit 1