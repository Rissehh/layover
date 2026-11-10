# LayoverTV

A fork of NuvioTV (github.com/NuvioMedia/NuvioTV, GPL-3.0, imported at b36db80), built and tested on a Sony
Bravia (XBR-49X800H, Android TV 10, 32-bit ARM); it should run on any Android TV / Google TV device. The
playback half of Layover (`../phone`), without downloads.

## What is different from NuvioTV

- **Own app**: `com.layover.tv` (debug `com.layover.tv.debug`), name LayoverTV, own banner/logo/icon,
  Nuvio's in-app updater off (its APKs carry Nuvio's signature), first-launch account sign-in skipped
  when no account server is configured (LayoverTV builds have none; "Continue without account" is what
  that screen leads to anyway).
- **Ranked autoplay** (`layover/streams`): auto-play is switched once from Manual to First stream, and
  "first stream" means Layover's best: only plain http(s) streams (no WatchHub/Crunchyroll subscription
  links, torrents, YouTube), never samples, debrid-cached first, AV1 last, then the per-addon track record.
- **Automatic fallback** (`layover/playback`, `layover/player/LayoverPlayback.kt`): each attempt is judged
  (8 s first-picture timeout, stalls, "Still downloading" placeholder clips, errors after NuvioTV's own
  retries). A failed source switches to the next with "Trying another source", resuming where the title
  had got to; out of candidates opens the Sources panel instead of the error screen. A surface rebuilt
  after the first frame that never shows a picture again counts as stalled (18 s).
- **Next source** button in the player controls. **Track record** per addon (`layover/rank`).
- **Addon retry** (`layover/addons`): addons whose manifest never loaded are tried again 3/10/30 s after
  start and whenever the network comes back.
- **Home feed** (`layover/home/HomeShelfProvider`): continue watching and history for the home screen
  (`../tv-home`), behind the signature permission `com.layover.permission.READ_HOME`.
- **Stremio history import** (`layover/importer`), adb only:
  `adb shell am broadcast -n com.layover.tv/com.nuvio.tv.layover.importer.StremioImportReceiver --es authKey KEY`
  (`KEY` is your Stremio authKey; `../tv-home/install-on-sony.sh` reads it from `~/.layover/stremio_auth.txt`. Treat it like a password).

Upstream files touched are marked `// LAYOVER:` (`git diff b36db80 -- ':!app/src/main/java/com/nuvio/tv/layover'`).

## Build

Signing: `local.properties` NUVIO_RELEASE_* point at your release key (see "Signing key" in `../phone/LAYOVER.md`; never commit either).
Also in `local.properties`: `INTRODB_API_URL=https://api.introdb.app/` (IntroDB, free, no key): Skip Intro/Recap and
the Next episode card at the credits. Without it those fall back to a fixed point near the end.

    ./gradlew :app:testFullReleaseUnitTest --tests "com.nuvio.tv.layover.*" :app:assembleFullRelease -x uploadSentryProguardMappingsFullRelease

(`-x uploadSentry...`: skips Sentry's upload step, which needs a Sentry account and breaks on spaces in the path.) The TV needs
`app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk`; the emulator the x86 one.

## Verified on the Android TV 10 emulator (2026-10-24)

Addon install by stremio:// link, Stremio import (13 in progress, 17 watched), autoplay on an RD source,
automatic switch on decode errors and on a stall, out of candidates to the Sources panel, Next source
resuming at the same position, home feed. Not verified: the addon retry live, mpv engine, playback on the
real TV (the emulator cannot decode 10-bit HEVC; a real TV can).
