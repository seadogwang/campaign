[CmdletBinding()]
param(
    [string]$ConfigurationPath = '',
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
foreach ($propertyName in 'url', 'username', 'passwordFile', 'schema') {
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

$environmentNames = @(
    'CAMPAIGN_DATASOURCE_ENABLED',
    'CAMPAIGN_DATASOURCE_URL',
    'CAMPAIGN_DATASOURCE_USERNAME',
    'CAMPAIGN_DATASOURCE_PASSWORD',
    'CAMPAIGN_DATASOURCE_SCHEMA'
)
$previousEnvironment = @{}
foreach ($environmentName in $environmentNames) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}

$startArguments = @{
    JdkHome        = $JdkHome
    BackendPort    = $BackendPort
    FrontendPort   = $FrontendPort
    GatewayMode    = $GatewayMode
    ApiBaseUrl     = $ApiBaseUrl
    CampaignId     = $CampaignId
    MavenRepoLocal = $MavenRepoLocal
    Build          = $Build
    DryRun         = $DryRun
}
if ($PSBoundParameters.ContainsKey('TimeoutMinutes')) {
    $startArguments.TimeoutMinutes = $TimeoutMinutes
}

try {
    $env:CAMPAIGN_DATASOURCE_ENABLED = 'true'
    $env:CAMPAIGN_DATASOURCE_URL = [string]$configuration.url
    $env:CAMPAIGN_DATASOURCE_USERNAME = [string]$configuration.username
    $env:CAMPAIGN_DATASOURCE_PASSWORD = $password
    $env:CAMPAIGN_DATASOURCE_SCHEMA = [string]$configuration.schema
    & (Join-Path $PSScriptRoot 'Start-LocalTest.ps1') @startArguments
} finally {
    foreach ($environmentName in $environmentNames) {
        [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironment[$environmentName], 'Process')
    }
    $password = $null
}
