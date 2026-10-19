[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TaskPath,

    [string]$Model = "",

    [ValidateRange(1, 240)]
    [int]$TimeoutMinutes = 60,

    [ValidateRange(1, 5)]
    [int]$MaxAttempts = 3,

    [ValidateRange(0, 300)]
    [int]$RetryDelaySeconds = 20,

    [ValidateSet("acceptEdits", "auto")]
    [string]$PermissionMode = "auto",

    [switch]$Restricted
)

$ErrorActionPreference = "Stop"

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$resolvedTask = (Resolve-Path -LiteralPath $TaskPath).Path
$taskPrefix = $repositoryRoot.TrimEnd("\") + "\"

if (-not $resolvedTask.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Task file must be inside the repository: $repositoryRoot"
}

$javaHome = "D:\Project\Loyalty\tools\jdk-21.0.6+7"
$javaExecutable = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Required JDK 21 was not found: $javaHome"
}

$claudeCommand = Get-Command claude.cmd -ErrorAction Stop
$claudeRoot = Split-Path -Parent $claudeCommand.Source
$claudeExecutable = Join-Path $claudeRoot "node_modules\@anthropic-ai\claude-code\bin\claude.exe"
if (-not (Test-Path -LiteralPath $claudeExecutable -PathType Leaf)) {
    throw "Claude executable was not found beside claude.cmd: $claudeExecutable"
}
$claudeSettings = Join-Path $env:USERPROFILE ".claude\settings.json"
if (-not (Test-Path -LiteralPath $claudeSettings -PathType Leaf)) {
    throw "Claude settings were not found: $claudeSettings"
}

$relativeTask = $resolvedTask.Substring($taskPrefix.Length).Replace("\", "/")
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$taskName = [IO.Path]::GetFileNameWithoutExtension($resolvedTask)
$logDirectory = Join-Path $repositoryRoot ".automation\logs"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPrefix = Join-Path $logDirectory "$timestamp-$taskName"

$prompt = @"
Open and execute the complete task in '$relativeTask'. Work only inside this repository.
Do not ask interactive questions. Make conservative assumptions within the task scope, and stop rather than performing destructive or out-of-scope actions.
Use JAVA_HOME=$javaHome for every Java or Maven command. Implement the task, run every required verification command, and finish with changed files, test results, and remaining blockers.
"@.Trim()

$arguments = @(
    "-p",
    ('"' + $prompt.Replace('"', '\"') + '"'),
    "--settings", ('"' + $claudeSettings + '"'),
    "--permission-mode", $PermissionMode,
    "--permission-prompts", "none",
    "--disallowedTools", "WebFetch,WebSearch",
    "--effort", "high",
    "--output-format", "text",
    "--no-session-persistence"
)

if ($Restricted) {
    $arguments += @("--restricted", "--tools", "Read,Edit,Write,Glob,Grep,PowerShell")
} else {
    $arguments += @("--allowedTools", "Read,Edit,Write,Glob,Grep,PowerShell")
}

if (-not [string]::IsNullOrWhiteSpace($Model)) {
    $arguments += @("--model", $Model)
}

$inheritedPath = $env:Path
[Environment]::SetEnvironmentVariable("PATH", $null, [EnvironmentVariableTarget]::Process)
[Environment]::SetEnvironmentVariable("Path", "$javaHome\bin;$inheritedPath", [EnvironmentVariableTarget]::Process)
$env:JAVA_HOME = $javaHome
$env:API_TIMEOUT_MS = [string]($TimeoutMinutes * 60 * 1000)
$env:CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT = "1"

Write-Host "Task: $relativeTask"
Write-Host "JDK:  $javaHome"
Write-Host "Log:  $logPrefix-attempt-*.log"

$lastExitCode = 1
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $stdoutLog = "$logPrefix-attempt-$attempt.out.log"
    $stderrLog = "$logPrefix-attempt-$attempt.err.log"
    Write-Host "Starting Claude attempt $attempt/$MaxAttempts with a $TimeoutMinutes minute timeout..."

    $process = Start-Process `
        -FilePath $claudeExecutable `
        -ArgumentList $arguments `
        -WorkingDirectory $repositoryRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden `
        -PassThru

    $completed = $process.WaitForExit($TimeoutMinutes * 60 * 1000)
    if (-not $completed) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        [Console]::Error.WriteLine("Claude timed out after $TimeoutMinutes minutes. See $stdoutLog and $stderrLog")
        exit 124
    }

    $process.WaitForExit()
    $process.Refresh()
    $lastExitCode = if ($null -eq $process.ExitCode) { 1 } else { [int]$process.ExitCode }

    if (Test-Path -LiteralPath $stdoutLog) {
        Get-Content -LiteralPath $stdoutLog
    }

    if ($lastExitCode -eq 0) {
        Write-Host "Claude task completed successfully. Review and acceptance are still required."
        exit 0
    }

    # Evaluate the completion report as a boolean expression, not a command invocation: writing
    # "Test-Path ... -and (...)" on one logical line would bind -and as a Test-Path parameter. Keeping
    # the two commands separate avoids any backtick continuation.
    $completionReported = $false
    if (Test-Path -LiteralPath $stdoutLog) {
        $completionReported = [bool](Select-String -LiteralPath $stdoutLog -Pattern "All required work is complete|All work is complete|All verification passes|All verification commands pass|completion report|Scope confirmation|\bT\d+[A-Z]?(?:-R\d+)?\s+is complete\b" -Quiet)
    }
    if ($completionReported) {
        Write-Warning "Claude produced a completion report but the custom model adapter returned exit code $lastExitCode. Skipping duplicate retry; independent review is still required."
        exit 0
    }

    if (Test-Path -LiteralPath $stderrLog) {
        Get-Content -LiteralPath $stderrLog -Tail 80 | ForEach-Object { [Console]::Error.WriteLine($_) }
    }
    [Console]::Error.WriteLine("Claude exited with code $lastExitCode on attempt $attempt.")

    if ($attempt -lt $MaxAttempts -and $RetryDelaySeconds -gt 0) {
        Write-Host "Retrying in $RetryDelaySeconds seconds..."
        Start-Sleep -Seconds $RetryDelaySeconds
    }
}

exit $lastExitCode
