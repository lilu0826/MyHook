param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Tools = (Join-Path $PSScriptRoot '..\.tools'),
    [string]$KeyStore,
    [string]$KeyAlias = 'myhook',
    [string]$ExpectedCertificateSha256,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
if (-not $Jdk -or -not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME to JDK 17 or pass -Jdk <path>.'
}
$Tools = [IO.Path]::GetFullPath($Tools)
$gradleConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app\build.gradle.kts') -Raw
$versionNameMatch = [regex]::Match($gradleConfig, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)"')
$versionCodeMatch = [regex]::Match($gradleConfig, 'versionCode\s*=\s*([1-9][0-9]*)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    throw 'Cannot read versionName/versionCode from app/build.gradle.kts.'
}
$versionName = $versionNameMatch.Groups[1].Value
$versionCode = $versionCodeMatch.Groups[1].Value
$usingLocalKey = [string]::IsNullOrWhiteSpace($KeyStore)
if ($Release -and $usingLocalKey) { throw 'Release requires an explicit -KeyStore; local debug fallback is disabled.' }
if ($usingLocalKey) { $KeyStore = Join-Path $PSScriptRoot 'myhook-debug.jks' }
elseif (-not [IO.Path]::IsPathRooted($KeyStore)) { $KeyStore = Join-Path $PSScriptRoot $KeyStore }
if (-not $usingLocalKey -and -not (Test-Path -LiteralPath $KeyStore)) { throw 'Signing keystore does not exist.' }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'Signing alias is required.' }
if (-not $usingLocalKey -and ([string]::IsNullOrWhiteSpace($env:MYHOOK_STORE_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:MYHOOK_KEY_PASSWORD))) {
    throw 'Set MYHOOK_STORE_PASSWORD and MYHOOK_KEY_PASSWORD for the supplied signing key.'
}
if ($ExpectedCertificateSha256 -and $ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9]{64}$') {
    throw 'Expected certificate SHA-256 must contain 64 hex characters.'
}
$savedStorePassword = $env:MYHOOK_STORE_PASSWORD
$savedKeyPassword = $env:MYHOOK_KEY_PASSWORD
if ($usingLocalKey) {
    $env:MYHOOK_STORE_PASSWORD = 'android'
    $env:MYHOOK_KEY_PASSWORD = 'android'
}
Push-Location -LiteralPath $PSScriptRoot
try {
# Windows aapt2 cannot open some non-ASCII absolute paths. Relative arguments
# keep this project buildable from directories such as "lsposed学习".
$Tools = Resolve-Path -LiteralPath $Tools -Relative
$sdkJar = Join-Path $Tools 'platform-35\android-35\android.jar'
$androidTools = Join-Path $Tools 'build-tools-35\android-15'
$apiJar = Join-Path $Tools 'libxposed-api-102.jar'
foreach ($required in @($sdkJar, $apiJar, (Join-Path $androidTools 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required. Run bootstrap-tools.ps1." }
}
function Run-Native([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$jar = Join-Path $Jdk 'bin\jar.exe'
$aapt = Join-Path $androidTools 'aapt2.exe'
# Use a fresh build directory so deleted source files cannot leave stale classes in the APK.
$buildDir = Join-Path '.' ('build\manual-' + [Guid]::NewGuid().ToString('N'))
$dist = '.\dist'
$classes = Join-Path $buildDir 'classes'
$testClasses = Join-Path $buildDir 'tests'
$dexDir = Join-Path $buildDir 'dex'
New-Item -ItemType Directory -Force $classes,$testClasses,$dexDir,$dist | Out-Null
$src = '.\app\src\main'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $src 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
Run-Native $javac (@('-encoding','UTF-8','-source','17','-target','17','-classpath',"$sdkJar;$apiJar",'-d',$classes) + $sources)
Run-Native $javac @('-encoding','UTF-8','-classpath',$classes,'-d',$testClasses,(Join-Path $PSScriptRoot 'tests\LaunchPolicyTest.java'))
Run-Native $java @('-classpath',"$classes;$testClasses",'dev.josh.myhook.LaunchPolicyTest')
Run-Native $jar @('--create','--file',(Join-Path $buildDir 'module.jar'),'-C',$classes,'.')
Run-Native $java @('-cp',(Join-Path $androidTools 'lib\d8.jar'),'com.android.tools.r8.D8','--release','--min-api','26','--lib',$sdkJar,'--classpath',$apiJar,'--output',$dexDir,(Join-Path $buildDir 'module.jar'))
Run-Native $aapt @('compile','--dir',(Join-Path $src 'res'),'-o',(Join-Path $buildDir 'resources.zip'))
$manifest = [xml](Get-Content -LiteralPath (Join-Path $src 'AndroidManifest.xml') -Raw)
$manifest.DocumentElement.SetAttribute('package','dev.josh.myhook')
$manifestPath = Join-Path $buildDir 'AndroidManifest.xml'
$manifest.Save([IO.Path]::GetFullPath((Join-Path $PSScriptRoot $manifestPath)))
$unsigned = Join-Path $buildDir 'unsigned.apk'
Run-Native $aapt @('link','-o',$unsigned,'--manifest',$manifestPath,'-I',$sdkJar,'--min-sdk-version','26','--target-sdk-version','35','--version-code',$versionCode,'--version-name',$versionName,(Join-Path $buildDir 'resources.zip'))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open((Join-Path $PSScriptRoot $unsigned), [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $PSScriptRoot (Join-Path $dexDir 'classes.dex')),'classes.dex') | Out-Null
    $metadataRoot = Join-Path $src 'resources\META-INF\xposed'
    foreach ($file in Get-ChildItem -LiteralPath $metadataRoot -File) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,('META-INF/xposed/' + $file.Name)) | Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $buildDir 'aligned.apk'
Run-Native (Join-Path $androidTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
$key = $KeyStore
if (-not (Test-Path -LiteralPath $key)) {
    if ($Release -or -not $usingLocalKey) { throw 'Release signing key is missing.' }
    Run-Native (Join-Path $Jdk 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass:env','MYHOOK_STORE_PASSWORD','-keypass:env','MYHOOK_KEY_PASSWORD','-alias',$KeyAlias,'-dname','CN=MyHook Local Debug','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$apkName = "MyHook-$versionName.apk"
$output = Join-Path $dist $apkName
Run-Native $java @('-jar',(Join-Path $androidTools 'lib\apksigner.jar'),'sign','--ks',$key,'--ks-key-alias',$KeyAlias,'--ks-pass','env:MYHOOK_STORE_PASSWORD','--key-pass','env:MYHOOK_KEY_PASSWORD','--out',$output,$aligned)
$verification = @(& $java '-jar' (Join-Path $androidTools 'lib\apksigner.jar') 'verify' '--verbose' '--print-certs' $output)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$verification | Write-Output
$certMatch = [regex]::Match(($verification -join "`n"), 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})')
if (-not $certMatch.Success) { throw 'Cannot read APK signing certificate SHA-256.' }
if ($ExpectedCertificateSha256 -and $certMatch.Groups[1].Value -ne $ExpectedCertificateSha256) {
    throw 'APK signing certificate differs from the expected certificate.'
}
Run-Native (Join-Path $androidTools 'zipalign.exe') @('-c','4',$output)
Run-Native $aapt @('dump','badging',$output)
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $PSScriptRoot (Join-Path $dist "$apkName.sha256")), "$hash  $apkName`n", [Text.UTF8Encoding]::new($false))
Write-Output "Built and signature-verified: $(Join-Path $PSScriptRoot $output)"
} finally {
    Pop-Location
    $env:MYHOOK_STORE_PASSWORD = $savedStorePassword
    $env:MYHOOK_KEY_PASSWORD = $savedKeyPassword
}
