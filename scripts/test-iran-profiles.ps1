# JVM tests for the on-phone «کانفیگ‌های ایران» builder (store/IranProfilesBuilder.kt).
#   powershell -ExecutionPolicy Bypass -File scripts\test-iran-profiles.ps1 [-Upstream <dir with Serverless-frag{A,B}.jsonc>]
param([string]$Upstream = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jar($group, $artifact, $version) {
    (Get-ChildItem -LiteralPath (Join-Path $cache "$group/$artifact/$version") -Recurse -Filter "$artifact-$version.jar" | Select-Object -First 1).FullName
}
$compiler = Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '1.9.22'
$stdlib = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.22'
$reflect = Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'
$trove = Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'
$annotations = Jar 'org.jetbrains' 'annotations' '13.0'
$json = Jar 'org.json' 'json' '20231013'
$junit = Jar 'junit' 'junit' '4.13.2'
$hamcrest = Jar 'org.hamcrest' 'hamcrest-core' '1.3'
$cp = @($stdlib,$annotations,$json,$junit,$hamcrest) -join ';'
$compilerCp = @($compiler,$stdlib,$reflect,$trove,$annotations) -join ';'
$java = 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe'
$out = Join-Path $repo 'build/iran-profiles-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$files = @(
    (Join-Path $repo 'app/src/main/java/com/mlmvpn/scanner/store/IranProfilesBuilder.kt'),
    (Join-Path $repo 'app/src/test/java/com/mlmvpn/scanner/store/IranProfilesBuilderTest.kt')
)
& $java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $out @files
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location $repo
try {
    $props = @()
    if ($Upstream) { $props += "-Diran.upstream.dir=$Upstream" }
    & $java @props -cp "$out;$cp" org.junit.runner.JUnitCore com.mlmvpn.scanner.store.IranProfilesBuilderTest
    exit $LASTEXITCODE
} finally { Pop-Location }
