[CmdletBinding()]
param(
    [string]$RepositoryPath = (Join-Path $PSScriptRoot '..'),
    [switch]$RequireSingleCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$scopeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
$repositoryRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $RepositoryPath).Path).TrimEnd('\', '/')
if ($repositoryRoot -ne $scopeRoot -and
    -not $repositoryRoot.StartsWith($scopeRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Privacy review must stay within the project containing this script.'
}

$privacyFindings = [Collections.Generic.HashSet[string]]::new()
$textExtensions = @('.kt', '.kts', '.xml', '.properties', '.toml', '.ps1', '.md', '.txt', '.yml', '.yaml', '.json', '.sh', '.bat', '.pro')
$textNames = @('LICENSE', 'NOTICE', 'gradlew', '.gitignore', '.gitattributes')
$forbiddenPath = '(?i)(?:^|/)(?:\.git|\.gradle|\.kotlin|\.idea|\.vscode|\.venv|__pycache__|build|apk_output|signing|captures)(?:/|$)|(?:^|/)(?:local\.properties|keystore\.properties|signing\.properties|\.env(?:\..*)?)$|\.(?:apk|aab|jks|keystore|p12|pfx|pem|key|dpapi|log|hprof|db|sqlite|zip|7z)$'
$contentRules = [ordered]@{
    'PrivateKey' = '(?i)-----BEGIN(?: [A-Z]+)* PRIVATE KEY-----'
    'AccessToken' = '(?i)\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{20,})\b'
    'CredentialInUrl' = '(?i)https?://[^/\s:]+:[^@\s]+@'
    'LocalAbsolutePath' = '(?i)(?<!\w)[A-Z]:[\\/]|/(?:Users|home)/[^/\s]+'
    'PhoneNumber' = '(?<![\w])1[3-9][0-9]{9}(?![\w])'
    'IdentityNumber' = '(?<![\w])[1-9][0-9]{16}[0-9Xx](?![\w])'
    'NumericAccountFixture' = '(?i)\b(?:username|user_account|uid)["'']?\s*[:=]\s*["''](?:,0,)?[0-9]{7,18}["'']'
    'CampusClientAddress' = '(?<![\w.])10\.(?!255\.255\.154\b)(?:[0-9]{1,3}\.){2}[0-9]{1,3}(?![\w.])'
    'DeviceMacAddress' = '(?i)(?<![\w:])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![\w:])'
}

function Get-RepoGitOutput([string[]]$Arguments) {
    $result = @(& git -C $repositoryRoot @Arguments)
    if ($LASTEXITCODE -ne 0) { throw 'A Git privacy inventory command failed.' }
    return $result
}

function Test-IsText([string]$RelativePath) {
    return [IO.Path]::GetExtension($RelativePath) -in $textExtensions -or
        [IO.Path]::GetFileName($RelativePath) -in $textNames
}

function Test-EmailPrivacy([string]$Text, [string]$Label) {
    foreach ($match in [regex]::Matches($Text, '(?i)(?<![\w.+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}')) {
        if ($match.Value -notmatch '(?i)@(?:users\.noreply\.github\.com|example\.invalid|example\.com)$') {
            [void]$privacyFindings.Add("${Label}: PersonalEmail")
        }
    }
}

function Test-ContentPrivacy([string]$Text, [string]$Label) {
    foreach ($rule in $contentRules.GetEnumerator()) {
        if ([regex]::IsMatch($Text, $rule.Value)) { [void]$privacyFindings.Add("${Label}: $($rule.Key)") }
    }
    Test-EmailPrivacy $Text $Label
    $credentialPattern = '(?i)\b(?:password|passwd|pwd|token|api[_-]?key|secret|username|user_account)\s*[:=]\s*["'']([^"''\r\n]+)["'']'
    foreach ($match in [regex]::Matches($Text, $credentialPattern)) {
        $isFictionalTest = $Label -match 'app/src/test/' -and
            $match.Groups[1].Value -in @('test-student', 'example&password')
        if (-not $isFictionalTest) { [void]$privacyFindings.Add("${Label}: LiteralCredential") }
    }
}

function Test-FilePath([string]$RelativePath, [string]$Label) {
    if ($RelativePath -match $forbiddenPath) { [void]$privacyFindings.Add("${Label}: LocalOrSensitiveFile") }
    $extension = [IO.Path]::GetExtension($RelativePath)
    if (-not (Test-IsText $RelativePath) -and $extension -ne '.webp' -and
        $RelativePath -ne 'gradle/wrapper/gradle-wrapper.jar') {
        [void]$privacyFindings.Add("${Label}: UnreviewedBinary")
    }
}

$hasOwnGit = Test-Path -LiteralPath (Join-Path $repositoryRoot '.git')
if ($hasOwnGit) {
    $gitRoot = [IO.Path]::GetFullPath(@(Get-RepoGitOutput -Arguments @('rev-parse', '--show-toplevel'))[0]).TrimEnd('\', '/')
    if ($gitRoot -ne $repositoryRoot) { throw 'Expected an independent Git repository at the review path.' }
    $candidateFiles = @(Get-RepoGitOutput -Arguments @('ls-files', '--cached', '--others', '--exclude-standard') | Sort-Object -Unique)
}
else {
    $candidateFiles = @(Get-ChildItem -LiteralPath $repositoryRoot -Force -Recurse -File | ForEach-Object {
        $_.FullName.Substring($repositoryRoot.Length + 1).Replace('\', '/')
    })
}

foreach ($relativePath in $candidateFiles) {
    Test-FilePath $relativePath $relativePath
    $fullPath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        [void]$privacyFindings.Add("${relativePath}: MissingTrackedFile")
        continue
    }
    if ((Get-Item -LiteralPath $fullPath).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        [void]$privacyFindings.Add("${relativePath}: SymlinkNotReviewed")
        continue
    }
    if (Test-IsText $relativePath) {
        Test-ContentPrivacy ([IO.File]::ReadAllText($fullPath)) $relativePath
    }
    elseif ([IO.Path]::GetExtension($relativePath) -eq '.webp') {
        $bytes = [IO.File]::ReadAllBytes($fullPath)
        if ($bytes.Length -lt 12 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'RIFF' -or
            [Text.Encoding]::ASCII.GetString($bytes, 8, 4) -ne 'WEBP') {
            [void]$privacyFindings.Add("${relativePath}: InvalidWebP")
            continue
        }
        $offset = 12L
        while ($offset + 8 -le $bytes.Length) {
            $chunk = [Text.Encoding]::ASCII.GetString($bytes, [int]$offset, 4)
            $size = [BitConverter]::ToUInt32($bytes, [int]$offset + 4)
            if ($chunk -notin @('VP8X', 'ALPH', 'VP8 ', 'VP8L')) {
                [void]$privacyFindings.Add("${relativePath}: ImageMetadataOrUnknownChunk")
            }
            $offset += 8L + $size + ($size % 2)
        }
        if ($offset -ne $bytes.Length) { [void]$privacyFindings.Add("${relativePath}: InvalidWebPChunks") }
    }
    elseif ($relativePath -eq 'gradle/wrapper/gradle-wrapper.jar') {
        $wrapperHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
        if ($wrapperHash -ne '381DFF8AA434499AA93BC25572B049C8C586A67FAFF2C02F375E4F23E17E49DE') {
            [void]$privacyFindings.Add("${relativePath}: WrapperHashNeedsReview")
        }
    }
}

$revisions = @()
if ($hasOwnGit) {
    $revisions = @(Get-RepoGitOutput -Arguments @('rev-list', '--all'))
    $identityRows = @(Get-RepoGitOutput -Arguments @('log', '--all', '--format=%an|%ae|%cn|%ce'))
    foreach ($row in $identityRows) { Test-EmailPrivacy $row 'GitHistoryIdentity' }
    if ($RequireSingleCommit -and ($revisions.Count -ne 1 -or
        $identityRows[0] -ne 'AHUT Wi-Fi Auth Contributors|contributors@example.invalid|AHUT Wi-Fi Auth Contributors|contributors@example.invalid')) {
        [void]$privacyFindings.Add('GitHistory: ExpectedOneAnonymousSnapshot')
    }
    foreach ($revision in $revisions) {
        $commitMessage = (Get-RepoGitOutput -Arguments @('show', '-s', '--format=%B', $revision)) -join "`n"
        Test-ContentPrivacy $commitMessage 'GitCommitMessage'
        foreach ($relativePath in @(Get-RepoGitOutput -Arguments @('ls-tree', '-r', '--name-only', $revision))) {
            $label = "history/$($revision.Substring(0, 7))/$relativePath"
            Test-FilePath $relativePath $label
            if (Test-IsText $relativePath) {
                $historicalText = (Get-RepoGitOutput -Arguments @('show', "${revision}:$relativePath")) -join "`n"
                Test-ContentPrivacy $historicalText $label
            }
        }
    }
}
elseif ($RequireSingleCommit) {
    [void]$privacyFindings.Add('GitHistory: IndependentSnapshotNotCommitted')
}

if ($privacyFindings.Count -gt 0) {
    $privacyFindings | Sort-Object | ForEach-Object { Write-Output $_ }
    throw "Privacy review failed with $($privacyFindings.Count) findings. Match values were withheld."
}
Write-Output "Privacy review passed: $($candidateFiles.Count) candidate files; $($revisions.Count) reachable commits."
Write-Output 'Heuristic rules require manual review of new content and binaries. Ignored local files are not upload candidates.'
