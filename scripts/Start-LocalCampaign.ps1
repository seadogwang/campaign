<#
.SYNOPSIS
    Starts the complete local Campaign authoring environment with PostgreSQL-backed state and Segment Preview.

.DESCRIPTION
    Reads the existing local PostgreSQL connection descriptor, enables the development Campaign Agent, uses
    PostgreSQL for durable conversations and the business-resource catalog, and enables the genuine read-only
    Segment Preview adapter. The application still reads model credentials through its normal provider boundary.

    The configured Preview database must already expose the physical tables declared by the server-owned Core
    Loyalty storage catalog. This script never creates, alters or seeds business tables.

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalCampaign.ps1 -Build

.EXAMPLE
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalCampaign.ps1 `
      -TranscriptDirectory .\.runtime_logs\campaign-transcripts
#>
[CmdletBinding()]
param(
    [string]$ConfigurationPath = '',
    [string]$JdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7',
    [ValidateRange(1, 65535)]
    [int]$BackendPort = 8080,
    [ValidateRange(1, 65535)]
    [int]$FrontendPort = 5173,
    [string]$ApiBaseUrl = '',
    [string]$CampaignId = 'campaign-1',
    [string]$ConversationSchema = 'campaign_conversation',
    [string]$PreviewSchema = 'public',
    [string]$TranscriptDirectory = '',
    [string]$MavenRepoLocal = 'C:/Users/WangL/.m2/repository',
    [switch]$Build,
    [ValidateRange(1, 600)]
    [int]$TimeoutMinutes = 3,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.automation/runtime'))
if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
    $ConfigurationPath = Join-Path $runtimeRoot 'postgres-local.json'
}
$ConfigurationPath = [IO.Path]::GetFullPath($ConfigurationPath)

if (-not (Test-Path -LiteralPath $ConfigurationPath -PathType Leaf)) {
    throw "PostgreSQL configuration was not found at $ConfigurationPath."
}

$configuration = Get-Content -LiteralPath $ConfigurationPath -Raw | ConvertFrom-Json
foreach ($propertyName in 'url', 'username', 'passwordFile') {
    if (-not ($configuration.PSObject.Properties.Name -contains $propertyName) -or
        [string]::IsNullOrWhiteSpace([string]$configuration.$propertyName)) {
        throw "PostgreSQL configuration is missing '$propertyName'."
    }
}

$passwordPath = [string]$configuration.passwordFile
if (-not [IO.Path]::IsPathRooted($passwordPath)) {
    $passwordPath = Join-Path $repositoryRoot $passwordPath
}
$passwordPath = [IO.Path]::GetFullPath($passwordPath)
$runtimePrefix = $runtimeRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $passwordPath.StartsWith($runtimePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The PostgreSQL password file must remain under .automation/runtime.'
}
if (-not (Test-Path -LiteralPath $passwordPath -PathType Leaf)) {
    throw "PostgreSQL password file was not found at $passwordPath."
}

$password = (Get-Content -LiteralPath $passwordPath -Raw).TrimEnd([char[]]"`r`n")
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'The PostgreSQL password file is empty.'
}

$sampleKeyPath = Join-Path $runtimeRoot 'campaign-preview-sample.secret'
$sampleKey = $null
if (-not $DryRun) {
    if (Test-Path -LiteralPath $sampleKeyPath -PathType Leaf) {
        $sampleKey = (Get-Content -LiteralPath $sampleKeyPath -Raw).TrimEnd([char[]]"`r`n")
    } else {
        $randomBytes = New-Object byte[] 32
        [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
        $sampleKey = [Convert]::ToBase64String($randomBytes)
        New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
        Set-Content -LiteralPath $sampleKeyPath -Value $sampleKey -Encoding ASCII -NoNewline
    }
    if ([Text.Encoding]::UTF8.GetByteCount($sampleKey) -lt 32) {
        throw 'The local Preview sample key is invalid.'
    }
}

if (-not [string]::IsNullOrWhiteSpace($TranscriptDirectory)) {
    if (-not [IO.Path]::IsPathRooted($TranscriptDirectory)) {
        $TranscriptDirectory = Join-Path $repositoryRoot $TranscriptDirectory
    }
    $TranscriptDirectory = [IO.Path]::GetFullPath($TranscriptDirectory)
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force -Path $TranscriptDirectory | Out-Null
    }
}

$environment = [ordered]@{
    CAMPAIGN_AGENT_DEVELOPMENT_ENABLED = 'true'
    CAMPAIGN_AGENT_DEVELOPMENT_SCHEMA_REPAIR_ENABLED = 'true'
    CAMPAIGN_AGENT_DEVELOPMENT_STORE_MODE = 'POSTGRESQL'
    CAMPAIGN_DATASOURCE_ENABLED = 'true'
    CAMPAIGN_DATASOURCE_URL = [string]$configuration.url
    CAMPAIGN_DATASOURCE_USERNAME = [string]$configuration.username
    CAMPAIGN_DATASOURCE_PASSWORD = $password
    CAMPAIGN_DATASOURCE_SCHEMA = $ConversationSchema
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_ENABLED = 'true'
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_URL = [string]$configuration.url
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_USERNAME = [string]$configuration.username
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_PASSWORD = $password
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_SCHEMA = $PreviewSchema
    CAMPAIGN_AGENT_DEVELOPMENT_PREVIEW_POSTGRES_SAMPLE_KEY = $sampleKey
    CAMPAIGN_AGENT_DEVELOPMENT_TRANSCRIPT_ENABLED = if ([string]::IsNullOrWhiteSpace($TranscriptDirectory)) { 'false' } else { 'true' }
    CAMPAIGN_AGENT_DEVELOPMENT_TRANSCRIPT_DIRECTORY = $TranscriptDirectory
}

$previousEnvironment = @{}
foreach ($entry in $environment.GetEnumerator()) {
    $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
}

$startArguments = @{
    JdkHome = $JdkHome
    BackendPort = $BackendPort
    FrontendPort = $FrontendPort
    GatewayMode = 'http'
    ApiBaseUrl = $ApiBaseUrl
    CampaignId = $CampaignId
    MavenRepoLocal = $MavenRepoLocal
    Build = $Build
    TimeoutMinutes = $TimeoutMinutes
    DryRun = $DryRun
}

try {
    foreach ($entry in $environment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    & (Join-Path $PSScriptRoot 'Start-LocalTest.ps1') @startArguments
} finally {
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    $password = $null
    $sampleKey = $null
}
