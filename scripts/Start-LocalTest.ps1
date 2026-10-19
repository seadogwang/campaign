<#
.SYNOPSIS
    Starts the local human-test environment: the packaged Spring Boot application and the Vite frontend.

.DESCRIPTION
    One command that starts both halves of the accepted build on loopback:

      * the backend from the packaged application JAR on 127.0.0.1:8080, run with JDK 21;
      * the frontend Vite development server on 127.0.0.1:5173, in development fixture mode by default so the
        complete accepted UI is testable without any backend route that the accepted build does not expose.

    The script is deliberately conservative:

      * it refuses an occupied port instead of terminating whatever holds it;
      * it launches every child hidden and writes bounded, non-secret logs and PID metadata under
        .automation/runtime;
      * it waits with a bounded timeout for the backend health endpoint and the frontend HTML;
      * if startup fails it stops only the processes it started itself, and never uses wildcard termination.

    Fixture mode is simulation-only and is not readiness evidence: it cannot satisfy LOCAL_READY, AI_READY or
    LIVE_READY. HTTP gateway mode is opt-in and makes no claim that the accepted backend exposes the
    documented /api/v1 routes it does not implement.

.PARAMETER GatewayMode
    'fixture' (default) starts the UI in the permanent SIMULATED development fixture mode.
    'http' starts the UI pointed at -ApiBaseUrl; only the read-only Segment Preview slice is actually exposed
    by the accepted backend today, so the UI keeps reporting every unwired route honestly.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalTest.ps1

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalTest.ps1 -Build -GatewayMode http -ApiBaseUrl http://127.0.0.1:8080
#>
[CmdletBinding()]
param(
    [string]$JdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7',

    [ValidateRange(1, 65535)]
    [int]$BackendPort = 8080,

    [ValidateRange(1, 65535)]
    [int]$FrontendPort = 5173,

    [ValidateSet('fixture', 'http')]
    [string]$GatewayMode = 'fixture',

    [string]$ApiBaseUrl = '',

    [string]$CampaignId = 'campaign-1',

    [string]$MavenRepoLocal = 'C:/Users/WangL/.m2/repository',

    [switch]$Build,

    [ValidateRange(1, 600)]
    [int]$TimeoutMinutes = 3,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "[local-test] $Message"
}

function Get-RepositoryRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

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
    param([string]$RepositoryRoot)

    $target = Join-Path $RepositoryRoot 'target'
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
    param([string]$RepositoryRoot, [string]$JdkHome, [string]$MavenRepoLocal)

    $maven = Join-Path $RepositoryRoot 'mvnw.cmd'
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
        $env:JAVA_HOME = $JdkHome
        $env:Path = (Join-Path $JdkHome 'bin') + ';' + $savedPath
        & $maven -q "-Dmaven.repo.local=$MavenRepoLocal" -DskipTests package
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

$repositoryRoot = Get-RepositoryRoot
$runtimeDirectory = Join-Path $repositoryRoot '.automation\runtime'
$logDirectory = Join-Path $runtimeDirectory 'logs'
$metadataPath = Join-Path $runtimeDirectory 'local-test.json'

$javaExecutable = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Required JDK 21 was not found: $JdkHome"
}

foreach ($port in @($BackendPort, $FrontendPort)) {
    if (Test-LoopbackPortOccupied -Port $port) {
        throw "Port $port is already in use. This script refuses to stop an unrelated process; choose another port."
    }
}

$jar = Get-ApplicationJar -RepositoryRoot $repositoryRoot
if ($Build -or $null -eq $jar) {
    if ($DryRun) {
        Write-Step "DRY RUN: the application JAR would be built (present: $($null -ne $jar), forced: $([bool]$Build))."
    } else {
        Invoke-PackagedBuild -RepositoryRoot $repositoryRoot -JdkHome $JdkHome -MavenRepoLocal $MavenRepoLocal
        $jar = Get-ApplicationJar -RepositoryRoot $repositoryRoot
    }
}
if (-not $DryRun -and $null -eq $jar) {
    throw 'The application JAR could not be located after the build.'
}

$frontendDirectory = Join-Path $repositoryRoot 'frontend'
if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules') -PathType Container)) {
    throw 'frontend/node_modules is missing. Run "npm install" in frontend first.'
}

$npmCommand = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
if ($null -eq $npmCommand) {
    throw 'npm.cmd was not found; the frontend cannot be started.'
}

