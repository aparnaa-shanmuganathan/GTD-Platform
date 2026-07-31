# Phase 11 (packaging): builds a native Windows installer (.msi or .exe) via jpackage.
#
# NOT run or verified in this session's environment - this session runs macOS only, and
# jpackage cannot cross-compile a Windows installer from macOS (a real .msi/.exe can only be
# produced by actually running this script on a Windows machine). Reimplements (in native
# PowerShell, since packaging/common/build-runtime.sh's bash won't run on plain Windows without
# WSL/Git Bash) the same two steps packaging/macos/build.sh and packaging/linux/build.sh share:
# jlink a minimal Java 21 runtime, then jpackage it together with the fat jar. See
# docs/DEPLOYMENT.md for the exact commands to run this for real and what to check afterward.
#
# Prerequisites on the build machine:
#   - A real JDK 21 (JAVA_HOME) - e.g. Temurin 21 (https://adoptium.net).
#   - WiX Toolset v3 (https://wixtoolset.org) on PATH - jpackage's Windows msi/exe bundlers both
#     require it.
#   - Before running: `mvn -P package-windows-amd64 clean package` so the fat jar bundles the
#     Windows architecture's real Postgres 16 binaries
#     (io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64).
#
# Usage: $env:JAVA_HOME = "C:\path\to\jdk-21" ; .\packaging\windows\build.ps1 -InstallerType msi
param(
    [ValidateSet("msi", "exe")]
    [string]$InstallerType = "msi"
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME must point at a JDK 21 installation"
    exit 1
}

$RepoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$BuildDir = Join-Path $RepoRoot "packaging\build"
$RuntimeDir = Join-Path $BuildDir "runtime"
$StagingDir = Join-Path $BuildDir "windows-jar-input"
$DistDir = Join-Path $BuildDir "dist\windows"
$FatJar = Join-Path $RepoRoot "target\gtd-platform.jar"
$AppName = "GTD Platform"
$AppVersion = "1.0.0"

if (-not (Test-Path $FatJar)) {
    Write-Error "Fat jar not found at $FatJar - run 'mvn -P package-windows-amd64 clean package' first"
    exit 1
}

& "$env:JAVA_HOME\bin\java.exe" -version

Write-Host "==> Extracting $FatJar to inspect its module dependencies"
$WorkDir = Join-Path $env:TEMP "gtd-jdeps-$(Get-Random)"
New-Item -ItemType Directory -Path $WorkDir | Out-Null
Push-Location $WorkDir
& "$env:JAVA_HOME\bin\jar.exe" xf $FatJar
Pop-Location

Write-Host "==> Running jdeps to compute the minimal JDK module set"
$DetectedModules = & "$env:JAVA_HOME\bin\jdeps.exe" `
    --multi-release 21 `
    --ignore-missing-deps `
    --print-module-deps `
    --recursive `
    --class-path "$WorkDir\BOOT-INF\lib\*" `
    "$WorkDir\BOOT-INF\classes"

Write-Host "jdeps detected: $DetectedModules"

# Same defensive additions as packaging/common/build-runtime.sh (macOS/Linux) - see that
# script's comment for the rationale. Kept in sync manually since this is a separate
# PowerShell implementation, not a shared script.
$ExtraModules = "java.management,java.naming,java.logging,jdk.crypto.ec"
$AllModules = "$DetectedModules,$ExtraModules"

if (Test-Path $RuntimeDir) { Remove-Item -Recurse -Force $RuntimeDir }
Write-Host "==> Running jlink with modules: $AllModules"
& "$env:JAVA_HOME\bin\jlink.exe" `
    --add-modules $AllModules `
    --output $RuntimeDir `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=zip-6

Remove-Item -Recurse -Force $WorkDir

Write-Host "==> Staging jpackage input"
if (Test-Path $StagingDir) { Remove-Item -Recurse -Force $StagingDir }
New-Item -ItemType Directory -Path $StagingDir | Out-Null
Copy-Item $FatJar -Destination $StagingDir

Write-Host "==> Running jpackage (type=$InstallerType)"
if (Test-Path $DistDir) { Remove-Item -Recurse -Force $DistDir }
New-Item -ItemType Directory -Path $DistDir | Out-Null

& "$env:JAVA_HOME\bin\jpackage.exe" `
    --type $InstallerType `
    --name $AppName `
    --app-version $AppVersion `
    --vendor "GTD Platform Project" `
    --input $StagingDir `
    --main-jar "gtd-platform.jar" `
    --runtime-image $RuntimeDir `
    --java-options "-Dgtd.packaged=true" `
    --java-options "-Dspring.profiles.active=packaged" `
    --java-options "-Xmx2g" `
    --win-shortcut `
    --win-menu `
    --dest $DistDir

Write-Host "==> Installer written to $DistDir"
Get-ChildItem $DistDir

Write-Host @"

==> Manual verification checklist for this platform (not run in the macOS-only build session):
  1. Run the produced installer (dist\windows\*.msi or *.exe) on a clean Windows machine with
     no prior Java/Postgres install.
  2. Launch "GTD Platform" from the Start Menu.
  3. Confirm it starts, migrates its schema, and serves http://localhost:8080/ - the same
     checks packaging/macos/build.sh's own verification already proved for macOS.
  4. Confirm the bundled Postgres data directory lands under
     %APPDATA%\GTD Platform\data\pgdata.
"@
