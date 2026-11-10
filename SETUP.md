# Setting up Layover

From nothing to watching, step by step. Plan on about an hour the first time (most of it is waiting on
downloads and the first build). The steps are written for a **Windows PC**; on a Mac or Linux the same
commands work with `./gradlew` instead of `gradlew.bat`, and step 3's manual version instead of the script.

You only need the parts you want: the phone app, the TV app (plus the optional home screen), or the PC app.

1. [Get your addons ready](#1-get-your-addons-ready)
2. [Install the tools](#2-install-the-tools)
3. [Download the code and make your signing key](#3-download-the-code-and-make-your-signing-key)
4. [Build the apps](#4-build-the-apps)
5. [Phone: install and first run](#5-phone-install-and-first-run)
6. [TV: install and first run](#6-tv-install-and-first-run)
7. [TV home screen (optional)](#7-tv-home-screen-optional)
8. [PC](#8-pc)
9. [Updating](#9-updating)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Get your addons ready

Layover has no content of its own. Like Stremio, it shows what your **addons** return. Each addon is a
link that ends in `manifest.json`.

- **Cinemeta** (catalogs, posters, show and movie pages). Everyone needs it:
  `https://v3-cinemeta.strem.io/manifest.json`
- **Your stream addons.** If you already use Stremio, use the same ones: open Stremio, go to
  **Addons**, open each addon, and copy its link (the share or copy button). If an addon has a
  **Configure** page (to connect a debrid account, pick qualities or languages), set it up there first,
  then copy the link it gives you.
- **A debrid account is recommended** (Real-Debrid or similar). Layover's autoplay, fallback and downloads
  work best with the direct links debrid addons return.

Put the links in a note you can open on the phone, and in a text file on the PC for the TV
(`C:\Users\<you>\.layover\addons.txt`, one link per line).

> **Addon links are passwords.** A configured addon link usually has your debrid key inside it. Never post
> one in a GitHub issue, a screenshot or a chat, and never commit `addons.txt`.

## 2. Install the tools

1. **Git:** https://git-scm.com/download/win (defaults are fine). It also gives you **Git Bash**, used in step 6.
2. **Android Studio:** https://developer.android.com/studio. Open it once and click through the setup
   wizard (Standard). That installs the Android SDK, `adb`, and a JDK 21 that the builds use.
3. Tell the build where that JDK is. In PowerShell, once:

   ```powershell
   setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
   $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
   [Environment]::SetEnvironmentVariable("Path", "$userPath;$env:LOCALAPPDATA\Android\Sdk\platform-tools", "User")
   ```

   Close and reopen PowerShell afterwards. `adb version` should now print a version.
4. **PC app only:** Visual Studio 2022 Build Tools with "Desktop development with C++", and the WebView2 SDK
   (see `pc/LAYOVER-DESKTOP.md`).

## 3. Download the code and make your signing key

```powershell
cd $HOME
git clone https://github.com/Rissehh/layover.git
cd layover
powershell -ExecutionPolicy Bypass -File .\setup-local.ps1
```

`setup-local.ps1` asks you to choose a password, creates your signing key at
`C:\Users\<you>\.layover\layover-release.jks`, and writes a `local.properties` file into each app folder
(Android SDK location, key location and password, IntroDB for skip intro). Run it again any time; it won't
overwrite files that already exist unless you add `-Force`.

**Back up the key file and its password.** Android only installs an update over an app signed with the same
key. Lose the key and you have to uninstall to update, which deletes downloads and watch history.

<details><summary>Doing it by hand (Mac, Linux, or if the script won't run)</summary>

```
keytool -genkeypair -v -keystore ~/.layover/layover-release.jks -alias layover -keyalg RSA -keysize 4096 -validity 10000
```

Then create `local.properties` in each of `phone/`, `tv/`, `tv-home/` and `pc/`:

```properties
sdk.dir=/Users/you/Library/Android/sdk
NUVIO_RELEASE_STORE_FILE=/Users/you/.layover/layover-release.jks
NUVIO_RELEASE_STORE_PASSWORD=your-password
NUVIO_RELEASE_KEY_ALIAS=layover
NUVIO_RELEASE_KEY_PASSWORD=your-password
INTRODB_API_URL=https://api.introdb.app/
```

On Windows, write paths as `C\:\\Users\\you\\...`. `local.properties` is gitignored; never commit it.
</details>

## 4. Build the apps

Build one at a time; each build is heavy (the first one downloads a lot and can take 10 to 20 minutes).

```powershell
# Phone
cd $HOME\layover\phone
.\gradlew.bat :androidApp:assembleFullRelease
# -> androidApp\build\outputs\apk\full\release\androidApp-full-arm64-v8a-release.apk

# TV
cd $HOME\layover\tv
.\gradlew.bat :app:assembleFullRelease -x uploadSentryProguardMappingsFullRelease
# -> app\build\outputs\apk\full\release\app-full-armeabi-v7a-release.apk   (most TVs)
#    app-full-arm64-v8a-release.apk                                       (newer 64-bit TVs and streaming boxes)

# TV home screen (optional)
cd $HOME\layover\tv-home
.\gradlew.bat assembleRelease
# -> app\build\outputs\apk\release\app-release.apk
```

Not sure which TV APK? After step 6's `adb connect`, run `adb shell getprop ro.product.cpu.abi`: it says
`armeabi-v7a` or `arm64-v8a`.

When you're done building: `.\gradlew.bat --stop` frees the memory the build left running.

## 5. Phone: install and first run

**Install**

1. Get the APK onto the phone. Any of these works:
   - Upload it to Google Drive and open it from the Drive app.
   - Plug the phone in by USB and copy it to the phone's Download folder.
   - Serve it on your Wi-Fi (needs Python). In a folder holding **only** the APK, run `py -m http.server 8099`, then on the
     phone (same Wi-Fi) open `http://<your-pc-ip>:8099/` in Chrome and tap the file. `ipconfig` shows the
     PC's IP (IPv4 Address). Allow it if Windows Firewall asks. Stop the server with Ctrl+C when done.
2. Tap the APK. When Android says that app isn't allowed to install apps, tap **Settings**, turn on
   **Allow from this source**, and go back.
3. Tap **Install**. If Play Protect warns about an unknown developer, choose **Install anyway** (expected for
   an app you built yourself).

**First run**

1. Tap **Continue Without Account**. Everything stays on the phone.
2. **Manage Profiles** > **Add Profile**, type a name, **Create Profile**.
3. Tap **Profile** (bottom right) > **Content & Discovery** > **Addons**. Paste each addon link into
   **Addon URL** and tap **Install Addon**. Do Cinemeta first.
4. Leave the app's own debrid settings off; your addons already handle that.
5. Optional: **Profile** > **Layover** for Wi-Fi only downloads, size caps, max quality, auto-delete and
   stream stats.

Tap any movie or episode and it plays. To download, use the download button on a movie, the round icon on an
episode, or **Download season**. Downloads are in the **Offline** tab.

## 6. TV: install and first run

**Turn on ADB on the TV (once)**

1. On the TV: **Settings** > **System** (on some TVs **Device Preferences**) > **About**. Select **Build**
   7 times until it says you're a developer.
2. Back out to **Settings** > **System** > **Developer options** and turn on **ADB debugging** (and
   **Network debugging** if your TV has that switch).
3. Find the TV's IP: **Settings** > **Network & Internet** > your Wi-Fi network (or **About** > **Status**).

**Install (from PowerShell on the PC, same Wi-Fi)**

```powershell
adb connect <tv-ip>:5555
```

The TV shows "Allow debugging?": tick **Always allow from this computer** and choose **OK**, then run it
again so it says `connected`.

```powershell
adb install -r "$HOME\layover\tv\app\build\outputs\apk\full\release\app-full-armeabi-v7a-release.apk"
```

**Add your addons without typing on the remote.** For each addon link, swap `https://` for `stremio://`
and send it to the TV. LayoverTV opens with it (confirm on the TV if it asks):

```powershell
adb shell am start -a android.intent.action.VIEW -d "stremio://v3-cinemeta.strem.io/manifest.json" com.layover.tv
```

Or all of them from your `addons.txt`:

```powershell
Get-Content "$HOME\.layover\addons.txt" | Where-Object { $_ } | ForEach-Object {
    adb shell am start -a android.intent.action.VIEW -d ("'" + ($_ -replace '^https://', 'stremio://') + "'") com.layover.tv
    Start-Sleep 5
}
```

They then show in LayoverTV's addon settings. Links with special characters need the single quotes the loop adds.

**First run on the TV:** if it offers an account, choose **Continue without account**; when it asks you to
choose your LayoverTV experience, pick **Essential**. LayoverTV is in the TV's app list; play anything and it starts on the best source, switching
on its own if one fails. **Next source** in the player controls switches by hand.

<details><summary>Optional: bring over your Stremio watch history</summary>

Your Stremio **authKey** is in the Stremio web app: open https://web.stremio.com, sign in, press F12,
and in the Console run `JSON.parse(localStorage.profile).auth.key`. Treat it like a password. Then:

```powershell
adb shell am broadcast -n com.layover.tv/com.nuvio.tv.layover.importer.StremioImportReceiver --es authKey '<your-key>'
```

Continue watching and watched marks appear in LayoverTV within a few seconds.
</details>

## 7. TV home screen (optional)

`tv-home` replaces the TV's home screen with a clean one: your apps in a dock, plus **Continue watching**
and **Picked for you** rows from LayoverTV. It is tested on a Sony Bravia running Android TV 10 with the
stock Android TV launcher. Other TVs may work but aren't tested. Everything below can be undone.

Set the dock and the apps it hides in `tv-home\app\src\main\res\values\config.xml` before building (app
package names: `adb shell pm list packages`).

**Install LayoverTV first** (step 6). The home screen reads LayoverTV's rows through a permission that only
works if both are signed with your key and LayoverTV is already installed.

**Sony Bravia, the easy way.** From **Git Bash**:

```bash
cd ~/layover/tv-home
TV_HOST=<tv-ip> ./install-on-sony.sh           # installs both apps, your addons, makes this the Home screen
TV_HOST=<tv-ip> ./install-on-sony.sh restore   # gives Home back to Sony's launcher
```

It records the TV's current state in `install-state-*.txt` first. It also reads `~/.layover/addons.txt`
and, if present, `~/.layover/stremio_auth.txt` (your Stremio authKey on one line) for the history import.

**Any other Android TV, by hand:**

```powershell
adb install -r "$HOME\layover\tv-home\app\build\outputs\apk\release\app-release.apk"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
#   ^ note the current home screen's package (before the slash), e.g. com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
adb shell pm disable-user --user 0 <stock-launcher-package>
```

Press Home on the remote to check. To undo:

```powershell
adb shell pm enable --user 0 <stock-launcher-package>
adb shell cmd package set-home-activity --user 0 <stock-launcher-package>/<its activity from the resolve command>
```

On **Google TV** devices (Chromecast with Google TV, many newer TVs) the stock launcher also runs setup and
account screens; leave it alone and just open LayoverTV from the apps row instead.

## 8. PC

Experimental: it builds and starts, but playback with real addons hasn't been tested yet.

```powershell
cd $HOME\layover\pc
.\scripts\fetch-desktop-natives.ps1                 # once: about 250 MB of video-player libraries
.\gradlew.bat :composeApp:createDistributable
```

Copy `composeApp\build\compose\binaries\main\app\Layover` to e.g. `%LOCALAPPDATA%\Programs\Layover` and run
`Layover.exe`. First run is the same as the phone: Continue Without Account, a profile, your addons.

## 9. Updating

```powershell
cd $HOME\layover
git pull
```

Rebuild the app (step 4) and install it the same way. Phone: open the new APK and choose **Update**. TV:
the same `adb install -r` command. Your profiles, addons, downloads and history stay, as long as the new
build is signed with the same key.

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| Home or search is empty | Install Cinemeta (step 1). |
| An addon shows "Unavailable" | It didn't load when the app started (no network yet). It retries by itself after 3, 10 and 30 s and when the network comes back. If it stays that way, check the link opens in a browser. |
| Nothing plays, or it keeps "Trying another source" | Your stream addons aren't returning direct links. Check each addon's Configure page (debrid connected?) and test the same title in Stremio. |
| Phone: "App not installed" when updating | The new APK was signed with a different key. Use your original key, or uninstall first (deletes downloads and history). |
| TV home screen rows are empty | tv-home was installed before LayoverTV, or with a different key. Uninstall tv-home (`adb uninstall com.gathin.tvhome`) and install it again. |
| `adb connect` fails | TV and PC must be on the same network; ADB debugging (and Network debugging) on; the TV's IP may have changed. |
| Build says `SDK location not found` | `local.properties` is missing in that folder: run `setup-local.ps1` again. |
| Build says `Unsupported class file major version` or no JDK | `JAVA_HOME` isn't set to a JDK 21 (step 2), or PowerShell wasn't reopened after `setx`. |
| Build runs out of memory or the PC crawls | Run one build at a time, close Android Studio, and `gradlew --stop` after each. |
| Skip intro / next episode at the credits never shows | `INTRODB_API_URL` is missing from `local.properties`. Many shows also have no timestamps yet; then the next-episode card shows near the end instead. |
