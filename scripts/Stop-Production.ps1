<#
.SYNOPSIS
    Stops the production-shaped environment started by Start-Production.ps1.

.DESCRIPTION
    It reads the PID metadata the production startup script wrote under .automation/runtime and stops exactly
    those processes, verifying each one before it acts:

      * the process must still exist (an already-stopped process is tolerated, not an error);
      * its name must be the one the record expects for that role;
      * its start time must be at or after the recorded start of the run, so a recycled PID belonging to an
        unrelated process is never terminated.

    There is no wildcard termination anywhere: every Stop-Process call targets one verified PID, deepest child
    first. Stale metadata is removed only after the processes it names are gone or were never ours.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Production.ps1

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Production.ps1 -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$RuntimeDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "[production] $Message"
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

function Test-RecordedProcess {
    param(
        [int]$ProcessId,
        [string]$Role,
        [string[]]$ExpectedNames,
        [datetime]$NotBefore
    )

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Write-Step "$Role process $ProcessId is already stopped."
        return $false
    }
    if ($ExpectedNames -notcontains $process.ProcessName) {
        Write-Step "$Role process $ProcessId is '$($process.ProcessName)', which is not one of $($ExpectedNames -join ', '); it is left running."
        return $false
    }
    try {
        if ($process.StartTime -lt $NotBefore) {
            Write-Step "$Role process $ProcessId started before this run; the PID was reused and is left running."
            return $false
        }
    } catch {
        Write-Step "$Role process $ProcessId has no readable start time; it is left running."
        return $false
    }
    return $true
}

function Stop-VerifiedProcess {
    param(
        [int]$ProcessId,
        [string]$Role,
        [string[]]$ExpectedNames,
        [datetime]$NotBefore
    )

    if (-not (Test-RecordedProcess -ProcessId $ProcessId -Role $Role -ExpectedNames $ExpectedNames -NotBefore $NotBefore)) {
        return
    }
    foreach ($child in Get-ChildProcessId -ParentId $ProcessId) {
        Stop-VerifiedProcess -ProcessId $child -Role "$Role child" `
            -ExpectedNames @('node', 'cmd', 'java', 'conhost', 'esbuild') -NotBefore $NotBefore
    }
    if ($PSCmdlet.ShouldProcess("$Role process $ProcessId", 'Stop')) {
        try {
            Stop-Process -Id $ProcessId -Force -ErrorAction Stop
            Write-Step "Stopped $Role process $ProcessId."
        } catch {
            Write-Step "$Role process $ProcessId could not be stopped; it may have exited between checks."
        }
    }
}

if ([string]::IsNullOrWhiteSpace($RuntimeDirectory)) {
    $repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $RuntimeDirectory = Join-Path $repositoryRoot '.automation\runtime'
}

$metadataPath = Join-Path $RuntimeDirectory 'production-local.json'
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    Write-Step 'No recorded production environment was found; nothing to stop.'
    return
}

try {
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
} catch {
    Write-Step "The runtime metadata is unreadable and is being removed: $($_.Exception.Message)"
    if ($PSCmdlet.ShouldProcess($metadataPath, 'Remove stale metadata')) {
        Remove-Item -LiteralPath $metadataPath -Force
    }
    return
}

$startedAt = [datetime]::MinValue
if ($null -ne $metadata.startedAt) {
    [void][datetime]::TryParse(
        [string]$metadata.startedAt,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::RoundtripKind,
        [ref]$startedAt)
}
$notBefore = $startedAt.AddMinutes(-2)

if ($null -ne $metadata.frontend -and $null -ne $metadata.frontend.pid) {
    Stop-VerifiedProcess -ProcessId ([int]$metadata.frontend.pid) -Role 'frontend' `
        -ExpectedNames @('cmd', 'node') -NotBefore $notBefore
}
if ($null -ne $metadata.backend -and $null -ne $metadata.backend.pid) {
    Stop-VerifiedProcess -ProcessId ([int]$metadata.backend.pid) -Role 'backend' `
        -ExpectedNames @('java') -NotBefore $notBefore
}

$recordedIds = @()
foreach ($record in @($metadata.backend, $metadata.frontend)) {
    if ($null -ne $record -and $null -ne $record.pid) {
        $recordedIds += [int]$record.pid
    }
}

# A just-terminated process can still be reported for a moment, so the check is bounded rather than immediate.
$deadline = (Get-Date).AddSeconds(5)
$stillRunning = @()
do {
    $stillRunning = @()
    foreach ($pidValue in $recordedIds) {
        if ($null -ne (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) {
            $stillRunning += $pidValue
        }
    }
    if ($stillRunning.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $deadline)

if ($stillRunning.Count -eq 0) {
    if ($PSCmdlet.ShouldProcess($metadataPath, 'Remove runtime metadata')) {
        Remove-Item -LiteralPath $metadataPath -Force
        Write-Step 'Runtime metadata removed.'
    }
} else {
    Write-Step "Some recorded processes were not stopped ($($stillRunning -join ', ')); the metadata is kept for a later retry."
}

Write-Step 'Done.'