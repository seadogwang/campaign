<#
.SYNOPSIS
    Starts the production-shaped Campaign Agent environment, fail-closed.

.DESCRIPTION
    One command that starts both halves of the real production path on loopback:

      * the backend from the packaged application JAR under JDK 21, with every production setting read only from
        the current process environment; and
      * the frontend Vite server with `VITE_GATEWAY_MODE=http`, so the user-facing conversation UI is the only
        surface and no fixture, mock or internal workbench path is reachable.

    The script is deliberately fail-closed and conservative:

      * it requires every production setting the deployment owns to be present, and refuses to start when any is
        absent — it prints the variable NAMES that are missing, never a value or a credential;
      * it refuses to start when the development Campaign Agent host is enabled, when any fixture/development
        substitute is selected, or when the frontend is not explicitly in HTTP gateway mode;
      * it refuses to start the Provider Runtime host unless the operator explicitly acknowledges that
        host-supplied durable Runtime/Action stores are required, because this repository ships no such durable
        store and the host would otherwise fail closed at startup;
      * it refuses an occupied port instead of terminating whatever holds it;
      * it launches every child hidden, writes bounded non-secret logs and PID metadata under
        .automation/runtime, waits with a bounded timeout for backend health and frontend HTML, and on failure
        stops only the processes it started itself;
      * it writes a redacted, machine-readable acceptance report that separates configuration, local integration
        evidence and genuine live production evidence. `productionGateEvidence` is always false here: this
        script never invents a live OIDC, model, Provider or Runtime proof. Run
        scripts\Invoke-ProductionLiveAcceptance.ps1 to attempt the live set.

.PARAMETER AllowProviderRuntime
    Explicitly allow enabling the Provider Runtime host. It still requires host-supplied durable
    Runtime/Action stores; without them the backend fails closed at startup with a bounded code.

.EXAMPLE
    # Export the production settings in this shell first (never in the repository), then:
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Production.ps1 -Build

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Production.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$JdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7',

    [ValidateRange(1, 65535)]
    [int]$BackendPort = 8080,

    [ValidateRange(1, 65535)]
    [int]$FrontendPort = 5173,

    [string]$ApiBaseUrl = 'http://127.0.0.1:8080',

    [string]$CampaignId = 'campaign-1',

    [string]$MavenRepoLocal = 'C:/Users/WangL/.m2/repository',

    [switch]$Build,

    [switch]$AllowProviderRuntime,

    [ValidateRange(5, 600)]
    [int]$TimeoutMinutes = 5,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "[production] $Message"
}

function Get-RepositoryRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

$RepositoryRoot = Get-RepositoryRoot
$RuntimeDirectory = Join-Path $RepositoryRoot '.automation\runtime'
$LogDirectory = Join-Path $RuntimeDirectory 'logs'

# ------------------------------------------------------------------ required / forbidden settings

<#
    The production settings this deployment owns. Every entry is required to be present and non-blank. Only the
    NAMES are ever printed: no value, credential, URL, issuer, host, model id or schema is echoed.
