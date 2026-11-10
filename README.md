# Layover

Layover is a set of apps for watching movies and shows through Stremio addons, on your phone, your TV and
your PC. It is built on the open-source [Nuvio](https://github.com/NuvioMedia) apps and adds the parts
that make day-to-day watching painless:

- **Tap to play.** Tapping a title plays the best source straight away instead of showing a long list.
- **Automatic fallback.** If a source fails, never shows a picture, or keeps buffering, the player quietly
  moves to the next one. A **Next source** button does the same by hand.
- **Track record.** Every play is remembered, so addons and releases that work for you rise to the top.
- **English first** (phone and PC). Foreign-only dubs go to the back of the line, never removed.
- **Offline downloads** on the phone, sized for a phone, Wi-Fi only, with expired-link recovery and
  auto-delete once watched. Made for flights.
- **Skip intro, skip recap, next episode at the credits** (timestamps from [IntroDB](https://introdb.app),
  free, no key).
- **A clean TV home screen** with Continue watching and "Picked for you" rows, no ads.

Layover does not host, index or provide any content. It plays what the addons you install return, exactly
like Stremio. You are responsible for only using sources you have the right to watch.

## What's in here

| Folder | What it is | Built from | Status |
|---|---|---|---|
| [`phone/`](phone) | Android phone app (Layover) | [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), GPL-3.0 | Used daily |
| [`tv/`](tv) | Android TV app (LayoverTV) | [NuvioTV](https://github.com/NuvioMedia/NuvioTV), GPL-3.0 | Used daily on a Sony Bravia |
| [`tv-home/`](tv-home) | Android TV home screen with Layover rows | [CleanYourTV](https://github.com/Gathin23/cleanyourtv), MIT | Used daily on a Sony Bravia |
| [`pc/`](pc) | Windows desktop app | [NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop), GPL-3.0 | Experimental: builds and starts, playback with real addons not tested |

Each folder is its own Gradle project with its own doc:
[`phone/LAYOVER.md`](phone/LAYOVER.md) (features, settings, every upstream file touched, test checklist),
[`tv/LAYOVER-TV.md`](tv/LAYOVER-TV.md), [`tv-home/README.md`](tv-home/README.md),
[`pc/LAYOVER-DESKTOP.md`](pc/LAYOVER-DESKTOP.md). The `README.md` files inside `phone/`, `tv/` and `pc/` are
upstream Nuvio's, kept as they were.

Layover's own code lives in `features/layover` (phone and PC) and `layover/` (TV). Every place it hooks into
upstream code is marked with a `// LAYOVER` comment, so `grep -rn "LAYOVER"` shows the whole footprint.

## What you need

- **Stremio addons.** Layover has no content of its own. Install the same addons you use in Stremio by
  pasting their manifest URL (the link ending in `manifest.json`) into Settings > Addons. Add Cinemeta
  (`https://v3-cinemeta.strem.io/manifest.json`) for catalogs and show pages.
- **A debrid service (recommended).** Layover's autoplay and downloads prefer direct, cached links, which is
  what debrid addons (Real-Debrid, etc.) return. Your debrid key lives inside the addon URL, so **treat addon
  URLs like passwords**: never commit them, paste them in an issue, or share screenshots of them.
- **To build:** JDK 21 and the Android SDK (Android Studio installs both). The PC app also needs Visual Studio
  2022 Build Tools (C++) and the WebView2 SDK; see `pc/LAYOVER-DESKTOP.md`.

## Build

**New here? Follow [SETUP.md](SETUP.md)**: it goes from nothing to watching on the phone, TV and PC, step by
step, including addons, signing and installing. This section is the short version for developers.

On Windows, `setup-local.ps1` makes your signing key and writes every `local.properties` for you.

Every project reads its private settings from a `local.properties` file in its folder. It is gitignored and
must never be committed. A typical one:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
NUVIO_RELEASE_STORE_FILE=C\:\\Users\\you\\.layover\\layover-release.jks
NUVIO_RELEASE_STORE_PASSWORD=...
NUVIO_RELEASE_KEY_ALIAS=layover
NUVIO_RELEASE_KEY_PASSWORD=...
INTRODB_API_URL=https://api.introdb.app/
```

Make the signing key once (`keytool` comes with the JDK) and keep it outside the repo. Use **one key for the
TV app and tv-home**: tv-home reads LayoverTV's rows through a signature permission, so they must match.

```
keytool -genkeypair -v -keystore ~/.layover/layover-release.jks -alias layover -keyalg RSA -keysize 4096 -validity 10000
```

Then, from each folder (Windows: `gradlew.bat`; Mac/Linux: `./gradlew`):

| App | Command | Output |
|---|---|---|
| Phone | `gradlew :androidApp:assembleFullRelease` | `androidApp/build/outputs/apk/full/release/androidApp-full-arm64-v8a-release.apk` |
| TV | `gradlew :app:assembleFullRelease -x uploadSentryProguardMappingsFullRelease` | `app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk` (use `arm64-v8a` for newer TVs) |
| TV home | `gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| PC | `scripts\fetch-desktop-natives.ps1` once, then `gradlew :composeApp:createDistributable` | `composeApp/build/compose/binaries/main/app/Layover/Layover.exe` |

Run one Gradle build at a time; they are heavy. Layover's tests:
`gradlew :composeApp:testAndroidHostTest --tests "com.nuvio.app.features.layover.*"` (phone),
`gradlew :app:testFullReleaseUnitTest --tests "com.nuvio.tv.layover.*"` (TV),
`gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.layover.*"` (PC).

## Install

Full steps are in [SETUP.md](SETUP.md). In short:

- **Phone:** copy the APK to the phone (Drive, USB, or a local web server, see `phone/LAYOVER.md`), open it,
  allow installs from that app, install. First run: **Continue Without Account**, make a profile, add your
  addons.
- **TV:** turn on Developer options > ADB debugging on the TV, then `adb connect <tv-ip>:5555` and
  `adb install -r <apk>`. Install LayoverTV **before** tv-home. On a Sony Bravia,
  `TV_HOST=<tv-ip> tv-home/install-on-sony.sh` does it all (both apps, your addons from
  `~/.layover/addons.txt`, optional Stremio history import, makes tv-home the Home screen) and
  `... restore` undoes it. Other TVs: SETUP.md step 7.
- **PC:** copy the `Layover` folder somewhere permanent and run `Layover.exe`.

## What it isn't

It isn't a live TV or sports service. Layover plays on-demand movies and episodes from Stremio addons;
there's no channel guide, DVR or live sports. For live sports, look at a streaming TV service (YouTube TV,
Sling, Fubo, DirecTV Stream) or the league's own app, and keep it in the TV home screen's dock next to
LayoverTV.

## Contributing

Fork it and build on top. Keep Layover changes inside the `layover` folders where you can, mark any hook into
upstream code with `// LAYOVER`, and add a test next to the ones already there. Before you push, check that
no `local.properties`, keystore (`*.jks`), addon URL or Stremio key went into a commit.

## Licenses

- `phone/`, `tv/` and `pc/` are forks of Nuvio and stay under the **GNU GPL v3** (see `LICENSE` in each
  folder and at the top level). If you share a build, share its source too.
- `tv-home/` is a fork of CleanYourTV and stays under the **MIT** license (`tv-home/LICENSE`); its font is
  under the SIL Open Font License (`tv-home/FONT-LICENSE-OFL.txt`).

Thanks to the Nuvio and CleanYourTV authors, whose work all of this sits on.
