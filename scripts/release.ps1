# Builds the release app and packages it in dist\InboxHub. With -InstallTo, also installs it
# there: the old app files are moved to a _previous-version folder and your inbox-hub.toml is kept.
#
#   .\scripts\release.ps1
#   .\scripts\release.ps1 -InstallTo D:\inbox-hub
param([string] $InstallTo)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
if (-not (Test-Path $cargo)) { $cargo = 'cargo' }

if (-not (Test-Path assets\icon.ico)) {
    & $cargo run --release --manifest-path tools\make-icon\Cargo.toml
    if ($LASTEXITCODE) { throw 'icon generation failed' }
}
& $cargo build --release
if ($LASTEXITCODE) { throw 'cargo build failed' }
& "$PSScriptRoot\set-exe-icon.ps1" -Exe target\release\inbox-hub.exe -Icon assets\icon.ico

$dist = Join-Path $root 'dist\InboxHub'
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force -Confirm:$false }
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item target\release\inbox-hub.exe, inbox-hub.example.toml, README.md $dist
Set-Content -Encoding ascii (Join-Path $dist 'Try-Demo.bat') "@echo off`r`nstart `"`" `"%~dp0inbox-hub.exe`" --demo"
Write-Host "Packaged $dist"

if ($InstallTo) {
    New-Item -ItemType Directory -Force $InstallTo | Out-Null
    $old = @('inbox-hub.exe', 'Start-InboxHub.bat', 'Try-Demo.bat', 'README.md', 'inbox-hub.example.toml') |
        ForEach-Object { Join-Path $InstallTo $_ } | Where-Object { Test-Path $_ }
    if ($old) {
        $backup = Join-Path $InstallTo ('_previous-version-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory $backup | Out-Null
        $old | Move-Item -Destination $backup
        if (Test-Path (Join-Path $InstallTo 'inbox-hub.toml')) { Copy-Item (Join-Path $InstallTo 'inbox-hub.toml') $backup }
        Write-Host "Old app moved to $backup"
    }
    Copy-Item (Join-Path $dist '*') $InstallTo -Force
    if (-not (Test-Path (Join-Path $InstallTo 'inbox-hub.toml'))) {
        Copy-Item inbox-hub.example.toml (Join-Path $InstallTo 'inbox-hub.toml')
    }
    Write-Host "Installed to $InstallTo (your inbox-hub.toml was kept)"
}
