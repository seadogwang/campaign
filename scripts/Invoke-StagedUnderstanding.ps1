param(
    [Parameter(Mandatory = $true)]
    [string]$Requirement,

    [Parameter(Mandatory = $true)]
    [string]$AsOf
)

$ErrorActionPreference = 'Stop'
$jdkHome = 'D:\Project\Loyalty\tools\jdk-21.0.6+7'
$javaExecutable = Join-Path $jdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw 'JDK 21 was not found at the configured path.'
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$previousRequirement = $env:SEGMENT_REQUIREMENT
try {
    $env:JAVA_HOME = $jdkHome
    $env:Path = "$jdkHome\bin;$previousPath"
    $env:SEGMENT_REQUIREMENT = $Requirement
    & mvn.cmd -q -DskipTests compile exec:java `
        '-Dexec.mainClass=com.company.campaign.dev.segment.StagedUnderstandingCli' `
        "-Dexec.args=--as-of $AsOf"
    exit $LASTEXITCODE
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
    $env:SEGMENT_REQUIREMENT = $previousRequirement
}
