# Installs the Aether release binaries into jniLibs as libaether.so.
#
# The executable ships as a "native library" on purpose: since Android 10 (API 29) an app
# with targetSdk >= 29 cannot exec anything it writes into its own data dir, so the only
# workable location is nativeLibraryDir, which is populated from jniLibs/<abi>/lib*.so.
#
# Download these two from https://github.com/CluvexStudio/Aether/releases/tag/v1.4.0
# into your Downloads folder (or pass -Source), then run this script:
#
#   aether-android-arm64.tar.gz
#   aether-android-armv7.tar.gz
#
#   powershell -ExecutionPolicy Bypass -File android\install-aether-binary.ps1

param(
    [string]$Source = "$env:USERPROFILE\Downloads",
    [string]$JniLibs = (Join-Path $PSScriptRoot "app\src\main\jniLibs")
)

$ErrorActionPreference = "Stop"

# armv7 is the 32-bit ABI; Android calls it armeabi-v7a. x86/x86_64 are omitted because
# app/build.gradle restricts abiFilters to the two ARM ABIs.
$map = @{
    "aether-android-arm64.tar.gz" = "arm64-v8a"
    "aether-android-armv7.tar.gz" = "armeabi-v7a"
}

$staging = Join-Path $env:TEMP "aether-extract"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }

foreach ($archive in $map.Keys) {
    $abi = $map[$archive]
    $tarball = Join-Path $Source $archive
    if (-not (Test-Path $tarball)) {
        Write-Warning "missing, skipping $abi : $tarball"
        continue
    }

    # Verify against the .sha256 sibling when it was downloaded too. These binaries get
    # exec'd on user devices, so a silent truncated download is worth catching here.
    $shaFile = "$tarball.sha256"
    if (Test-Path $shaFile) {
        $expected = ((Get-Content $shaFile -Raw).Trim() -split '\s+')[0]
        $actual = (Get-FileHash $tarball -Algorithm SHA256).Hash
        if ($expected -ine $actual) {
            throw "checksum mismatch for $archive`n  expected $expected`n  actual   $actual"
        }
        Write-Host "[ok] checksum $archive"
    } else {
        Write-Warning "no .sha256 next to $archive - skipping integrity check"
    }

    $dest = Join-Path $staging $abi
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    tar -xzf $tarball -C $dest
    if ($LASTEXITCODE -ne 0) { throw "tar failed for $archive" }

    # The archive layout has varied between releases, so locate the binary rather than
    # assuming it sits at the root.
    $bin = Get-ChildItem $dest -Recurse -File |
        Where-Object { $_.Name -eq "aether" -or $_.Name -eq "aether.exe" } |
        Select-Object -First 1
    if (-not $bin) { throw "no 'aether' binary inside $archive" }

    $target = Join-Path (Join-Path $JniLibs $abi) "libaether.so"
    New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
    Copy-Item $bin.FullName $target -Force
    Write-Host ("[ok] {0} -> {1} ({2:N1} MB)" -f $abi, $target, ($bin.Length / 1MB))
}

Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "`nDone. Rebuild the APK for the change to take effect."
