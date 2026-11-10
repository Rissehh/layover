# Downloads the desktop video-player libraries (libmpv and friends) that are too big to keep in this repo.
# They come from the upstream NuvioDesktop project at the commit this code was built against.
# Run once from the phone-and-pc folder before building the PC app:  .\scripts\fetch-desktop-natives.ps1
# The phone (Android) build does not need them.

$ErrorActionPreference = "Stop"
$Commit = "09bbaeb7c32ced60c45428d88bf58bf6f7694495"
$Base = "https://media.githubusercontent.com/media/NuvioMedia/NuvioDesktop/$Commit"
$Root = Split-Path -Parent $PSScriptRoot
$List = Join-Path $PSScriptRoot "desktop-natives.txt"

$files = Get-Content $List | Where-Object { $_.Trim() -ne "" }
$i = 0
foreach ($rel in $files) {
    $i++
    $dest = Join-Path $Root $rel
    if (Test-Path $dest) { continue }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Write-Host "[$i/$($files.Count)] $rel"
    Invoke-WebRequest -Uri "$Base/$rel" -OutFile $dest -UseBasicParsing
}
Write-Host "Done: $($files.Count) files in place."
