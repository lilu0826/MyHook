param([string]$Tools = (Join-Path $PSScriptRoot '..\.tools'))
$ErrorActionPreference = 'Stop'
$Tools = [IO.Path]::GetFullPath($Tools)
New-Item -ItemType Directory -Force $Tools | Out-Null
function Fetch([string]$Url, [string]$Name, [string]$Algorithm, [string]$Hash) {
    $path = Join-Path $Tools $Name
    if (-not (Test-Path -LiteralPath $path)) {
        & curl.exe -L --fail --silent --show-error $Url -o $path
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm $Algorithm).Hash -ne $Hash) { throw "Checksum mismatch: $path" }
    return $path
}
$platform = Fetch 'https://dl.google.com/android/repository/platform-35_r02.zip' 'platform-35.zip' 'SHA1' '0BB560A90A7A2CBD0DD8348224D518B638FE7949'
$buildTools = Fetch 'https://dl.google.com/android/repository/build-tools_r35_windows.zip' 'build-tools-35.zip' 'SHA1' 'AF059BB67CF7786F45EE0DB85E2D24985DF1B4B6'
$api = Fetch 'https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar' 'libxposed-api-102.aar' 'SHA256' '423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0'
Expand-Archive -LiteralPath $platform -DestinationPath (Join-Path $Tools 'platform-35') -Force
Expand-Archive -LiteralPath $buildTools -DestinationPath (Join-Path $Tools 'build-tools-35') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($api)
try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'),(Join-Path $Tools 'libxposed-api-102.jar'),$true) }
finally { $archive.Dispose() }
Write-Output 'Build dependencies ready.'
