# Layover Desktop

Layover for the Windows PC. NuvioDesktop (github.com/NuvioMedia/NuvioDesktop, GPL-3.0, branch `Dev` at
3e876f69) is the same Kotlin Multiplatform codebase as the Nuvio phone app, plus a desktop target (mpv
player, HTML controls in WebView2). Layover's phone code (`../phone`) is merged on top, so the features and
settings are the ones described in `../phone/LAYOVER.md`. This folder builds the desktop app only; build the
phone app from `../phone`.

## What is different from Nuvio Desktop

- **Own app**: window, exe, MSI and Start menu entry are "Layover", own MSI upgrade id, data in
  `%APPDATA%\Layover` and `%LOCALAPPDATA%\Layover\Cache`, so it sits next to stock Nuvio Desktop without
  sharing anything. In-app updater off.
- **Everything Layover adds on the phone**, since it lives in shared code: tap to play the best source,
  automatic fallback, track record and Stream stats (Settings > Layover), addon retry, downloads.
- **Next source** button in the desktop player controls (next to Sources), and "Trying another source"
  shown as the player's own notice (app toasts sit under the video on desktop).
- Desktop platform hooks (`desktopMain/.../features/layover`): settings in `layover.properties`, network
  always counts as unmetered and online (no Wi-Fi waits on a PC), file header check, free disk space.

Upstream files touched are marked `// LAYOVER`.

## Build and install

First, once: fetch the video-player libraries (about 250 MB, too big for this repo; they download from
upstream NuvioDesktop):

    .\scripts\fetch-desktop-natives.ps1      # or ./scripts/fetch-desktop-natives.sh

Needs: JDK 21, Visual Studio 2022 Build Tools (C++), and the WebView2 SDK in
`~/.nuget/packages/microsoft.web.webview2/1.0.4078.44` (the NuGet package, unzipped). `local.properties`
needs `sdk.dir` (the Android module is still in the build).

    .\gradlew.bat :composeApp:desktopTest --tests "com.nuvio.app.features.layover.*"   # 317 tests
    .\gradlew.bat :composeApp:run                                                       # dev run
    .\gradlew.bat :composeApp:createDistributable                                       # Layover.exe

The distributable is `composeApp/build/compose/binaries/main/app/Layover/` (mpv, the player bridge and
WebView2Loader ride inside the app jar). Copy that folder to e.g. `%LOCALAPPDATA%\Programs\Layover` and
make a Start menu shortcut to `Layover.exe`. To update, close Layover and copy the new folder over it. An MSI
(`packageMsi`) was not built.

## Updating from upstream

Upstream NuvioDesktop changes come from its `Dev` branch; Layover's own code lives in
`composeApp/src/*/kotlin/com/nuvio/app/features/layover` plus the `// LAYOVER` hooks, the same as in `../phone`.

## Verified (2026-11-10)

Compiles, 317 Layover tests pass on the desktop JVM, dev run and installed exe both start (Layover
window, sign-in screen), data lands in `%APPDATA%\Layover`.

Not verified yet (treat the PC app as experimental): actual playback, fallback and Next source with real addons, the Layover settings
pages, downloads on desktop.