#>
$RequiredSettings = @(
    'CAMPAIGN_PRODUCTION_ENABLED',
    'CAMPAIGN_PRODUCTION_JWT_ISSUER_URI',
    'CAMPAIGN_PRODUCTION_JWT_JWK_SET_URI',
    'CAMPAIGN_PRODUCTION_JWT_AUDIENCE',
    'CAMPAIGN_PRODUCTION_JWT_ALLOWED_ALGORITHMS',
    'CAMPAIGN_PRODUCTION_JWT_CLOCK_SKEW',
    'CAMPAIGN_PRODUCTION_JWT_MAX_ISSUED_AT_AGE',
    'CAMPAIGN_PRODUCTION_MEMBERSHIP_MODE',
    'CAMPAIGN_DATASOURCE_ENABLED',
    'CAMPAIGN_DATASOURCE_URL',
    'CAMPAIGN_DATASOURCE_USERNAME',
    'CAMPAIGN_DATASOURCE_PASSWORD',
    'CAMPAIGN_DATASOURCE_SCHEMA',
    'CAMPAIGN_PRODUCTION_AUDIT_ENABLED',
    'CAMPAIGN_PRODUCTION_MODEL_ENABLED',
    'CAMPAIGN_PRODUCTION_MODEL_ENDPOINT',
    'CAMPAIGN_PRODUCTION_MODEL_ALLOWED_HOSTS',
    'CAMPAIGN_PRODUCTION_MODEL_MODEL_ID',
    'CAMPAIGN_PRODUCTION_MODEL_API_VERSION',
    'CAMPAIGN_PRODUCTION_MODEL_CREDENTIAL',
    'CAMPAIGN_PRODUCTION_CAMPAIGN_AGENT_ENABLED',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_ENABLED',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_ENDPOINT',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_ALLOWED_HOSTS',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_PROVIDER_ID',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_OWNER_TENANT_ID',
    'CAMPAIGN_PRODUCTION_PROVIDER_MESSAGE_CREDENTIAL',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_ENABLED',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_ENDPOINT',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_ALLOWED_HOSTS',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_PROVIDER_ID',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_OWNER_TENANT_ID',
    'CAMPAIGN_PRODUCTION_PROVIDER_REWARD_CREDENTIAL'
)

function Get-ProcessVariable {
    param([string]$Name)
    return [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Test-Truthy {
    param([string]$Value)
    return -not [string]::IsNullOrWhiteSpace($Value) -and $Value.Trim().ToLowerInvariant() -eq 'true'
}

function Get-MissingSettings {
    $missing = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $RequiredSettings) {
        if ([string]::IsNullOrWhiteSpace((Get-ProcessVariable $name))) {
            $missing.Add($name)
        }
    }
    # Membership, audit and Campaign Agent persistence share CAMPAIGN_DATASOURCE_*.
    # Live verifiers keep their own isolated gate variables and schemas.
    return ,$missing
}

function Get-ForbiddenSettings {
    $violations = [System.Collections.Generic.List[string]]::new()
    if (Test-Truthy (Get-ProcessVariable 'CAMPAIGN_AGENT_DEVELOPMENT_ENABLED')) {
        $violations.Add('CAMPAIGN_AGENT_DEVELOPMENT_ENABLED=true (development host)')
    }
    if (Test-Truthy (Get-ProcessVariable 'CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_ENABLED')) {
        $violations.Add('CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_ENABLED=true (development Preview)')
    }
    $gateway = Get-ProcessVariable 'VITE_GATEWAY_MODE'
    if (-not [string]::IsNullOrWhiteSpace($gateway) -and $gateway -ne 'http') {
        $violations.Add("VITE_GATEWAY_MODE=$gateway (must be http)")
    }
    return ,$violations
}

# ------------------------------------------------------------------ helpers

function Test-LoopbackPortOccupied {
    param([int]$Port)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(400)) {
            return $false
        }
        $client.EndConnect($pending)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-ApplicationJar {
    param([string]$Root)

    $target = Join-Path $Root 'target'
    if (-not (Test-Path -LiteralPath $target -PathType Container)) {
        return $null
    }
    $candidate = Get-ChildItem -LiteralPath $target -Filter 'campaign-ai-builder-*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-sources.jar' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $candidate) {
        return $null
    }
    return $candidate.FullName
}

function Invoke-PackagedBuild {
    param([string]$Root, [string]$Jdk, [string]$Repo)

    $maven = Join-Path $Root 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        $mavenCommand = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
        if ($null -eq $mavenCommand) {
            throw 'Neither mvnw.cmd nor mvn.cmd is available to build the application JAR.'
        }
        $maven = $mavenCommand.Source
    }

    Write-Step 'Packaging the application JAR (mvn -DskipTests package).'
    $savedJavaHome = $env:JAVA_HOME
    $savedPath = $env:Path
    try {
        $env:JAVA_HOME = $Jdk
        $env:Path = (Join-Path $Jdk 'bin') + ';' + $savedPath
        & $maven -q "-Dmaven.repo.local=$Repo" -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "The packaged build failed with exit code $LASTEXITCODE."
        }
    } finally {
        $env:JAVA_HOME = $savedJavaHome
        $env:Path = $savedPath
    }
}

