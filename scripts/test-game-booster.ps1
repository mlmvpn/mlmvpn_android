param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
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
$coroutines = Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.7.3'
if (-not $AndroidSdkRoot) { $AndroidSdkRoot = $env:ANDROID_HOME }
if (-not $AndroidSdkRoot) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $repo 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if ($sdkLine) { $AndroidSdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
$android = Join-Path $AndroidSdkRoot 'platforms/android-34/android.jar'
$cp = @($stdlib,$annotations,$json,$junit,$hamcrest,$coroutines,$android) -join ';'
$compilerCp = @($compiler,$stdlib,$reflect,$trove,$annotations) -join ';'
if (-not $JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr' }
$java = Join-Path $JavaHome 'bin/java.exe'
$out = Join-Path $repo 'build/game-booster-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$main = Join-Path $repo 'app/src/main/java/com/mlmvpn/scanner/engines/game/booster'
$sources = @('model/GameProfile','model/TrafficClass','model/RegionCatalog','probe/ProbeStats','probe/ProbeCodecs','probe/ProbeWork','probe/Trains','decide/RouteDecider','decide/RegionAdvisor','session/SpikeWatch')
$files = @($sources | ForEach-Object { Join-Path $main "$_.kt" })
$files += Join-Path $repo 'app/src/main/java/com/mlmvpn/scanner/utils/ProcessCompat.kt'
$files += Join-Path $repo 'app/src/main/java/com/mlmvpn/scanner/utils/ConnectionOwner.kt'
$files += Join-Path $repo 'app/src/main/java/com/mlmvpn/scanner/utils/GameSessionOwner.kt'
$files += Join-Path $main 'session/GameRoute.kt'
$tests = @('ProbeStatsTest','ProbeCodecsTest','RouteDeciderTest','GameProfileCodecTest','RegionAdvisorTest','SpikeWatchTest','MultiFlowTrainTest','ProbeReorderTest','ProbeWorkTest','ProbeCancellationTest')
$files += @($tests | ForEach-Object { Join-Path $repo "app/src/test/java/com/mlmvpn/scanner/engines/game/booster/$_.kt" })
$tests += 'GameRouteTest'
$files += Join-Path $repo 'app/src/test/java/com/mlmvpn/scanner/engines/game/booster/GameRouteTest.kt'

& $java -Xmx512m -XX:ActiveProcessorCount=2 -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $out @files
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$classes = @($tests | ForEach-Object { 'com.mlmvpn.scanner.engines.game.booster.' + $_ })
& $java -Xmx256m -XX:ActiveProcessorCount=2 -cp "$out;$cp" org.junit.runner.JUnitCore @classes
exit $LASTEXITCODE
