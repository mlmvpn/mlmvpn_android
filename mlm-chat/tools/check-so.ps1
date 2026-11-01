# Size and page alignment of every .so under a folder (MLM CHAT phase 0).
#   .\check-so.ps1 <folder>
# A library loads on 16 KB-page Android devices (Android 15+) only if every LOAD segment is
# aligned to at least 0x4000. Prints one line per file: ABI, size, stripped size, alignment.
param([Parameter(Mandatory = $true)][string]$Dir)
$ErrorActionPreference = "Stop"
$ndk = "I:\Android\ndk\android-ndk-r27d\toolchains\llvm\prebuilt\windows-x86_64\bin"
$readelf = Join-Path $ndk "llvm-readelf.exe"
$strip = Join-Path $ndk "llvm-strip.exe"
$tmp = Join-Path $env:TEMP "check-so"
New-Item -ItemType Directory -Force $tmp | Out-Null
Get-ChildItem -Recurse -Path $Dir -Filter *.so | Sort-Object FullName | ForEach-Object {
    $aligns = & $readelf -lW $_.FullName | Select-String "^\s+LOAD" | ForEach-Object { ($_.Line -split "\s+")[-1] }
    $min = [long](($aligns | ForEach-Object { [Convert]::ToInt64($_, 16) } | Measure-Object -Minimum).Minimum)
    $copy = Join-Path $tmp $_.Name
    Copy-Item $_.FullName $copy -Force
    & $strip --strip-all $copy 2>$null
    $stripped = (Get-Item $copy).Length
    $abi = Split-Path -Leaf (Split-Path -Parent $_.FullName)
    "{0,-12} {1,-28} {2,12:N0} B  stripped {3,12:N0} B  LOAD align 0x{4:X} {5}" -f $abi, $_.Name, $_.Length, $stripped, $min, $(if ($min -ge 0x4000) { "OK-16K" } else { "NOT-16K" })
}