function Get-ChildProcessId {
    param([int]$ParentId)

    try {
        return @(Get-CimInstance -ClassName Win32_Process -Filter "ParentProcessId=$ParentId" -ErrorAction Stop |
            Select-Object -ExpandProperty ProcessId)
    } catch {
        return @()
    }
}

function Stop-StartedProcess {
    param([int]$ProcessId)

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return
    }
    foreach ($child in Get-ChildProcessId -ParentId $ProcessId) {
        Stop-StartedProcess -ProcessId $child
    }
    try {
        Stop-Process -Id $ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Step "A started process ($ProcessId) could not be stopped: it may already have exited."
    }
}

function Wait-ForHttp {
    param(
        [string]$Url,
        [string]$ExpectedFragment,
        [int]$TimeoutSeconds,
        [System.Diagnostics.Process[]]$TrackedProcesses,
        [string]$Label
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($tracked in $TrackedProcesses) {
            if ($tracked.HasExited) {
                throw "$Label exited before it answered on $Url (exit code $($tracked.ExitCode))."
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -like "*$ExpectedFragment*") {
                return
            }
        } catch {
            # Not listening yet; keep waiting inside the bounded window.
        }
        Start-Sleep -Milliseconds 750
    }
    throw "$Label did not answer on $Url within $TimeoutSeconds seconds."
}

function Repair-DuplicateProcessPath {
    $pathEntries = @(
        [Environment]::GetEnvironmentVariables('Process').GetEnumerator() |
            Where-Object { [string]$_.Key -ieq 'Path' }
    )
    if ($pathEntries.Count -le 1) {
        return
    }

    $canonicalEntry = $pathEntries | Where-Object { [string]$_.Key -ceq 'Path' } | Select-Object -First 1
    if ($null -eq $canonicalEntry) {
        $canonicalEntry = $pathEntries | Select-Object -First 1
    }
    $pathValue = [string]$canonicalEntry.Value
    foreach ($entry in $pathEntries) {
        if ([string]$entry.Key -cne 'Path') {
            [Environment]::SetEnvironmentVariable([string]$entry.Key, $null, 'Process')
        }
    }
    [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
}

<#
    The one bounded failure code a redacted log may carry. It never prints a value: only the first token that
    looks like a bounded production code is read back, and the log tail is otherwise not echoed.
#>
function Get-BoundedFailureCode {
    param([string]$LogPath)

    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return $null
    }
    $tail = Get-Content -LiteralPath $LogPath -Tail 200 -ErrorAction SilentlyContinue
    foreach ($line in $tail) {
        if ($line -match '(PRODUCTION_[A-Z0-9_]{3,}|CAMPAIGN_[A-Z0-9_]{3,})') {
            return $Matches[1]
        }
    }
    return $null
}

