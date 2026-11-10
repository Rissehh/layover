# tv-home

A clean, ad-free Android TV home screen: the CleanYourTV launcher
(github.com/Gathin23/cleanyourtv, MIT, imported at upstream 204143007f0b) plus a Layover shelf.

## What is added

- **Continue watching**: what is in progress or next up in LayoverTV (`../tv`), read from
  its home feed `content://com.layover.tv.home/continue`. A card resumes the title in LayoverTV.
- **Picked for you**: titles like the ones watched lately. Seeds are the four most weighted watched titles
  (recent and actually watched), candidates come from Cinemeta (Stremio's free metadata) catalogs of the
  seed's genres, scored on shared genres, director or writer, cast and rating; anime only matches anime.
  Rebuilt when the seeds change or after 12 hours. A card opens the title's page in LayoverTV.
- The shelf sits where the dock app's name was. Up from the dock enters it, Up from the top row goes to
  the top bar, Down from the bottom row returns to the dock. With LayoverTV missing, Home is unchanged.
- Dock: LayoverTV, Netflix, Prime Video, Disney+, YouTube. Tuner app `com.sony.dtv.tvx` behind Inputs.
  All of it (dock, hidden apps, live TV app, columns) is in `app/src/main/res/values/config.xml`: change it
  for your TV.

Code: `HomeShelf` (rows, focus, refresh), `LayoverFeed` (provider client), `Picks` (pure scoring,
`PicksTest`), `Cinemeta` (streamed fetch, one-day disk cache), `ShelfImages`. Hooks in `MainActivity`
are marked by the "Layover shelf" section and the `shelf` field.

## Signing and order

The feed is behind LayoverTV's signature permission `com.layover.permission.READ_HOME`, so this app is
signed with the same key as LayoverTV (see "Signing key" in `../phone/LAYOVER.md`; paths and passwords go in
`local.properties` as `NUVIO_RELEASE_*`, never committed) and must be installed **after** LayoverTV.

## Build and install

    ./gradlew testReleaseUnitTest assembleRelease
    TV_HOST=<tv ip> ./install-on-sony.sh            # LayoverTV + this, addons, Stremio history import, Home takeover
    TV_HOST=<tv ip> ./install-on-sony.sh restore    # Sony's launcher back

Turn on Developer options > ADB debugging on the TV first. The script records the TV's state first
(`install-state-*.txt` next to it, gitignored) and rolls Home back if the takeover does not stick.
`install-on-sony.sh` is written for Sony's stock Android TV launcher (`com.google.android.tvlauncher`); on
another model set `DEVICE=` to its `ro.product.device`. For other brands or Google TV, use upstream's
`build-and-install.sh` / `build-and-install.ps1` (they take your stock launcher package) and install
LayoverTV first with `adb install`.
