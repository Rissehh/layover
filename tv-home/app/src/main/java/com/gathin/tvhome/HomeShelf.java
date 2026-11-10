package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The shelf above the dock: LayoverTV's Continue watching, and Picked for you (titles like the ones watched
 * lately, see Picks). It takes the place of the focused app's name while it has anything to show; with
 * LayoverTV missing or nothing watched yet the home screen is exactly as before.
 *
 * Focus: Up from the dock enters the bottom row, Up from the top row goes to the top bar, Down from the
 * bottom row goes back to the dock tile it came from. The feed is read on every return to Home (a local
 * query); the picks are rebuilt when the watched titles they come from change, or after half a day.
 */
final class HomeShelf {

    interface Host {
        void openFromShelf(Intent intent);

        /** Focus back to the dock, on the tile Up came from. */
        void focusDockFromShelf();

        /** Focus up to the top bar. */
        void focusTopBarFromShelf();

        /** The rows changed while Home is showing: place the shelf again over the dock. */
        void shelfChanged();
    }

    private static final String TAG = "HomeShelf";
    private static final long PICKS_MAX_AGE_MS = 12L * 60 * 60 * 1000;
    private static final long FEED_MIN_INTERVAL_MS = 3_000L;
    private static final float FOCUS_SCALE = 1.08f;

    private final Activity activity;
    private final LinearLayout container;
    private final Host host;
    private final float density;
    private final int cardWidthPx;
    private final int cardHeightPx;
    private final boolean animations;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Cinemeta cinemeta;

    private final Row resumeRow;
    private final Row picksRow;
    private long lastFeed;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean shown;

    HomeShelf(Activity activity, LinearLayout container, Host host, int cardWidthDp, boolean animations) {
        this.activity = activity;
        this.container = container;
        this.host = host;
        this.animations = animations;
        density = activity.getResources().getDisplayMetrics().density;
        cardWidthPx = Math.round(cardWidthDp * density);
        cardHeightPx = Math.round(cardWidthDp * density * 9f / 16f);
        prefs = activity.getSharedPreferences("home_shelf", Context.MODE_PRIVATE);
        cinemeta = new Cinemeta(activity.getCacheDir());
        resumeRow = new Row(activity.getString(R.string.shelf_continue));
        picksRow = new Row(activity.getString(R.string.shelf_picks));
        container.setAlpha(0f);
        picksRow.setCards(readPicks());
        watchNetwork();
    }

    boolean hasRows() {
        return resumeRow.visible() || picksRow.visible();
    }

    /**
     * Puts the shelf's bottom just above the dock row (dockTop, root coordinates) and below minTop (the
     * header). When both rows don't fit, the picks row steps aside.
     */
    void place(float dockTop, float minTop) {
        float bottom = dockTop - 22 * density;
        picksRow.fits = true;
        picksRow.apply();
        int h = measure();
        if (bottom - h < minTop && resumeRow.visible() && picksRow.hasCards()) {
            picksRow.fits = false;
            picksRow.apply();
            h = measure();
        }
        container.setY(bottom - h);
    }

    void show() {
        if (shown) return;
        shown = true;
        container.setVisibility(View.VISIBLE);
        container.animate().cancel();
        if (animations) container.animate().alpha(1f).setDuration(220).start();
        else container.setAlpha(1f);
    }

    void hide() {
        if (!shown) return;
        shown = false;
        container.animate().cancel();
        if (animations) container.animate().alpha(0f).setDuration(160).start();
        else container.setAlpha(0f);
        // Focus must not stay on an invisible card.
        if (container.hasFocus()) host.focusDockFromShelf();
    }

    boolean focusBottomRow() {
        if (!shown) return false;
        Row bottom = picksRow.visible() ? picksRow : resumeRow;
        return bottom.focus();
    }

    boolean focusTopRow() {
        if (!shown) return false;
        Row top = resumeRow.visible() ? resumeRow : picksRow;
        return top.focus();
    }

    /** Reads the feed again (and rebuilds the picks when due). Called whenever Home comes back. */
    /** Loads again the artwork that failed, in both rows. Cheap: loaded images come from memory. */
    void retryImages() {
        for (Row row : new Row[]{resumeRow, picksRow}) {
            if (!row.imagesMissing) continue;
            row.imagesMissing = false;
            row.adapter.notifyDataSetChanged();
        }
    }