function Write-AcceptanceReport {
    param(
        [string]$ObservedAt,
        [bool]$HealthReached,
        $FailureCode,
        [string[]]$MissingSettings,
        [string[]]$ForbiddenSettings,
        [bool]$ProviderRuntimeRequested,
        [bool]$ProviderRuntimeAllowed,
        [string]$BackendLog,
        [string]$FrontendLog
    )

    $reportPath = Join-Path $RuntimeDirectory 'production-acceptance-report.json'
    $blockers = [System.Collections.Generic.List[string]]::new()
    if ($MissingSettings.Count -gt 0) {
        $blockers.Add('Missing required production settings: ' + ($MissingSettings -join ', '))
    }
    if ($ForbiddenSettings.Count -gt 0) {
        $blockers.Add('Forbidden development/fixture settings: ' + ($ForbiddenSettings -join ', '))
    }
    if (-not [string]::IsNullOrEmpty([string]$FailureCode)) {
        $blockers.Add("The backend refused to start with the bounded code $FailureCode.")
    }
    if (-not $HealthReached -and $MissingSettings.Count -eq 0 -and $ForbiddenSettings.Count -eq 0) {
        $blockers.Add('The backend health endpoint was not reached within the bounded window.')
    }
    $blockers.Add('A real OIDC issuer, a real production model endpoint, real Message and Reward Provider endpoints and host-supplied durable Runtime/Action stores are environment-owned and were not proved by this configuration step.')

    $report = [ordered]@{
        reportVersion    = 'campaign-production-acceptance/1.0'
        observedAt       = $ObservedAt
        configuration    = [ordered]@{
            productionEnabled      = (Test-Truthy (Get-ProcessVariable 'CAMPAIGN_PRODUCTION_ENABLED'))
            frontendGatewayMode    = 'http'
            requiredSettingsPresent = ($MissingSettings.Count -eq 0)
            missingSettings        = @($MissingSettings)
            forbiddenSettings      = @($ForbiddenSettings)
            providerRuntimeRequested = $ProviderRuntimeRequested
            providerRuntimeAllowed   = $ProviderRuntimeAllowed
        }
        localIntegration = [ordered]@{
            backendHealthReached = $HealthReached
            backendFailureCode   = $FailureCode
            backendLog           = 'redacted; see .automation/runtime/logs/backend.err.log'
            frontendLog          = 'redacted; see .automation/runtime/logs/frontend.err.log'
        }
        liveProduction   = [ordered]@{
            oidcAccessTokenObtained       = 'NOT_RUN'
            badTokenRefused               = 'NOT_RUN'
            wrongAudienceRefused          = 'NOT_RUN'
            keyRotationFailureRefused     = 'NOT_RUN'
            modelInvocationEvidence       = 'NOT_RUN'
            postgresAudiencePreview       = 'NOT_RUN'
            campaignProposalProduced      = 'NOT_RUN'
            planApplyPreparePublish       = 'NOT_RUN'
            runtimeAndProviderEvidence    = 'NOT_RUN'
            restartDurability             = 'NOT_RUN'
            frontendConversationOnly      = 'NOT_RUN'
            note                          = 'Run scripts\Invoke-ProductionLiveAcceptance.ps1 to attempt the live set.'
        }
        productionGateEvidence = $false
        blockers         = @($blockers)
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Step "Bounded acceptance report written to $reportPath (productionGateEvidence=false)."
}

# ------------------------------------------------------------------ validation

$observedAt = (Get-Date).ToUniversalTime().ToString('o')
$missing = Get-MissingSettings
$forbidden = Get-ForbiddenSettings
$providerRuntimeRequested = Test-Truthy (Get-ProcessVariable 'CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED')

# `CAMPAIGN_PRODUCTION_ENABLED` is already in `$RequiredSettings`, so an absent or blank value is reported there.
# Replace that single name with the explicit exact-true reason instead of adding it a second time: the report
# names the setting exactly once, and every value that is not exactly `true` still fails closed.
$productionEnabled = Get-ProcessVariable 'CAMPAIGN_PRODUCTION_ENABLED'
if (-not (Test-Truthy $productionEnabled)) {
    [void]$missing.Remove('CAMPAIGN_PRODUCTION_ENABLED')
    $missing.Add('CAMPAIGN_PRODUCTION_ENABLED (must be exactly true)')
}
$providerRuntimeAllowed = [bool]$AllowProviderRuntime
if ($providerRuntimeRequested -and -not $providerRuntimeAllowed) {
    $forbidden.Add('CAMPAIGN_PRODUCTION_PROVIDER_RUNTIME_ENABLED=true without -AllowProviderRuntime')
}

if ($missing.Count -gt 0 -or $forbidden.Count -gt 0) {
    New-Item -ItemType Directory -Force -Path $RuntimeDirectory | Out-Null
    Write-AcceptanceReport -ObservedAt $observedAt -HealthReached $false -FailureCode $null `
        -MissingSettings $missing -ForbiddenSettings $forbidden `
        -ProviderRuntimeRequested $providerRuntimeRequested -ProviderRuntimeAllowed $providerRuntimeAllowed `
        -BackendLog '' -FrontendLog ''
    if ($forbidden.Count -gt 0) {
        throw "The production environment refuses simulation settings: $($forbidden -join '; ')."
    }
    throw "The production environment is missing required settings (names only): $($missing -join ', ')."
}

$javaExecutable = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Required JDK 21 was not found: $JdkHome"
}

foreach ($port in @($BackendPort, $FrontendPort)) {
    if (Test-LoopbackPortOccupied -Port $port) {
        throw "Port $port is already in use. This script refuses to stop an unrelated process; choose another port."
    }
}

$jar = Get-ApplicationJar -Root $RepositoryRoot
if ($Build -or $null -eq $jar) {
    if ($DryRun) {
        Write-Step "DRY RUN: the application JAR would be built (present: $($null -ne $jar), forced: $([bool]$Build))."
    } else {
        Invoke-PackagedBuild -Root $RepositoryRoot -Jdk $JdkHome -Repo $MavenRepoLocal
        $jar = Get-ApplicationJar -Root $RepositoryRoot
    }
}
if (-not $DryRun -and $null -eq $jar) {
    throw 'The application JAR could not be located after the build.'
}

$frontendDirectory = Join-Path $RepositoryRoot 'frontend'
if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules') -PathType Container)) {
    throw 'frontend/node_modules is missing. Run "npm install" in frontend first.'
}
$npmCommand = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
if ($null -eq $npmCommand) {
    throw 'npm.cmd was not found; the frontend cannot be started.'
}

Write-Step "Validated $($RequiredSettings.Count) required production settings (names only; no value printed)."

if ($DryRun) {
    Write-Step "DRY RUN: backend would run $(if ($null -eq $jar) { '<jar after build>' } else { $jar }) on http://127.0.0.1:$BackendPort"
    Write-Step "DRY RUN: frontend would run from $frontendDirectory on http://127.0.0.1:$FrontendPort in http mode"
    Write-Step 'DRY RUN: no process was started and no metadata was written.'
    return
}

New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
Repair-DuplicateProcessPath

$backendOut = Join-Path $LogDirectory 'backend.out.log'
$backendErr = Join-Path $LogDirectory 'backend.err.log'
$frontendOut = Join-Path $LogDirectory 'frontend.out.log'
$frontendErr = Join-Path $LogDirectory 'frontend.err.log'
$metadataPath = Join-Path $RuntimeDirectory 'production-local.json'

$backendProcess = $null
$frontendProcess = $null
$healthReached = $false
$failureCode = $null
$savedGatewayMode = $env:VITE_GATEWAY_MODE
$savedApiBaseUrl = $env:VITE_API_BASE_URL
$savedCampaignId = $env:VITE_CAMPAIGN_ID

try {
    Write-Step "Starting the production backend on http://127.0.0.1:$BackendPort"
    $backendProcess = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-jar', $jar, '--server.address=127.0.0.1', "--server.port=$BackendPort") `
        -WorkingDirectory $RepositoryRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr

    $env:VITE_GATEWAY_MODE = 'http'
    $env:VITE_API_BASE_URL = $ApiBaseUrl
    $env:VITE_CAMPAIGN_ID = $CampaignId

    Write-Step "Starting the frontend on http://127.0.0.1:$FrontendPort in http mode"
    $frontendArguments = @(
        '/c', $npmCommand.Source, 'run', 'dev', '--',
        '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort'
    )
    $frontendProcess = Start-Process -FilePath $env:ComSpec -ArgumentList $frontendArguments `
        -WorkingDirectory $frontendDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr

    $timeoutSeconds = $TimeoutMinutes * 60
    try {
        Wait-ForHttp -Url "http://127.0.0.1:$BackendPort/api/v1/health" -ExpectedFragment '"success":true' `
            -TimeoutSeconds $timeoutSeconds -TrackedProcesses @($backendProcess, $frontendProcess) -Label 'The backend'
        $healthReached = $true
    } catch {
        # The backend fail-closed at startup, or it is genuinely unavailable. Report the bounded code, if any.
        $failureCode = Get-BoundedFailureCode -LogPath $backendErr
        throw
    }

    Wait-ForHttp -Url "http://127.0.0.1:$FrontendPort/" -ExpectedFragment 'id="root"' `
        -TimeoutSeconds $timeoutSeconds -TrackedProcesses @($backendProcess, $frontendProcess) -Label 'The frontend'

    $metadata = [ordered]@{
        startedAt    = $observedAt
        gatewayMode  = 'http'
        production   = $true
        repository   = $RepositoryRoot
        javaHome     = $JdkHome
        jar          = $jar
        backend      = [ordered]@{
            pid  = $backendProcess.Id
            port = $BackendPort
            url  = "http://127.0.0.1:$BackendPort"
            healthUrl = "http://127.0.0.1:$BackendPort/api/v1/health"
            outLog = $backendOut
            errLog = $backendErr
        }
        frontend     = [ordered]@{
            pid  = $frontendProcess.Id
            port = $FrontendPort
            url  = "http://127.0.0.1:$FrontendPort"
            outLog = $frontendOut
            errLog = $frontendErr
        }
    }
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

    Write-AcceptanceReport -ObservedAt $observedAt -HealthReached $true -FailureCode $null `
        -MissingSettings $missing -ForbiddenSettings $forbidden `
        -ProviderRuntimeRequested $providerRuntimeRequested -ProviderRuntimeAllowed ([bool]$AllowProviderRuntime) `
        -BackendLog $backendErr -FrontendLog $frontendErr

    Write-Host ''
    Write-Step 'Production-shaped environment is up.'
    Write-Host "  Backend : http://127.0.0.1:$BackendPort  (health: http://127.0.0.1:$BackendPort/api/v1/health)"
    Write-Host "  Frontend: http://127.0.0.1:$FrontendPort  (gateway: http)"
    Write-Host "  Logs    : $LogDirectory"
    Write-Host '  This is configuration plus local integration evidence only. productionGateEvidence=false.'
    Write-Host ''
    Write-Step 'Stop both processes with scripts\Stop-Production.ps1'
} catch {
    Write-Step "Startup failed: $($_.Exception.Message)"
    if ($null -eq $failureCode) {
        $failureCode = Get-BoundedFailureCode -LogPath $backendErr
    }
    if ($null -ne $frontendProcess) { Stop-StartedProcess -ProcessId $frontendProcess.Id }
    if ($null -ne $backendProcess) { Stop-StartedProcess -ProcessId $backendProcess.Id }
    if (Test-Path -LiteralPath $metadataPath) { Remove-Item -LiteralPath $metadataPath -Force }
    Write-AcceptanceReport -ObservedAt $observedAt -HealthReached $false -FailureCode $failureCode `
        -MissingSettings $missing -ForbiddenSettings $forbidden `
        -ProviderRuntimeRequested $providerRuntimeRequested -ProviderRuntimeAllowed ([bool]$AllowProviderRuntime) `
        -BackendLog $backendErr -FrontendLog $frontendErr
    throw
} finally {
    $env:VITE_GATEWAY_MODE = $savedGatewayMode
    $env:VITE_API_BASE_URL = $savedApiBaseUrl
    $env:VITE_CAMPAIGN_ID = $savedCampaignId
}
