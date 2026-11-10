# Layover

Layover is a fork of [Nuvio](https://github.com/NuvioMedia/NuvioMobile) (Kotlin Multiplatform, Android first). It adds three things on top of upstream:

- Offline downloads that are first-class in the library and player
- Stream fallback (try the next source when one fails)
- A stream track record (remember which sources worked)

Identity: applicationId `com.layover.app` (debug: `com.layover.app.debug`), launcher name "Layover". The Kotlin `namespace` stays `com.nuvio.app` on purpose, so upstream R imports keep merging cleanly. The in-app updater is off (`inAppUpdaterEnabled = false`), since updates come from your own builds, not upstream releases. It installs next to Stremio and stock Nuvio without touching them.

## Install on your phone

Build the release APK first (see Build below); use the `arm64-v8a` one, which fits any recent Android phone.

1. Get the file onto the phone: upload it to Google Drive and open it from the Drive app, plug in USB and copy it to the phone's Download folder, or serve its folder on your Wi-Fi (`py -m http.server 8099` in a folder holding only the APK) and open `http://<pc-ip>:8099/<file>.apk` in the phone's browser.
2. Tap the file. Android will say the app that opened it (Drive, Files, Chrome) isn't allowed to install apps. Tap Settings, turn on "Allow from this source", go back.
3. Tap Install. If Play Protect warns about an unknown developer, choose "Install anyway". That is expected for an app you built yourself.
4. Open Layover.

## First run

Inside, the app still shows Nuvio's logo and screens; only the launcher name says Layover.

1. On the sign-in screen tap **Continue Without Account** (everything stays on the phone).
2. "Who's watching?" starts empty. Tap **Manage Profiles** > **Add Profile**, type a name, tap **Create Profile**. Home opens.
3. Layover uses the same addons you use in Stremio. A debrid addon's URL already has your Real-Debrid key inside, so there's nothing else to log into. Treat those URLs like passwords: never paste them in an issue or commit them. In Stremio: Settings > Addons. For each addon, copy its manifest URL (the long link ending in `manifest.json`). Get them onto the phone (a note, or an email to yourself).
4. In Layover: tap **Profile** (bottom right, it opens Settings) > Content & Discovery > Addons. Paste each URL into "Addon URL" and tap **Install Addon**. Add Cinemeta too (`https://v3-cinemeta.strem.io/manifest.json`) if Home stays empty; it supplies the catalogs and show pages.
5. Leave Nuvio's own debrid settings off. Layover only uses the addons, so a failed source never adds a torrent to your RD account.
6. Optional: Profile > Layover to check the defaults below.

## What Layover adds, and where

All Layover settings are in Profile (Settings) > Layover, in the General section right after Playback.

- **Tap to play.** Tapping a movie or episode plays the first source instead of showing the stream list (set once on first launch; change it back under Settings > Playback > Stream auto-play if you prefer the list).
- **Auto fallback.** If a source errors, shows no picture within 8 seconds, or keeps buffering in its first 2 minutes, the player quietly tries the next one ("Trying another source"). Up to 6 tries, then the stream list opens. Settings > Layover > Streaming: Auto fallback, First-frame timeout.
- **Next source.** A button in the player controls, next to Sources, skips to the next best source by hand.
- **Track record.** Every play and download is logged on the phone. Sources and addons that worked for you move up, flaky ones move down.
- **Stream stats.** Settings > Layover > Stream stats: per addon and per quality, how often it worked, average start time, how often you skipped it, and the 20 latest failures. "Clear stream history" at the bottom starts the ranking over (downloads are not touched).
- **Downloads tab.** In the bottom bar between Library and Settings, labelled "Offline" there (the full word doesn't fit five tabs). Shows Ready / Downloading / Failed per item, grouped by show, plays with no internet.
- **Download buttons.** Movie: Download in the action row on the movie page. Episode: the round download icon on each episode card. Whole season: the "Download season" pill under the season title. Layover picks the best phone-sized source for you.
- **Phone-sized picks.** Prefers 1080p, then 720p, at or under 4 GB (movie) or 2 GB (episode). When nothing fits (debrid addons often list only 4K and remuxes), it takes the smallest file at or under Max quality up to 1.5 times the cap, then a 4K file within the cap (not AV1). Remuxes and samples (`...sample.mkv`) are never picked. Settings > Layover > Downloads: Movie size cap, Episode size cap, Max quality.
- **English first.** A release tagged as a foreign dub or foreign subs (FRENCH, VFF, iTA, GERMAN, Castellano, VOSTFR, Hindi and similar, or a non-English flag) with no English tag (ENG, MULTI, Dual Audio, a UK/US flag) goes to the back of the line: in autoplay's first pick, in the fallback order, and in the download picker (an English 720p beats a French 1080p). Nothing is dropped, so a title that only has dubs still plays. Untagged releases count as English. Rules in `layover/streams/Language.kt`; the autoplay hook is one line in upstream's `streams/StreamAutoPlaySelector.kt` (`evaluateAutoPlayStream`).
- **Wi-Fi only.** Downloads wait for Wi-Fi ("Waiting for Wi-Fi") instead of failing. "Use mobile data" on a waiting item lets that one go now. Setting: Wi-Fi only.
- **Never broken at the airport.** A download only shows Ready once the whole file is on the phone and opens. When the server sends no file length, the size the addon stated is what the file has to match. A file under 50 MB on a stream claiming 500 MB or more is the addon's "still downloading" clip and fails the check too. A file that fails that check is downloaded once more from scratch; if it fails again it says "File check failed" and Retry picks a different file. If the RD link expires mid-download, Layover asks the addon for the same file again and carries on from where it stopped.
- **Addons that didn't load.** An addon whose manifest couldn't load (Layover opened before the network was up) shows "Unavailable" and serves nothing. Layover retries it after 3, 10 and 30 s, and again on the triggers below, so it comes back without a manual refresh.
- **Back online, carries on.** When the phone gets a working connection again, or you bring Layover back to the front, addons that didn't load, downloads that couldn't reach your addons ("Couldn't reach your addons") and queued episodes that were waiting on the network start again by themselves, still at most 2 at a time.
- **Phone full.** Before asking the addons for a queued download, Layover checks there is room for a file at the size cap plus 200 MB (minus what running downloads still need), then again for the file it picked. If not, the item says "Not enough space on the phone" with Retry, holds no download slot, and no source is blamed for it. A download that runs out of space part way stops with the same message, without hunting for another source; Retry carries on from the bytes already saved once you've freed space. With little space left, lowering the size caps lets smaller files through.
- **Only what you tap.** Layover never downloads on its own. Smart downloads (next episodes queued automatically) were removed on 2026-11-10: they kept failing and filled the Downloads tab with errors. On first launch after the update, anything they left that never finished is cleared; one that did finish stays until you watch it.
- **Auto-delete watched.** An episode is removed once you've watched 90% of it or marked it watched (never while it's playing). Movies too if "Also delete movies" is on.

## Test checklist

Do these once after installing.

1. **Play with fallback.** Play an episode you know has some dead sources. It should start on its own, maybe after a "Trying another source" toast, without you picking from a list.
2. **Next source.** While something plays, open the controls and tap "Next source". It should switch to another source and keep going.
3. **Season download on Wi-Fi.** On Wi-Fi, open a show and tap "Download season". The Downloads tab should show at most 2 downloading at once and the rest waiting, and each one should end up Ready.
4. **Airplane-mode playback.** Turn on airplane mode, open the Downloads tab, play a Ready episode. It should play start to finish with no errors.
5. **Auto-delete.** Watch a downloaded episode past 90% (or mark it watched), close the player, check the Downloads tab: it should be gone.
6. **Stats screen.** Settings > Layover > Stream stats should show the plays and downloads you just did.

## Signing key

Make your own release key once, outside the repo:

```
keytool -genkeypair -v -keystore ~/.layover/layover-release.jks -alias layover -keyalg RSA -keysize 4096 -validity 10000
```

Then point the four `NUVIO_RELEASE_*` keys in `local.properties` at it (`NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD`). `local.properties` is gitignored: never commit it or the key. Back up the key and those lines somewhere safe (a password manager or an encrypted drive). Use the same key for LayoverTV and tv-home, since tv-home reads LayoverTV's feed through a signature permission. If the key is lost, the next build can't install over the old one: you'd have to uninstall Layover first, which deletes every download and the stream history.

## Build

```
gradlew.bat :androidApp:assembleFullDebug
```

APK: `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`

Release (signed, R8-minified, one APK per ABI):

```
gradlew.bat :androidApp:assembleFullRelease
```

APKs: `androidApp/build/outputs/apk/full/release/androidApp-full-<abi>-release.apk`. Use `arm64-v8a` for the phone, `x86_64` for an emulator. The version name is `nuvio.app.versionName` in `gradle.properties`; the version code comes from upstream's `iosApp/Configuration/Version.xcconfig`. Signing reads the four `NUVIO_RELEASE_*` keys from `local.properties`; without them the release APK comes out unsigned and won't install. Also set `INTRODB_API_URL=https://api.introdb.app/` there (IntroDB, free, no key): it drives Skip Intro/Recap and the Next episode card at the credits; without it they fall back to a fixed point near the end.

Host tests: `gradlew.bat :composeApp:testAndroidHostTest`

Needs JDK 21 and an Android SDK path in `local.properties` (`sdk.dir=...`, never committed). Do not run two Gradle commands at once.

## Merging upstream

Layover sits on upstream Nuvio (NuvioMobile). To pull in a newer upstream release, keep Layover on its own branch on top of an upstream release tag and merge the next tag in.

```
git remote add upstream https://github.com/NuvioMedia/NuvioMobile.git   # once
git fetch upstream --tags
git checkout main
git merge <chosen-tag>                  # a release tag, not upstream's moving branch
git checkout layover
git merge main                          # or rebase, if the history is still local only
git grep -n "LAYOVER:"                  # every hook point; compare with "Hook files" below
gradlew.bat :composeApp:testAndroidHostTest
gradlew.bat :androidApp:assembleFullRelease
```

Resolve conflicts at the `// LAYOVER:` hook points. Walk the "Hook files" list below and check each hook still sits where it should (upstream moves player code often). Make sure the in-app updater is still off and the applicationId is still `com.layover.app`. If upstream's version code went up, bump `nuvio.app.versionName` too, then smoke-test the release APK before it goes on the phone.

Keep Layover changes small and tagged with that marker so they are easy to find. Put new code in new files under a `layover` package where possible, and touch upstream files only to wire in.

## Hook files

Upstream files Layover edits. Re-check these on every upstream merge.

- `androidApp/build.gradle.kts` (applicationId, debug applicationId; Sentry `includeProguardMapping` only when SENTRY_* are set, since its sentry-cli step fails on the space in the repo path)
- `gradle.properties` (`nuvio.app.versionName`)
- `androidApp/src/debug/res/values/strings.xml` (debug launcher label)
- `composeApp/src/androidMain/res/values/strings.xml`, `values-bn/strings.xml`, `values-es/strings.xml` (`app_name`)
- `composeApp/src/androidFull/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.android.kt` (`inAppUpdaterEnabled = false`)
- `composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt` (`LayoverStorage.initialize` next to `DownloadsStorage.initialize`)
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/DownloadsNotificationActionReceiver.kt` (`LayoverStorage.initialize` next to `DownloadsStorage.initialize`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt` (`LayoverSettingsRepository.onProfileChanged()`, `AttemptLog.onProfileChanged()`, `LayoverDownloadQueue.onProfileChanged()` and `LayoverAutoplayMigration.run()` next to `DownloadsRepository.onProfileChanged()`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt` (`LayoverDownloadQueue.start()` and `LayoverAutoplayMigration.run()` right after `DownloadsRepository.ensureLoaded()`; `AutoDeleteWatched.refresh()` and `LayoverWakeUp.onForeground()` in the `AppVisibility.Foreground` branch; `AppScreenTab.Downloads` branch in `handleRootTabClick`; `onOpenDownload` / `onDownloadShowClick` in the `AppTabActions` built for the tabs)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppScreenTab.kt`, `core/ui/NativeTabBridge.kt` (`Downloads` enum value between Library and Settings, both mappings. The iOS Swift tab bar in `iosApp/iosApp/ContentView.swift` has its own `NuvioAppTab` enum and is not wired yet: deferred to the iPhone plan)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt` (`AppTabActions.onOpenDownload` / `onDownloadShowClick`; `AppScreenTab.Downloads` branch in `AppTabHost` showing `DownloadsScreen(onBack = null)`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt` (Downloads item in the floating and classic nav bars, between Library and Settings; the floating bar's label is `compose_nav_layover_downloads` ("Offline") from Layover's own `composeResources/values/strings_layover.xml`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsScreen.kt` (nullable `onBack` (no back arrow at the tab root, system back leaves an in-screen show); `LayoverDownloadsEnvironment` around `NuvioScreen`; Waiting section above the list; no empty message while queued; badge chip before the status line, "Use mobile data" while waiting for Wi-Fi, no Retry on a Failed item that is recovering)
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/RootTabHostTest.kt` (tab lists include `Downloads`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchprogress/WatchProgressRepository.kt` (`AutoDeleteWatched.onProgressSaved(entry)` in `upsert` after the entry is published and persisted)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt` (`AutoDeleteWatched.onPlaybackSource` / `onPlaybackSourceReleased` in the `DisposableEffect(activeSourceUrl)` block)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailActionButtons.kt` (`DetailActions` `download` param, used as the pinned action when shuffle isn't pinned)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt` (both `DetailActions` calls pass `download = rememberLayoverMovieDownloadAction(meta)`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailSeriesContent.kt` (`LayoverSeasonDownloadButton` under the season title in `DetailSeriesContent` and `DetailSeriesListHeader`; `LayoverEpisodeDownloadIcon` overlaid on episode cards in the list branch, `DetailSeriesListEpisode` and `EpisodeHorizontalRow` (new `layoverMeta` param))
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonRepository.kt` (`refreshAddon` `retryAttempt` param and `scheduleManifestRetry` on failure; `retryUnloaded()`, called from `LayoverWakeUp`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamAutoPlaySelector.kt` (`.foreignOnlyLast()` on `candidateStreams` in `evaluateAutoPlayStream`, plus its import)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsModels.kt` (defaulted `DownloadItem` fields: `streamIdentity`, `allowMetered`, `verified`, `recoveryAttempts`, `origin`; `DownloadOrigin` enum)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsRepository.kt` (`useMobileData`; `enqueueFromStream` `allowMetered`/`origin`/`recoveryAttempts` params and stored stream identity; Ready check in `onSuccess`; one clean retry after a failed check, and one `DownloadFailed` record (`DownloadRecovery.onReadyCheckGaveUp`) when the retry fails too (`justFailed`, set in `onFailure` / `failReadyCheck` / the restore hook); `DownloadCompleted` attempt logging; restore hook in `loadFromDisk`/`reattachBackgroundDownload`; link recovery: `DownloadRecovery.onDownloadFailed` in `onFailure` and the restore hook, `DownloadRecovery.onManualRetry` in `retryDownload`, `layoverUpdateItem`, `sanitizeRequestHeaders` made internal)
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/AndroidDownloadScheduler.kt` (Ready check in `execute` before the file is finalized; `verified = true` on completion; Wi-Fi only network type in `buildJob` and the WorkManager constraints; `shouldRetryAndroidDownload` doesn't retry a "No space" / ENOSPC error)
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/downloads/AndroidDownloadLifecycleTest.kt`, `DownloadSubtitlesTest.kt`, `DownloadsTransferWorkerTest.kt` (fixtures serve a body that passes the Ready check)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`, `SettingsRootPage.kt`, `SettingsScreen.kt` (`SettingsPage.Layover` enum value after Playback and `SettingsPage.LayoverStreamStats` right after it, child of Layover; `onLayoverClick` param and Layover row after Playback in the General section of `settingsRootContent`; `layoverSettingsContent` (with `onStreamStatsClick`) and `streamStatsContent` branches in both the phone and tablet `when (page)`, and the `onLayoverClick` argument at both `settingsRootContent` call sites. Neither page is in settings search)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerModels.kt` (`PlayerPlaybackSnapshot.firstFrameRendered`, `isBuffering` and `playerCommandCount`, all defaulted)
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` (ExoPlayer: `layoverFirstFrame` flag set in `onRenderedFirstFrame` and `layoverCommands` counter, both passed to `ExoPlayer.snapshot()`, which also sets `isBuffering` from `STATE_BUFFERING`; the Exo controller is wrapped with `countingCommands` inside AutoSync's `wrap`. libmpv: `NuvioLibmpvView.layoverFirstFrameRendered` cleared on `START_FILE`, set on `PLAYBACK_RESTART`, `layoverCommands` counter, `isBuffering` from `paused-for-cache` in `readSnapshotNow`; `controller()` result wrapped with `countingCommands`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/StreamDestination.kt` (`LayoverPlaybackCandidates.capture(...)` before the player opens, in the autoplay `LaunchedEffect` before `consumeAutoPlay()` and in `openSelectedStream`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt` (`layoverFallback` field)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt` (`layoverFallback.BindEffects(runtime)` after `BindAutoSyncRuntimeEffects()`; `onSnapshot` feeds `layoverFallback.onSnapshot`; `onError` tells it about a claimed credential refresh, then lets it take the error over; `onNextSourceClick` passed to `PlayerControlsShell`)
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt`, `PlayerControlActions.kt` (`onNextSourceClick` param threaded through `PlayerControlsShell`, the legacy `ProgressControls` pill row and `PlayerControlActions`; "Next source" right after Sources)

## Playback fallback

When a stream errors out, turns out to be a placeholder clip, shows no picture within the first-frame timeout (Settings, default 8 s), or stalls in its first 2 minutes (2 rebuffers, or more than 10 s rebuffering), the player moves to the next source with a "Trying another source" toast. Sources are tried in upstream's order (the autoplay candidates, or every stream on the screen for a manual pick), re-sorted by track record, starting from the one that actually started. At most `maxAttempts` sources per play (default 6); after that the sources panel opens. "Next source" in the player controls skips by hand.

Placeholder clips: debrid addons sometimes answer a "cached" pick with a short video instead of the file (a 2-minute "Still downloading" slate) while the stream still claims the real size. A duration under 5 minutes on a stream claiming 500 MB or more, or on a stream named as a sample, fails the attempt as soon as the player knows the duration, and the next source starts from the attempt's resume point, not the clip's position. Samples are left out of the fallback order entirely.

Every attempt leaves one record in the attempt log: Failed, Stalled, SwitchedEarly (skipped within 3 minutes of the first frame), Played (logged once 3 minutes have passed, or when the player closes after a first frame), or Completed (90% reached; replaces that attempt's Played). An attempt abandoned before its first frame (player closed while loading) leaves no record.

Nothing is judged while the app is in the background (coming back restarts the first-frame timeout) or while the phone has no network with internet capability. An error while offline shows upstream's error screen instead of burning through sources; dismissing it leaves the player, as upstream does. Nothing about that attempt is judged until the source changes ("Next source", the sources panel) or a seek or track change once back online. "Next source" while offline still switches but logs nothing. On a network that has internet capability but isn't validated (captive portal, in-flight Wi-Fi), streams are judged and switched as usual, but no attempt records are written. Buffering the app causes itself (seeks, subtitle or audio track changes after the first frame, including libmpv's resume seek, which lands after its first frame) gets a 4 s grace; longer than that counts toward the 10 s buffering budget from the end of the grace, but not as one of the 2 rebuffers, so two slow seeks don't switch sources while a hang that never ends still does about 14 s in. A spell still going when judging resumes (back from the background or offline) gets the same 4 s grace, then counts from its end: as the same rebuffer if it was already being counted, as a new one if it began while nothing was judged. "Next source" with nothing left just opens the sources panel.

Not covered: downloaded files, torrents, media-server items, external players, and iOS for now (its player doesn't report first frame or buffering yet). With fallback off in Layover settings the player behaves exactly like upstream (no switching, no button, no logging). On first launch per profile, upstream's stream autoplay mode goes from "manual" to "first stream" so tapping a title plays it; changing it back in Nuvio's playback settings sticks.

## Auto-delete

Smart downloads were removed on 2026-11-10 (they failed too often to be worth it). `DownloadOrigin.Smart` stays so saved items still decode; `LayoverDownloadQueue.dropAutoDownloadsNow()` runs on queue start and on a profile switch and removes queue entries with that origin plus downloads with it that never finished. Finished ones are kept and go through auto-delete like any other.

`downloads/AutoDeleteWatched.kt`: watched downloads are deleted once progress passes 90% or the title is marked watched, but only for progress or marks made after the file finished downloading (so a download kept for a rewatch survives until you rewatch it). A file that is playing is never deleted; it goes when playback moves on or the player closes. Movies follow the same rule when auto-delete applies to movies.

## Stream stats

Settings > Layover > Stream stats shows what the attempt log says, live: one row per addon and one per quality (4K, 1440p, 1080p, 720p, SD, Unknown), most attempts first, each with attempts, how often it worked, average start time (plays only) and how often it was switched away early. "Worked" and "didn't" use the same outcome sets as the track record ranking (`SUCCESS_OUTCOMES` / `FAILURE_OUTCOMES` in `rank/TrackRecord.kt`), and the whole log counts, not just the last 90 days. Below that are the 20 latest failures (failed to start, stalled, download failed) with title, addon, quality and how long ago; titles come from Downloads when the item is there, otherwise the meta id. "Clear stream history" at the bottom wipes the log, which also resets the ranking.
