# One-time build setup for Layover on Windows.
# Creates your signing key (if you don't have one) and writes local.properties into phone/, tv/, tv-home/ and pc/.
# Run from the repo folder in PowerShell:   powershell -ExecutionPolicy Bypass -File .\setup-local.ps1
# Nothing here is uploaded anywhere. local.properties and the key are gitignored: never commit them.

param(
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$KeystorePath = "$HOME\.layover\layover-release.jks",
    [string]$Alias = "layover",
    [switch]$Force   # overwrite existing local.properties files
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

function Find-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME "bin\keytool.exe" }
    $candidates += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $onPath = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "keytool not found. Install Android Studio (it ships a JDK), or set JAVA_HOME to a JDK 21."
}

# A .properties value: backslashes doubled, colons escaped.
function Escape-Prop([string]$value) { return $value.Replace('\', '\\').Replace(':', '\:') }

function Read-Password([string]$prompt) {
    if ($env:LAYOVER_KEY_PASSWORD) { return $env:LAYOVER_KEY_PASSWORD }   # for scripted runs
    $secure = Read-Host -AsSecureString $prompt
    return [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

# 1. Android SDK
if (-not (Test-Path (Join-Path $SdkDir "platform-tools"))) {
    throw "Android SDK not found at $SdkDir. Open Android Studio once (it installs the SDK), or pass -SdkDir <path>."
}
Write-Host "Android SDK: $SdkDir"

# 2. Signing key
$keytool = Find-Keytool
if (Test-Path $KeystorePath) {
    Write-Host "Using your existing key: $KeystorePath"
    $password = Read-Password "Password for that key"
} else {
    Write-Host "Creating a signing key at $KeystorePath (back this file and its password up; you need both to update the apps)."
    $password = Read-Password "Choose a key password (6+ characters)"
    if ($password.Length -lt 6) { throw "The password must be at least 6 characters." }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $KeystorePath) | Out-Null
    & $keytool -genkeypair -keystore $KeystorePath -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
        -dname "CN=Layover" -storepass $password -keypass $password | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the key." }
}
# Fails loudly here rather than at the end of a 10-minute build if the password is wrong.
& $keytool -list -keystore $KeystorePath -alias $Alias -storepass $password | Out-Null
if ($LASTEXITCODE -ne 0) { throw "That password doesn't open $KeystorePath (or the alias isn't '$Alias')." }

# 3. local.properties in each app
$content = @(
    "sdk.dir=$(Escape-Prop $SdkDir)"
    "NUVIO_RELEASE_STORE_FILE=$(Escape-Prop $KeystorePath)"
    "NUVIO_RELEASE_STORE_PASSWORD=$password"
    "NUVIO_RELEASE_KEY_ALIAS=$Alias"
    "NUVIO_RELEASE_KEY_PASSWORD=$password"
    "INTRODB_API_URL=https\://api.introdb.app/"
) -join "`n"

foreach ($app in "phone", "tv", "tv-home", "pc") {
    $file = Join-Path $Root "$app\local.properties"
    if ((Test-Path $file) -and -not $Force) {
        Write-Host "  $app\local.properties already exists, left alone (use -Force to overwrite)"
        continue
    }
    [IO.File]::WriteAllText($file, $content + "`n")
    Write-Host "  wrote $app\local.properties"
}
$password = $null

# 4. JDK for Gradle
if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    Write-Host ""
    Write-Host "JAVA_HOME is not set. Gradle needs a JDK 21; Android Studio's works. Run this once, then open a new terminal:"
    Write-Host "  setx JAVA_HOME `"$jbr`""
}
Write-Host ""
Write-Host "Done. Next: build the apps (SETUP.md, step 4)."
