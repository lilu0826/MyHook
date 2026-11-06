param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Tools = (Join-Path $PSScriptRoot '..\.tools')
)
$ErrorActionPreference = 'Stop'
if (-not $Jdk -or -not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME to JDK 17 or pass -Jdk <path>.'
}
$Tools = [IO.Path]::GetFullPath($Tools)
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
Run-Native $aapt @('link','-o',$unsigned,'--manifest',$manifestPath,'-I',$sdkJar,'--min-sdk-version','26','--target-sdk-version','35','--version-code','4','--version-name','0.2.0',(Join-Path $buildDir 'resources.zip'))
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
$key = '.\myhook-debug.jks'
if (-not (Test-Path -LiteralPath $key)) {
    Run-Native (Join-Path $Jdk 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','myhook','-dname','CN=MyHook Local Debug','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$output = Join-Path $dist 'MyHook-0.2.0.apk'
Run-Native $java @('-jar',(Join-Path $androidTools 'lib\apksigner.jar'),'sign','--ks',$key,'--ks-key-alias','myhook','--ks-pass','pass:android','--key-pass','pass:android','--out',$output,$aligned)
Run-Native $java @('-jar',(Join-Path $androidTools 'lib\apksigner.jar'),'verify','--verbose','--print-certs',$output)
Run-Native (Join-Path $androidTools 'zipalign.exe') @('-c','4',$output)
Run-Native $aapt @('dump','badging',$output)
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $PSScriptRoot (Join-Path $dist 'MyHook-0.2.0.apk.sha256')), "$hash  MyHook-0.2.0.apk`n", [Text.UTF8Encoding]::new($false))
Write-Output "Built and signature-verified: $(Join-Path $PSScriptRoot $output)"
} finally { Pop-Location }
