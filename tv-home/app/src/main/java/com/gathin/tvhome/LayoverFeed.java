package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads LayoverTV's home-screen feed (com.nuvio.tv.layover.home.HomeShelfProvider in projects/layover-tv):
 * what is in progress or next up, and the watch history the picks are built from. The provider is guarded by
 * a signature permission, so this launcher must be signed with the Layover key and installed after LayoverTV.
 * Every call blocks on a binder: run it off the main thread. Returns empty lists when LayoverTV is missing.
 */
final class LayoverFeed {

    static final String PACKAGE = "com.layover.tv";
    private static final String MAIN_ACTIVITY = "com.nuvio.tv.MainActivity";
    private static final Uri CONTINUE = Uri.parse("content://" + PACKAGE + ".home/continue");
    private static final Uri HISTORY = Uri.parse("content://" + PACKAGE + ".home/history");

    private LayoverFeed() {
    }

    /** A Continue watching card: an episode or movie in progress, or the next episode of a show. */
    static final class Resume {
        final String id;
        final String type;
        final String name;
        final String episodeTitle;
        final int season;
        final int episode;
        final String image;
        final String poster;
        final long position;
        final long duration;
        final double progress;
        final String intentUri;

        Resume(String id, String type, String name, String episodeTitle, int season, int episode, String image,
               String poster, long position, long duration, double progress, String intentUri) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.episodeTitle = episodeTitle;
            this.season = season;
            this.episode = episode;
            this.image = image;
            this.poster = poster;
            this.position = position;
            this.duration = duration;
            this.progress = progress;
            this.intentUri = intentUri;
        }

        /** "S1 · E11" for an episode, null for a movie. */
        String episodeLabel() {
            return season > 0 && episode > 0 ? "S" + season + " · E" + episode : null;
        }

        /** Minutes left when the position is known, else 0. */
        long minutesLeft() {
            return position > 0 && duration > position ? Math.max(1, (duration - position) / 60_000L) : 0;
        }
    }

    /** One watched title, newest first; engagement 0..1 (1 = finished). */
    static final class Watched {
        final String id;
        final String type;
        final String name;
        final long lastWatched;
        final double engagement;

        Watched(String id, String type, String name, long lastWatched, double engagement) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.lastWatched = lastWatched;
            this.engagement = engagement;
        }
    }

    /** LayoverTV is installed and signed with this launcher's key (not some other app using its package name). */
    static boolean installed(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            pm.getPackageInfo(PACKAGE, 0);
            return pm.checkSignatures(ctx.getPackageName(), PACKAGE) == PackageManager.SIGNATURE_MATCH;
        } catch (Exception e) {
            return false;
        }
    }

    static List<Resume> continueWatching(Context ctx) {
        List<Resume> out = new ArrayList<>();
        try (Cursor c = query(ctx.getContentResolver(), CONTINUE)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String backdrop = str(c, "backdrop");
                out.add(new Resume(str(c, "content_id"), str(c, "content_type"), str(c, "name"),
                        str(c, "episode_title"), integer(c, "season"), integer(c, "episode"),
                        backdrop != null ? backdrop : str(c, "poster"), str(c, "poster"),
                        lng(c, "position"), lng(c, "duration"), dbl(c, "progress"), str(c, "intent")));
            }
        } catch (RuntimeException ignored) {
            // LayoverTV missing, not permitted (installed in the wrong order) or busy: no row this time.
        }
        return out;
    }

    static List<Watched> history(Context ctx) {
        List<Watched> out = new ArrayList<>();
        try (Cursor c = query(ctx.getContentResolver(), HISTORY)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                out.add(new Watched(str(c, "content_id"), str(c, "content_type"), str(c, "name"),
                        lng(c, "last_watched"), dbl(c, "engagement")));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Resumes a Continue watching card in LayoverTV (its stream screen, which autoplays when that is on). */
    static Intent resumeIntent(String intentUri, String type, String id) {
        try {
            // Only the extras are taken from the feed; where it goes is fixed here: LayoverTV's main activity.
            Intent parsed = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setComponent(new ComponentName(PACKAGE, MAIN_ACTIVITY));
            if (parsed.getExtras() != null) i.putExtras(parsed.getExtras());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return i;
        } catch (Exception e) {
            return detailIntent(type, id);
        }
    }

    /** Opens a title's page in LayoverTV (nuvio://detail/type/id, see DeepLinkParser). */
    static Intent detailIntent(String type, String id) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://detail/" + Uri.encode(type) + "/" + Uri.encode(id)));
        i.setPackage(PACKAGE);
        return i;
    }

    private static Cursor query(ContentResolver resolver, Uri uri) {
        return resolver.query(uri, null, null, null, null);
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.isEmpty() ? null : s;
    }

    private static int integer(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getInt(i);
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0L : c.getLong(i);
    }

    private static double dbl(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0.0 : c.getDouble(i);
    }
}
