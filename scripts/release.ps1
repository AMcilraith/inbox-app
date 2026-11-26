# Builds InboxHub.exe and packages the same folder layout as the GitHub release:
#
#   dist\InboxHub\
#     InboxHub.exe
#     config\inbox-hub.example.toml   (your config\inbox-hub.toml is created on first run)
#     data\                           (sign-in tokens and logs)
#     README.md
#
# -InstallTo D:\InboxHub copies it there, keeping your existing config\ and data\ folders.
param([string]$InstallTo)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

cargo build --release
if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }

$dist = 'dist\InboxHub'
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force -Path "$dist\config", "$dist\data" | Out-Null
Copy-Item target\release\InboxHub.exe "$dist\InboxHub.exe"
Copy-Item inbox-hub.example.toml "$dist\config\inbox-hub.example.toml"
Copy-Item README.md "$dist\README.md"
Write-Host "Packaged $dist"

if ($InstallTo) {
    New-Item -ItemType Directory -Force -Path "$InstallTo\config", "$InstallTo\data" | Out-Null
    Copy-Item "$dist\InboxHub.exe", "$dist\README.md" $InstallTo -Force
    Copy-Item "$dist\config\inbox-hub.example.toml" "$InstallTo\config" -Force
    # Older layouts kept settings next to the exe: move them into config\ once.
    if ((Test-Path "$InstallTo\inbox-hub.toml") -and -not (Test-Path "$InstallTo\config\inbox-hub.toml")) {
        Move-Item "$InstallTo\inbox-hub.toml" "$InstallTo\config\inbox-hub.toml"
    }
    foreach ($old in 'inbox-hub.exe', 'Start-InboxHub.bat', 'Try-Demo.bat', 'inbox-hub.example.toml') {
        if (Test-Path "$InstallTo\$old") { Remove-Item "$InstallTo\$old" }
    }
    Write-Host "Installed to $InstallTo (config\ and data\ kept)"
}
