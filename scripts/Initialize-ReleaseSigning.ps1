[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'signing'))
$keystorePath = [IO.Path]::GetFullPath((Join-Path $signingDir 'ahut-release.p12'))
$workingDirectory = [IO.Path]::GetFullPath((Join-Path $signingDir ('.ahut-release.' + [Guid]::NewGuid().ToString('N') + '.partial')))
$workingKeystorePath = [IO.Path]::GetFullPath((Join-Path $workingDirectory 'ahut-release.p12'))
$passwordPath = [IO.Path]::GetFullPath((Join-Path $signingDir 'release-password.dpapi'))
$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
$envName = 'AHUT_RELEASE_INIT_PASSWORD'
$repoTempDir = [IO.Path]::GetFullPath((Join-Path $repoRoot '.gradle/tmp'))
$javaHome = [IO.Path]::GetFullPath((Split-Path (Split-Path $keytool -Parent) -Parent))
$environmentNames = @($envName, 'TEMP', 'TMP', 'JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')
$oldEnvironment = @{}
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
$securePassword = $null
$confirmPassword = $null
$createdKeystore = $false
$createdWorkingDirectory = $false
$createdPasswordFile = $false
$passwordPlain = $null

function Assert-WithinRepo([string]$Path) {
    $rootPrefix = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside repository: $Path"
    }
}

Assert-WithinRepo $signingDir
Assert-WithinRepo $keystorePath
Assert-WithinRepo $workingDirectory
Assert-WithinRepo $workingKeystorePath
Assert-WithinRepo $passwordPath
Assert-WithinRepo $repoTempDir

if (Test-Path -LiteralPath $keystorePath) {
    throw "Signing keystore already exists; refusing to overwrite it: $keystorePath"
}
if (Test-Path -LiteralPath $passwordPath) {
    throw "DPAPI password file already exists; refusing to overwrite it: $passwordPath"
}
if (Test-Path -LiteralPath $workingDirectory) {
    throw "Temporary signing path already exists; refusing to use it: $workingDirectory"
}

try {
    $securePassword = Read-Host 'Enter the new release signing password (input is hidden)' -AsSecureString
    $confirmPassword = Read-Host 'Confirm the release signing password' -AsSecureString
    if ($securePassword.Length -lt 6) {
        throw 'The password must contain at least six characters (keytool requirement).'
    }
    if ($securePassword.Length -ne $confirmPassword.Length) {
        throw 'The two password entries do not match.'
    }

    $firstBstr = [IntPtr]::Zero
    $secondBstr = [IntPtr]::Zero
    try {
        $firstBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $secondBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmPassword)
        for ($i = 0; $i -lt $securePassword.Length; $i++) {
            if ([Runtime.InteropServices.Marshal]::ReadInt16($firstBstr, $i * 2) -ne
                [Runtime.InteropServices.Marshal]::ReadInt16($secondBstr, $i * 2)) {
                throw 'The two password entries do not match.'
            }
        }
    }
    finally {
        if ($firstBstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstBstr)
        }
        if ($secondBstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondBstr)
        }
    }

    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
    New-Item -ItemType Directory -Path $repoTempDir -Force | Out-Null
    $env:TEMP = $repoTempDir
    $env:TMP = $repoTempDir
    $env:JAVA_HOME = $javaHome
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$repoTempDir"
    Remove-ProcessEnvironmentVariable '_JAVA_OPTIONS'
    Remove-ProcessEnvironmentVariable 'JDK_JAVA_OPTIONS'
    $passwordPlain = [Net.NetworkCredential]::new('', $securePassword).Password
    [Environment]::SetEnvironmentVariable($envName, $passwordPlain, 'Process')
    New-Item -ItemType Directory -Path $workingDirectory | Out-Null
    $createdWorkingDirectory = $true
    $keytoolArgs = @(
        '-genkeypair',
        '-alias', 'ahut-release',
        '-keyalg', 'RSA',
        '-keysize', '3072',
        '-validity', '10000',
        '-dname', 'CN=AHUT Wi-Fi Auth Release',
        '-storetype', 'PKCS12',
        '-keystore', $workingKeystorePath,
        '-storepass:env', $envName,
        '-keypass:env', $envName
    )
    & $keytool @keytoolArgs | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $workingKeystorePath -PathType Leaf)) {
        throw 'keytool failed to create the signing keystore.'
    }

    $verifyArgs = @('-list', '-keystore', $workingKeystorePath, '-storetype', 'PKCS12', '-alias', 'ahut-release', '-storepass:env', $envName)
    & $keytool @verifyArgs | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The generated keystore alias could not be read back; initialization failed.'
    }

    [IO.File]::Move($workingKeystorePath, $keystorePath)
    $createdKeystore = $true
    [IO.Directory]::Delete($workingDirectory, $false)
    $createdWorkingDirectory = $false
    $encryptedPassword = ConvertFrom-SecureString -SecureString $securePassword
    $passwordStream = [IO.File]::Open($passwordPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $createdPasswordFile = $true
    try {
        $passwordBytes = [Text.Encoding]::UTF8.GetBytes($encryptedPassword + [Environment]::NewLine)
        $passwordStream.Write($passwordBytes, 0, $passwordBytes.Length)
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    }
    finally {
        $passwordStream.Dispose()
    }
    Write-Host 'Release signing key created and verified.'
    Write-Host "Keystore: $keystorePath"
    Write-Host "Current-user DPAPI password file: $passwordPath"
}
catch {
    if ($createdPasswordFile -and (Test-Path -LiteralPath $passwordPath)) {
        Remove-Item -LiteralPath $passwordPath -Force
    }
    if ($createdKeystore -and (Test-Path -LiteralPath $keystorePath)) {
        Remove-Item -LiteralPath $keystorePath -Force
    }
    if ($createdWorkingDirectory -and (Test-Path -LiteralPath $workingKeystorePath -PathType Leaf)) {
        Remove-Item -LiteralPath $workingKeystorePath -Force
    }
    if ($createdWorkingDirectory -and (Test-Path -LiteralPath $workingDirectory -PathType Container)) {
        [IO.Directory]::Delete($workingDirectory, $false)
    }
    throw
}
finally {
    foreach ($name in $environmentNames) {
        Restore-ProcessEnvironmentVariable $name $oldEnvironment[$name]
    }
    $passwordPlain = $null
    if ($securePassword) { $securePassword.Dispose() }
    if ($confirmPassword) { $confirmPassword.Dispose() }
}