if ($DryRun) {
    Write-Step "DRY RUN: backend would run $(if ($null -eq $jar) { '<jar after build>' } else { $jar }) on http://127.0.0.1:$BackendPort"
    Write-Step "DRY RUN: frontend would run from $frontendDirectory on http://127.0.0.1:$FrontendPort in $GatewayMode mode"
    Write-Step 'DRY RUN: no process was started and no metadata was written.'
    return
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Repair-DuplicateProcessPath

$startedAt = (Get-Date).ToUniversalTime().ToString('o')
$backendOut = Join-Path $logDirectory 'backend.out.log'
$backendErr = Join-Path $logDirectory 'backend.err.log'
$frontendOut = Join-Path $logDirectory 'frontend.out.log'
$frontendErr = Join-Path $logDirectory 'frontend.err.log'

$backendProcess = $null
$frontendProcess = $null
$savedGatewayMode = $env:VITE_GATEWAY_MODE
$savedApiBaseUrl = $env:VITE_API_BASE_URL
$savedDevProxyTarget = $env:VITE_DEV_PROXY_TARGET
$savedCampaignId = $env:VITE_CAMPAIGN_ID

try {
    Write-Step "Starting the backend on http://127.0.0.1:$BackendPort"
    $backendProcess = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-jar', $jar, '--server.address=127.0.0.1', "--server.port=$BackendPort") `
        -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr

    if ($GatewayMode -eq 'http') {
        $env:VITE_GATEWAY_MODE = 'http'
        if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
            Remove-Item Env:VITE_API_BASE_URL -ErrorAction SilentlyContinue
            $env:VITE_DEV_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
        } else {
            $env:VITE_API_BASE_URL = $ApiBaseUrl
            Remove-Item Env:VITE_DEV_PROXY_TARGET -ErrorAction SilentlyContinue
        }
        $env:VITE_CAMPAIGN_ID = $CampaignId
    } else {
        Remove-Item Env:VITE_GATEWAY_MODE -ErrorAction SilentlyContinue
        Remove-Item Env:VITE_API_BASE_URL -ErrorAction SilentlyContinue
        Remove-Item Env:VITE_DEV_PROXY_TARGET -ErrorAction SilentlyContinue
        Remove-Item Env:VITE_CAMPAIGN_ID -ErrorAction SilentlyContinue
    }

    Write-Step "Starting the frontend on http://127.0.0.1:$FrontendPort in $GatewayMode mode"
    $frontendArguments = @(
        '/c', $npmCommand.Source, 'run', 'dev', '--',
        '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort'
    )
    $frontendProcess = Start-Process -FilePath $env:ComSpec -ArgumentList $frontendArguments `
        -WorkingDirectory $frontendDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr

    $timeoutSeconds = $TimeoutMinutes * 60
    Wait-ForHttp -Url "http://127.0.0.1:$BackendPort/api/v1/health" -ExpectedFragment '"success":true' `
        -TimeoutSeconds $timeoutSeconds -TrackedProcesses @($backendProcess, $frontendProcess) -Label 'The backend'
    Wait-ForHttp -Url "http://127.0.0.1:$FrontendPort/" -ExpectedFragment 'id="root"' `
        -TimeoutSeconds $timeoutSeconds -TrackedProcesses @($backendProcess, $frontendProcess) -Label 'The frontend'

    $metadata = [ordered]@{
        startedAt    = $startedAt
        gatewayMode  = $GatewayMode
        repository   = $repositoryRoot
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

    Write-Host ''
    Write-Step 'Local test environment is up.'
    Write-Host "  Backend : http://127.0.0.1:$BackendPort  (health: http://127.0.0.1:$BackendPort/api/v1/health)"
    Write-Host "  Frontend: http://127.0.0.1:$FrontendPort  (mode: $GatewayMode)"
    Write-Host "  Logs    : $logDirectory"
    if ($GatewayMode -eq 'fixture') {
        Write-Host '  Fixture mode is SIMULATED and is not readiness evidence; it cannot satisfy LOCAL_READY, AI_READY or LIVE_READY.'
    } else {
        Write-Host '  HTTP mode: only the read-only Segment Preview slice is exposed by the accepted backend; every other documented route stays unimplemented.'
    }
    Write-Host ''
    Write-Step 'Stop both processes with scripts\Stop-LocalTest.ps1'
} catch {
    Write-Step "Startup failed: $($_.Exception.Message)"
    if ($null -ne $frontendProcess) { Stop-StartedProcess -ProcessId $frontendProcess.Id }
    if ($null -ne $backendProcess) { Stop-StartedProcess -ProcessId $backendProcess.Id }
    if (Test-Path -LiteralPath $metadataPath) { Remove-Item -LiteralPath $metadataPath -Force }
    throw
} finally {
    $env:VITE_GATEWAY_MODE = $savedGatewayMode
    $env:VITE_API_BASE_URL = $savedApiBaseUrl
    $env:VITE_DEV_PROXY_TARGET = $savedDevProxyTarget
    $env:VITE_CAMPAIGN_ID = $savedCampaignId
}
