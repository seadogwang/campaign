<#
.SYNOPSIS
    Runs the environment-gated real-PostgreSQL readiness verification.

.DESCRIPTION
    This is the one entry point for the LOCAL_POSTGRESQL_INTEGRATION / LOCAL_ORDERED_MIGRATIONS evidence. It
    never substitutes an engine:

      * with no connection settings it prints "SKIPPED/BLOCKED: real PostgreSQL not configured", writes no
        report and emits no passing readiness evidence;
      * with connection settings it runs the gated Maven verification
        (ReadinessPostgresIntegrationTest) against the real engine. That run applies the committed V1-V3 assets
        with real Flyway into the configured dedicated schema and proves, through a real JdbcReadinessStore:
        append, idempotent replay, conflicting-identity refusal, a stale revision that writes nothing,
        assessment persistence, evidence-plus-audit atomicity under a real constraint failure, and durability
        across a restart.

    Required environment variables when configured (never committed, never printed):

        CAMPAIGN_READINESS_POSTGRES_URL
        CAMPAIGN_READINESS_POSTGRES_USERNAME
        CAMPAIGN_READINESS_POSTGRES_PASSWORD

    Optional:

        CAMPAIGN_READINESS_POSTGRES_SCHEMA       dedicated schema (default campaign_readiness_t26_it)
        CAMPAIGN_READINESS_POSTGRES_ENVIRONMENT  LOCAL | CI | STAGING | PRODUCTION (default LOCAL)
        CAMPAIGN_READINESS_POSTGRES_ALLOW_CLEAN  "true" allows cleaning a non-standard dedicated schema
        CAMPAIGN_READINESS_POSTGRES_REPORT       report path inside the repository

    The report is bounded and hash-pinned, names POSTGRESQL as the engine that answered, and stays
    development evidence (productionGateEvidence=false) unless an operator explicitly supplied
    CAMPAIGN_READINESS_POSTGRES_ENVIRONMENT=PRODUCTION.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-ReadinessPostgres.ps1

.EXAMPLE
    $env:CAMPAIGN_READINESS_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/campaign'
    $env:CAMPAIGN_READINESS_POSTGRES_USERNAME = 'campaign'
    $env:CAMPAIGN_READINESS_POSTGRES_PASSWORD = '<operator-supplied>'
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-ReadinessPostgres.ps1
#>
[CmdletBinding()]
param(
    [string]$JdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7',

    [string]$MavenRepoLocal = 'C:/Users/WangL/.m2/repository',

    [string]$ReportPath = '',

    [ValidateRange(5, 120)]
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "[readiness-postgres] $Message"
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory = Join-Path $repositoryRoot '.automation\runtime'
$logDirectory = Join-Path $runtimeDirectory 'logs'

$url = $env:CAMPAIGN_READINESS_POSTGRES_URL
if ([string]::IsNullOrWhiteSpace($url)) {
    Write-Host 'SKIPPED/BLOCKED: real PostgreSQL not configured'
    Write-Step 'Absent CAMPAIGN_READINESS_POSTGRES_URL. No report was written and no readiness evidence was produced.'
    Write-Step 'This is an explicit blocker, not a pass: LOCAL_POSTGRESQL_INTEGRATION and LOCAL_ORDERED_MIGRATIONS stay unsatisfied.'
    return
}

foreach ($requiredVariable in @(
        'CAMPAIGN_READINESS_POSTGRES_USERNAME',
        'CAMPAIGN_READINESS_POSTGRES_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredVariable))) {
        Write-Host "BLOCKED: real PostgreSQL is configured but $requiredVariable is absent"
        Write-Step 'The verification refuses to run with an incomplete connection configuration.'
        return
    }
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $env:CAMPAIGN_READINESS_POSTGRES_REPORT = $ReportPath
}

$javaExecutable = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Required JDK 21 was not found: $JdkHome"
}

$maven = Join-Path $repositoryRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
    $mavenCommand = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if ($null -eq $mavenCommand) {
        throw 'Neither mvnw.cmd nor mvn.cmd is available.'
    }
    $maven = $mavenCommand.Source
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$outLog = Join-Path $logDirectory 'readiness-postgres.out.log'
$errLog = Join-Path $logDirectory 'readiness-postgres.err.log'

Write-Step "Running the gated real-PostgreSQL verification (schema: $($env:CAMPAIGN_READINESS_POSTGRES_SCHEMA))"
Write-Step 'Credentials and the JDBC URL are read from the environment and are never printed or written.'

$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:Path
$exitCode = 1
try {
    $env:JAVA_HOME = $JdkHome
    $env:Path = (Join-Path $JdkHome 'bin') + ';' + $savedPath
    $arguments = @(
        '-q',
        "-Dmaven.repo.local=$MavenRepoLocal",
        '-Dtest=ReadinessPostgresIntegrationTest',
        '-DfailIfNoTests=false',
        'test'
    )
    & $maven @arguments > $outLog 2> $errLog
    $exitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:Path = $savedPath
}

if ($exitCode -ne 0) {
    Write-Host "FAILED: the real PostgreSQL verification did not pass (exit code $exitCode)"
    Write-Step "See $outLog and $errLog. No readiness evidence is claimed."
    exit $exitCode
}

$reportFile = if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    Join-Path $runtimeDirectory 'readiness-postgres-report.json'
} else {
    [IO.Path]::GetFullPath($ReportPath)
}

if (-not (Test-Path -LiteralPath $reportFile -PathType Leaf)) {
    Write-Host 'FAILED: the verification passed but wrote no report'
    Write-Step "Expected a bounded hash-pinned report at $reportFile; no readiness evidence is claimed."
    exit 1
}

$report = Get-Content -LiteralPath $reportFile -Raw | ConvertFrom-Json
if ($report.engine -ne 'POSTGRESQL' -or [string]::IsNullOrWhiteSpace($report.reportHash)) {
    Write-Host 'FAILED: the report does not name PostgreSQL as the engine and pin itself by hash'
    exit 1
}

Write-Host 'PASSED: real PostgreSQL verification'
Write-Step "Engine        : $($report.engine) ($($report.serverVersion))"
Write-Step "Schema        : $($report.schema)"
Write-Step "Environment   : $($report.environment)"
Write-Step "Migrations    : $($report.migrationVersions -join ', ')"
Write-Step "Report hash   : $($report.reportHash)"
Write-Step "Report        : $reportFile"
Write-Step "Production Gate evidence: $($report.productionGateEvidence)"
if (-not $report.productionGateEvidence) {
    Write-Step 'This is development evidence for an external verification run; it is not production Gate evidence.'
}
Write-Step 'The verification proves the storage boundary only. Browser E2E, API E2E and every other LOCAL_READY requirement keep their own evidence.'