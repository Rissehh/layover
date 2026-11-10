package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the shelf's artwork into its cards, downsampled to the card width and kept in a small memory cache
 * (about 8 MB: two rows of backdrops, small enough for a 2 GB TV). From the Up next add-on (MIT).
 */
final class ShelfImages {

    /** Artwork is a few hundred KB; anything past this isn't a backdrop. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ShelfImages() {
    }

    /** Shows url in view (blank until loaded), or fallback when url won't load. A view recycled meanwhile is left alone. */
    static void load(ImageView view, String url, String fallback, int targetWidth) {
        load(view, url, fallback, targetWidth, null);
    }

    /** As above; onFailed runs on the main thread when neither image could be loaded (offline, say). */
    static void load(ImageView view, String url, String fallback, int targetWidth, Runnable onFailed) {
        String first = url != null ? url : fallback;
        String second = url != null && fallback != null && !fallback.equals(url) ? fallback : null;
        view.setTag(R.id.shelf_image_url, first);
        Bitmap hit = first == null ? null : CACHE.get(first);
        view.setImageBitmap(hit);
        if (hit != null || first == null) return;
        POOL.execute(() -> {
            Bitmap b = fetch(first, targetWidth);
            if (b == null && second != null) b = fetch(second, targetWidth);
            if (b != null) CACHE.put(first, b);
            Bitmap shown = b;
            MAIN.post(() -> {
                if (!first.equals(view.getTag(R.id.shelf_image_url))) return;
                view.setImageBitmap(shown);
                if (shown == null && onFailed != null) onFailed.run();
            });
        });
    }

    static void trim() {
        CACHE.evictAll();
    }

    private static Bitmap fetch(String url, int targetWidth) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            if (c.getContentLengthLong() > MAX_BYTES) return null;
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) return null;
                }
                data = buf.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            while (o.outWidth / (sample * 2) >= targetWidth || o.outHeight / sample > 2048) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
