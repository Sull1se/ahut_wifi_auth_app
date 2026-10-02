[CmdletBinding()]
param(
    [switch]$VerifyOnly,
    [switch]$SkipTests,
    [switch]$SkipLint
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'signing'))
$keystorePath = [IO.Path]::GetFullPath((Join-Path $signingDir 'ahut-release.p12'))
$passwordPath = [IO.Path]::GetFullPath((Join-Path $signingDir 'release-password.dpapi'))
$toolingDir = [IO.Path]::GetFullPath((Join-Path $repoRoot '.gradle'))
$gradleUserHome = [IO.Path]::GetFullPath((Join-Path $toolingDir 'user-home'))
$androidUserHome = [IO.Path]::GetFullPath((Join-Path $toolingDir 'android-user-home'))
$tempDir = [IO.Path]::GetFullPath((Join-Path $toolingDir 'tmp'))
$javaHome = [IO.Path]::GetFullPath((Split-Path (Split-Path (Get-Command java.exe -ErrorAction Stop).Source -Parent) -Parent))
$wrapper = [IO.Path]::GetFullPath((Join-Path $repoRoot 'gradlew.bat'))
$environmentNames = @(
    'AHUT_RELEASE_STORE_FILE', 'AHUT_RELEASE_KEY_ALIAS',
    'AHUT_RELEASE_STORE_PASSWORD', 'AHUT_RELEASE_KEY_PASSWORD',
    'GRADLE_USER_HOME', 'ANDROID_USER_HOME', 'ANDROID_PREFS_ROOT', 'ANDROID_SDK_HOME',
    'TEMP', 'TMP', 'JAVA_HOME', 'JAVA_OPTS', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'
)
$oldEnvironment = @{}
$securePassword = $null
$passwordPlain = $null

function Assert-WithinRepo([string]$Path) {
    $rootPrefix = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside repository: $Path"
    }
}

foreach ($path in @($signingDir, $keystorePath, $passwordPath, $toolingDir, $gradleUserHome, $androidUserHome, $tempDir, $wrapper)) {
    Assert-WithinRepo $path
}
foreach ($name in $environmentNames) {
    $oldEnvironment[$name] = @{
        Exists = [Environment]::GetEnvironmentVariables('Process').Contains($name)
        Value = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
}

function Remove-ProcessEnvironmentVariable([string]$Name) {
    Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
}

function Restore-ProcessEnvironmentVariable([string]$Name, [hashtable]$Snapshot) {
    if ($Snapshot.Exists) {
        [Environment]::SetEnvironmentVariable($Name, $Snapshot.Value, 'Process')
    }
    else {
        Remove-ProcessEnvironmentVariable $Name
    }
}

try {
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Gradle wrapper is missing: $wrapper"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'local.properties') -PathType Leaf)) {
        throw 'local.properties is missing; refusing to let Gradle search for or install an SDK.'
    }

    if (-not $VerifyOnly) {
        if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
            throw "Signing keystore is missing: $keystorePath"
        }
        if (-not (Test-Path -LiteralPath $passwordPath -PathType Leaf)) {
            throw "Current-user DPAPI password file is missing: $passwordPath"
        }
        $encryptedPassword = [IO.File]::ReadAllText($passwordPath).Trim()
        if ([string]::IsNullOrWhiteSpace($encryptedPassword)) {
            throw 'The DPAPI password file is empty.'
        }
        $securePassword = ConvertTo-SecureString -String $encryptedPassword
        $passwordPlain = [Net.NetworkCredential]::new('', $securePassword).Password
    }

    foreach ($path in @($toolingDir, $gradleUserHome, $androidUserHome, $tempDir)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
    if ($VerifyOnly) {
        foreach ($name in @('AHUT_RELEASE_STORE_FILE', 'AHUT_RELEASE_KEY_ALIAS', 'AHUT_RELEASE_STORE_PASSWORD', 'AHUT_RELEASE_KEY_PASSWORD')) {
            Remove-ProcessEnvironmentVariable $name
        }
    }
    else {
        $env:AHUT_RELEASE_STORE_FILE = $keystorePath
        $env:AHUT_RELEASE_KEY_ALIAS = 'ahut-release'
        $env:AHUT_RELEASE_STORE_PASSWORD = $passwordPlain
        $env:AHUT_RELEASE_KEY_PASSWORD = $passwordPlain
    }
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:ANDROID_USER_HOME = $androidUserHome
    Remove-ProcessEnvironmentVariable 'ANDROID_PREFS_ROOT'
    Remove-ProcessEnvironmentVariable 'ANDROID_SDK_HOME'
    $env:TEMP = $tempDir
    $env:TMP = $tempDir
    $env:JAVA_HOME = $javaHome
    $env:JAVA_OPTS = "-Djava.io.tmpdir=$tempDir"
    $env:GRADLE_OPTS = "-Djava.io.tmpdir=$tempDir"
    Remove-ProcessEnvironmentVariable 'JAVA_TOOL_OPTIONS'
    Remove-ProcessEnvironmentVariable '_JAVA_OPTIONS'
    Remove-ProcessEnvironmentVariable 'JDK_JAVA_OPTIONS'

    if (-not $VerifyOnly) {
        $keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
        $verifyArgs = @('-list', '-keystore', $keystorePath, '-storetype', 'PKCS12', '-alias', $env:AHUT_RELEASE_KEY_ALIAS, '-storepass:env', 'AHUT_RELEASE_STORE_PASSWORD')
        & $keytool @verifyArgs | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'The configured release keystore or alias could not be verified.'
        }
    }

    $tasks = [Collections.Generic.List[string]]::new()
    if (-not $SkipTests) { $tasks.Add('testDebugUnitTest') }
    if (-not $SkipLint) { $tasks.Add('lintRelease') }
    if ($VerifyOnly) {
        $tasks.Add('compileReleaseKotlin')
    }
    else {
        $tasks.Add('assembleRelease')
    }
    $gradleArgs = @($tasks.ToArray()) + @(
        '--no-configuration-cache',
        '--no-daemon',
        '-Pkotlin.compiler.execution.strategy=in-process',
        '-Pandroid.builder.sdkDownload=false',
        "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$tempDir",
        '--gradle-user-home', $gradleUserHome
    )

    Push-Location $repoRoot
    try {
        & $wrapper @gradleArgs
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($gradleExitCode -ne 0) {
        throw "Gradle failed with exit code $gradleExitCode."
    }
    Write-Host 'Release Gradle tasks completed successfully.'
}
finally {
    foreach ($name in $environmentNames) {
        Restore-ProcessEnvironmentVariable $name $oldEnvironment[$name]
    }
    $passwordPlain = $null
    if ($securePassword) { $securePassword.Dispose() }
}