    /** Home stays on screen after a reboot while Wi-Fi comes up, so artwork that failed then is retried on reconnect. */
    private void watchNetwork() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                main.postDelayed(HomeShelf.this::retryImages, 2_000);   // let DNS settle first
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
        }
    }

    void refresh() {
        retryImages();
        long now = SystemClock.elapsedRealtime();
        if (lastFeed != 0 && now - lastFeed < FEED_MIN_INTERVAL_MS) return;
        lastFeed = now;
        Context app = activity.getApplicationContext();
        io.execute(() -> {
            if (!LayoverFeed.installed(app)) {
                main.post(() -> bind(Collections.emptyList(), null));
                return;
            }
            List<LayoverFeed.Resume> resume = LayoverFeed.continueWatching(app);
            List<LayoverFeed.Watched> history = LayoverFeed.history(app);
            main.post(() -> bind(resume, null));
            List<Picks.Seed> seeds = Picks.seeds(history, System.currentTimeMillis());
            Log.i(TAG, "feed: " + resume.size() + " to continue, " + history.size() + " watched, "
                    + seeds.size() + " seeds");
            String signature = signature(seeds);
            long builtAt = prefs.getLong("picks_built", 0);
            boolean due = !signature.equals(prefs.getString("picks_seeds", ""))
                    || System.currentTimeMillis() - builtAt > PICKS_MAX_AGE_MS;
            if (seeds.isEmpty() || !due) return;
            Set<String> exclude = Cinemeta.ids(history);
            List<Card> picks = new ArrayList<>();
            long started = SystemClock.elapsedRealtime();
            for (Picks.Pick p : cinemeta.picks(seeds, exclude)) picks.add(Card.of(p));
            Log.i(TAG, "picks: " + picks.size() + " in " + (SystemClock.elapsedRealtime() - started) + " ms");
            if (picks.isEmpty()) return;   // offline: keep the last picks
            writePicks(picks, signature);
            main.post(() -> bind(null, picks));
        });
    }

    void trimMemory() {
        ShelfImages.trim();
    }

    void destroy() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        }
        io.shutdownNow();
    }

    // ---- rows --------------------------------------------------------------------------------------

    private void bind(List<LayoverFeed.Resume> resume, List<Card> picks) {
        if (activity.isDestroyed()) return;
        boolean before = hasRows();
        boolean changed = false;
        if (resume != null) {
            List<Card> cards = new ArrayList<>();
            for (LayoverFeed.Resume r : resume) cards.add(Card.of(r));
            changed |= resumeRow.setCards(cards);
        }
        if (picks != null) changed |= picksRow.setCards(picks);
        if (changed || before != hasRows()) host.shelfChanged();
    }

    private int measure() {
        int width = ((View) container.getParent()).getWidth();
        container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return container.getMeasuredHeight();
    }

    /** One row: its heading (with the focused card's details after it) and the cards. */
    private final class Row {
        final View root;
        final TextView heading;
        final TextView detail;
        final RecyclerView list;
        final CardAdapter adapter = new CardAdapter(this);
        final String title;
        boolean fits = true;
        int lastFocused;
        /** Some artwork failed to load (no network yet, after a reboot): bind again when it is back. */
        boolean imagesMissing;

        Row(String title) {
            this.title = title;
            root = LayoutInflater.from(activity).inflate(R.layout.shelf_row, container, false);
            heading = root.findViewById(R.id.shelfHeading);
            detail = root.findViewById(R.id.shelfDetail);
            list = root.findViewById(R.id.shelfList);
            heading.setText(title);
            list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
            list.setItemAnimator(null);
            list.setAdapter(adapter);
            container.addView(root);
            apply();
        }

        boolean hasCards() {
            return !adapter.cards.isEmpty();
        }

        boolean visible() {
            return hasCards() && fits;
        }

        void apply() {
            root.setVisibility(visible() ? View.VISIBLE : View.GONE);
        }

        /** True when the cards changed. */
        boolean setCards(List<Card> cards) {
            if (cards.equals(adapter.cards)) return false;
            boolean hadFocus = list.hasFocus();
            String focusedId = hadFocus && lastFocused < adapter.cards.size() ? adapter.cards.get(lastFocused).id : null;
            adapter.cards = new ArrayList<>(cards);
            adapter.notifyDataSetChanged();
            apply();
            if (hadFocus) {
                int pos = 0;
                for (int i = 0; i < cards.size(); i++) if (cards.get(i).id.equals(focusedId)) pos = i;
                lastFocused = pos;
                list.post(this::focus);
            }
            return true;
        }

        boolean focus() {
            if (!visible()) return false;
            int pos = Math.min(lastFocused, adapter.getItemCount() - 1);
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
            if (vh != null) return vh.itemView.requestFocus();
            list.scrollToPosition(pos);
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.requestFocus();
            });
            return true;
        }

        Row above() {
            return this == picksRow && resumeRow.visible() ? resumeRow : null;
        }

        Row below() {
            return this == resumeRow && picksRow.visible() ? picksRow : null;
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        final Row row;
        List<Card> cards = new ArrayList<>();

        CardAdapter(Row row) {
            this.row = row;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(activity).inflate(R.layout.shelf_card, parent, false);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.width = cardWidthPx;
            lp.height = cardHeightPx;
            v.setLayoutParams(lp);
            return new CardHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Card c = cards.get(position);
            h.title.setText(c.name);
            ShelfImages.load(h.image, c.image, c.fallbackImage, cardWidthPx, () -> row.imagesMissing = true);
            if (c.progress > 0.01) {
                h.progress.setVisibility(View.VISIBLE);
                ViewGroup.LayoutParams lp = h.fill.getLayoutParams();
                lp.width = Math.max(1, Math.round((cardWidthPx - 16 * density) * (float) Math.min(1.0, c.progress)));
                h.fill.setLayoutParams(lp);
            } else {
                h.progress.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> host.openFromShelf(c.intent()));
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                if (animations) v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                else {
                    v.setScaleX(s);
                    v.setScaleY(s);
                }
                if (focused) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        row.lastFocused = pos;
                        row.detail.setText(cards.get(pos).detail);
                    }
                } else {
                    // Focus may be moving to the next card of this row: clear only once it has left the row.
                    row.list.post(() -> {
                        if (!row.list.hasFocus()) row.detail.setText("");
                    });
                }
            });
            h.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP: {
                        Row up = row.above();
                        if (up != null) up.focus();
                        else host.focusTopBarFromShelf();
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_DOWN: {
                        Row down = row.below();
                        if (down != null) down.focus();
                        else host.focusDockFromShelf();
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == getItemCount() - 1;
                    default:
                        return false;
                }
            });
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final View progress;
        final View fill;

        CardHolder(View v) {
            super(v);
            image = v.findViewById(R.id.shelfImage);
            title = v.findViewById(R.id.shelfTitle);
            progress = v.findViewById(R.id.shelfProgress);
            fill = v.findViewById(R.id.shelfProgressFill);
        }
    }

    /** What a card shows and opens. Equal cards mean nothing to redraw. */
    private static final class Card {
        final String id;
        final String type;
        final String name;
        final String image;
        /** Shown when image won't load (a missing backdrop): the poster. */
        final String fallbackImage;
        final String detail;
        final double progress;
        final String intentUri;   // a Continue watching card resumes; a pick opens the title

        Card(String id, String type, String name, String image, String fallbackImage, String detail, double progress,
             String intentUri) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.image = image;
            this.fallbackImage = fallbackImage;
            this.detail = detail;
            this.progress = progress;
            this.intentUri = intentUri;
        }

        static Card of(LayoverFeed.Resume r) {
            StringBuilder d = new StringBuilder();
            String ep = r.episodeLabel();
            if (ep != null) d.append(ep);
            if (r.episodeTitle != null) d.append(d.length() > 0 ? "  " : "").append(r.episodeTitle);
            long left = r.minutesLeft();
            if (left > 0) d.append(d.length() > 0 ? "  ·  " : "").append(left).append(" min left");
            return new Card(r.id + "|" + r.season + "|" + r.episode, r.type, r.name, r.image, r.poster, d.toString(),
                    r.progress, r.intentUri);
        }

        static Card of(Picks.Pick p) {
            Picks.Title t = p.title;
            String image = t.background != null ? t.background : t.poster;
            StringBuilder d = new StringBuilder();
            if (t.year > 0) d.append(t.year);
            if (t.rating > 0) d.append(d.length() > 0 ? "  ·  " : "").append("IMDb ").append(t.rating);
            d.append(d.length() > 0 ? "  ·  " : "").append("Because you watched ").append(p.because);
            return new Card(t.id, t.type, t.name, image, t.poster, d.toString(), 0, null);
        }

        Intent intent() {
            String contentId = id.split("\\|")[0];
            return intentUri != null ? LayoverFeed.resumeIntent(intentUri, type, contentId)
                    : LayoverFeed.detailIntent(type, contentId);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Card)) return false;
            Card c = (Card) o;
            return id.equals(c.id) && eq(name, c.name) && eq(image, c.image) && eq(detail, c.detail)
                    && Math.abs(progress - c.progress) < 0.005;
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    // ---- stored picks --------------------------------------------------------------------------------

    private static String signature(List<Picks.Seed> seeds) {
        StringBuilder b = new StringBuilder();
        for (Picks.Seed s : seeds) b.append(s.id).append(',');
        return b.toString();
    }

    private void writePicks(List<Card> picks, String signature) {
        JSONArray a = new JSONArray();
        try {
            for (Card c : picks) {
                a.put(new JSONObject().put("id", c.id).put("type", c.type).put("name", c.name)
                        .put("image", c.image).put("fallback", c.fallbackImage).put("detail", c.detail));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("picks", a.toString()).putString("picks_seeds", signature)
                .putLong("picks_built", System.currentTimeMillis()).apply();
    }

    private List<Card> readPicks() {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("picks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Card(o.getString("id"), o.optString("type"), o.optString("name"),
                        o.isNull("image") ? null : o.optString("image"),
                        o.isNull("fallback") ? null : o.optString("fallback", null), o.optString("detail"), 0, null));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
