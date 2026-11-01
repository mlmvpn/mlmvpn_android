# netprobe: build the native library and the APK from the command line.
#   .\build-netprobe.ps1               libnetprobe.so for 4 ABIs (cargo-ndk, 16 KB pages) + debug APK
#   .\build-netprobe.ps1 -SkipNative   APK only (app\src\main\jniLibs already built)
# Result: app\build\outputs\apk\debug\app-debug.apk
param([switch]$SkipNative)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path "C:\gradle-tmp")) { New-Item -ItemType Directory "C:\gradle-tmp" | Out-Null }

if (-not $SkipNative) {
    # Rust lives on I: on this machine (CARGO_HOME / RUSTUP_HOME), not in the user profile.
    $env:CARGO_HOME = "I:\cargo"
    $env:RUSTUP_HOME = "I:\rustup"
    $env:Path = "I:\cargo\bin;C:\Program Files\CMake\bin;$env:Path"
    $env:ANDROID_NDK_HOME = "I:\Android\ndk\android-ndk-r27d"
    $nlog = "C:\gradle-tmp\netprobe-ndk.log"
    Push-Location (Join-Path $here "native")
    try {
        cmd /c "cargo ndk --platform 24 -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ..\app\src\main\jniLibs build --release > $nlog 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed, see $nlog" }
    } finally {
        Pop-Location
    }
    # The libp2p and iroh spikes (PLAN 17.2) and the post-quantum TLS cost (17.3): phone ABIs
    # only, to keep the APK smaller.
    $jni = Join-Path $here "app\src\main\jniLibs"
    foreach ($spike in @("spike-libp2p", "spike-iroh", "spike-pqtls")) {
        Push-Location (Join-Path $here "..\$spike")
        try {
            cmd /c "cargo ndk --platform 24 -t arm64-v8a -t armeabi-v7a -o `"$jni`" build --release --lib > C:\gradle-tmp\netprobe-$spike.log 2>&1"
            if ($LASTEXITCODE -ne 0) { throw "cargo ndk ($spike) failed, see C:\gradle-tmp\netprobe-$spike.log" }
        } finally {
            Pop-Location
        }
    }
    # cargo-ndk also copies by-products of crates that declare cdylib (libif_watch-<hash>.so,
    # libiroh-<hash>.so...). Nothing loads them; keep them out of the APK.
    Get-ChildItem $jni -Recurse -Filter "*.so" | Where-Object { $_.Name -match "-[0-9a-f]{16}\.so$" } | Remove-Item
    & (Join-Path $here "..\check-so.ps1") $jni
}

# The Windows user name has a space, so TEMP is an 8.3 path where AF_UNIX sockets cannot connect:
# every temp variable points at C:\gradle-tmp, for the launcher and for the daemon it forks.
$env:GRADLE_OPTS = "-Djdk.net.unixdomain.tmpdir=C:\gradle-tmp"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:TEMP = "C:\gradle-tmp"
$env:TMP = "C:\gradle-tmp"
$log = "C:\gradle-tmp\netprobe-gradle.log"
Push-Location $here
try {
    # Never pipe gradlew in Windows PowerShell 5.1: a failed build then reads as exit code 0.
    # --no-daemon: a daemon left behind keeps the redirected output open, and a caller that
    # captures this script's output would wait for it for hours.
    cmd /c ".\gradlew.bat :app:assembleDebug --no-daemon -DsocksProxyHost= -DsocksProxyPort= --console=plain > $log 2>&1"
} finally {
    Pop-Location
}
Select-String -Path $log -Pattern "BUILD |FAILED|error:" | ForEach-Object { $_.Line }
Write-Host "Full log: $log"
