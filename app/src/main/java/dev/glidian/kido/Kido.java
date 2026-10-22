package dev.glidian.kido;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Shared look for KIDŌ: palette, fonts, the 1920x1080 design grid and the app catalog. */
final class Kido {
    private Kido() {}

    // Palette. The accent colors change with the theme; see applyTheme.
    static final int VOID = 0xFF040404;
    static final int PANEL = 0xDB0A0804;
    static final int DARK_TEXT = 0xFF0C0600;
    static final int WARN = 0xFFE8231E;     // alerts stay red in every theme
    static int AMBER, ORANGE, RED, GREEN, LIME, INK, CREAM, FOCUS_RIM;
    static int DIM;     // amber at 42%
    static int LINE;    // amber at 28%

    /** Theme kanji for the settings menu; the names beside them are in R.array.theme_names. */
    static final String[] THEME_KANJI = {"標準", "警報", "紫電"};

    // Per theme: amber (main text and lines), orange (focus fill), red (active tab, alerts), green (OK readouts),
    // lime (inactive tabs), ink (bright text), cream (names), focus rim.
    private static final int[][] PALETTES = {
            {0xFFFFB21E, 0xFFFF8A00, 0xFFE8231E, 0xFF3BD16F, 0xFFB6FF3C, 0xFFFFC23D, 0xFFFFF3D6, 0xFFFFD27A},
            {0xFFFF4A3A, 0xFFE8231E, 0xFFFFB21E, 0xFFFF8A00, 0xFFFF8A5A, 0xFFFF6A50, 0xFFFFE6E0, 0xFFFF9A8A},
            {0xFFA77BFF, 0xFF8FEF3C, 0xFFFF7A1E, 0xFF8FEF3C, 0xFFC8FF6A, 0xFFC3A6FF, 0xFFF0EAFF, 0xFFE2FFB0},
    };

    static int theme = -1;

    static void applyTheme(int index) {
        if (index < 0 || index >= PALETTES.length) index = 0;
        int[] p = PALETTES[index];
        AMBER = p[0];
        ORANGE = p[1];
        RED = p[2];
        GREEN = p[3];
        LIME = p[4];
        INK = p[5];
        CREAM = p[6];
        FOCUS_RIM = p[7];
        DIM = withAlpha(AMBER, 0.42f);
        LINE = withAlpha(AMBER, 0.28f);
        theme = index;
    }

    static {
        applyTheme(0);
    }

    // Fonts, loaded once
    static Typeface mincho, condBold, mono;

    static void loadFonts(Context c) {
        if (mincho != null) return;
        mincho = c.getResources().getFont(R.font.kido_mincho);
        condBold = c.getResources().getFont(R.font.kido_cond_bold);
        mono = c.getResources().getFont(R.font.kido_mono);
    }

    /** Everything is laid out on a 1920x1080 grid and scaled to the real screen. */
    static float scale = 1f;

    static int px(float designPx) {
        return Math.round(designPx * scale);
    }

    /** Dates are written in the language of the interface, so a day name never lands in another language. */
    static Locale dateLocale(Context c) {
        return Locale.forLanguageTag(c.getString(R.string.date_locale));
    }

    /**
     * App categories: the kanji, the name shown beside it (translated in strings.xml) and the color used when
     * an app's own logo has none to offer. The kanji are ones the Mincho subset in res/font carries.
     */
    enum Cat {
        FILM("映画", R.string.cat_film, 0xFFE50914),
        SERIES("番組", R.string.cat_series, 0xFF2D55FF),
        FAMILY("家族", R.string.cat_family, 0xFF3B5BFF),
        VIDEO("動画", R.string.cat_video, 0xFFFF2A3D),
        ANIME("アニメ", R.string.cat_anime, 0xFFF47521),
        MUSIC("音楽", R.string.cat_music, 0xFF1DB954),
        GAMES("遊戯", R.string.cat_games, 0xFF66C0F4),
        STORE("店", R.string.cat_store, 0xFF01A971),
        PLAYER("再生", R.string.cat_player, 0xFFFFB21E),
        NEWS("情報", R.string.cat_news, 0xFF4FA3FF),
        PHOTOS("写真", R.string.cat_photos, 0xFFFFC23D),
        CASTING("映写", R.string.cat_casting, 0xFF3BD1C8),
        LIVE("生放送", R.string.cat_live, 0xFFFF5A36),
        TOOLS("操作", R.string.cat_tools, 0xFF9AA7B0),
        BROWSER("網", R.string.cat_browser, 0xFF4FA3FF),
        SPORTS("放送", R.string.cat_sports, 0xFF3BD16F),
        WALLPAPER("写真", R.string.cat_wallpaper, 0xFFFFC23D),
        APP("アプリ", R.string.cat_app, 0);

        final String kanji;
        final int label, color;

        Cat(String kanji, int label, int color) {
            this.kanji = kanji;
            this.label = label;
            this.color = color;
        }
    }

    /**
     * What KIDŌ shows for an app: its name, a category in kanji and words, a brand color, and an optional
     * white emblem drawn tinted on the panels and hexes (0 when there is none).
     */
    static final class Look {
        final String name, kanji, category;
        final int glow, emblem;

        Look(String name, String kanji, String category, int glow, int emblem) {
            this.name = name;
            this.kanji = kanji;
            this.category = category;
            this.glow = glow;
            this.emblem = emblem;
        }
    }

    /** A catalog entry. A null name keeps the app's own label, which the TV already shows in its language. */
    private static final class Known {
        final String name, slug;
        final Cat cat;
        final int glow;

        Known(String name, String slug, Cat cat, int glow) {
            this.name = name;
            this.slug = slug;
            this.cat = cat;
            this.glow = glow;
        }
    }

    private static final Map<String, Known> LOOKS = new HashMap<>();

    private static void look(String pkg, String name, String slug, Cat cat, int glow) {
        LOOKS.put(pkg, new Known(name, slug, cat, glow));
    }

    static {
        look("com.netflix.ninja", "Netflix", "netflix", Cat.FILM, 0xFFE50914);
        look("com.amazon.amazonvideo.livingroom", "Prime Video", "prime_video", Cat.FILM, 0xFF00A8E1);
        look("com.wbd.stream", "HBO Max", "hbo_max", Cat.SERIES, 0xFF2D55FF);
        look("com.disney.disneyplus", "Disney+", "disney_plus", Cat.SERIES, 0xFF3B5BFF);
        look("com.google.android.youtube.tv", "YouTube", "youtube", Cat.VIDEO, 0xFFFF2A3D);
        look("com.hulu.livingroomplus", "Hulu", "hulu", Cat.SERIES, 0xFF1CE783);
        look("com.peacocktv.peacockandroid", "Peacock", "peacock", Cat.SERIES, 0xFF7B61FF);
        look("com.crunchyroll.crunchyroid", "Crunchyroll", "crunchyroll", Cat.ANIME, 0xFFF47521);
        look("com.spotify.tv.android", "Spotify", "spotify", Cat.MUSIC, 0xFF1DB954);
        look("com.valvesoftware.steamlink", "Steam Link", "steam_link", Cat.GAMES, 0xFF66C0F4);
        look("com.android.vending", "Play Store", "play_store", Cat.STORE, 0xFF01A971);
        look("com.mediatek.wwtv.mediaplayer", "Media Player", "media_player", Cat.PLAYER, 0xFFFFB21E);
        look("com.google.android.youtube.tvmusic", "YouTube Music", "youtube_music", Cat.MUSIC, 0xFFFF0033);
        look("com.google.android.youtube.tvkids", "YouTube Kids", "youtube_kids", Cat.FAMILY, 0xFFFF2A3D);
        look("com.mitv.dream", null, "mi_wallpaper", Cat.WALLPAPER, 0xFFFFC23D);
        look("com.cbs.ott", "Paramount+", "paramount_plus", Cat.FILM, 0xFF0064FF);
        look("com.cbs.ca", "Paramount+", "paramount_plus", Cat.FILM, 0xFF0064FF);
        look("com.apple.atve.androidtv.appletv", "Apple TV", "apple_tv", Cat.FILM, 0xFFE5E5EA);
        look("com.apple.atve.sony.appletv", "Apple TV", "apple_tv", Cat.FILM, 0xFFE5E5EA);
        look("com.plexapp.android", "Plex", "plex", Cat.PLAYER, 0xFFE5A00D);
        look("com.tubitv", "Tubi", "tubi", Cat.FILM, 0xFF7408FF);
        look("tv.pluto.android", "Pluto TV", "pluto_tv", Cat.LIVE, 0xFFFFF200);
        look("tv.twitch.android.app", "Twitch", "twitch", Cat.VIDEO, 0xFF9146FF);
        look("org.xbmc.kodi", "Kodi", "kodi", Cat.PLAYER, 0xFF17B2E7);
        look("com.sling", "Sling", "sling", Cat.LIVE, 0xFF0B7AFF);
        look("com.google.android.youtube.tvunplugged", "YouTube TV", "youtube_tv", Cat.LIVE, 0xFFFF0033);
        look("com.amazon.music.tv", "Amazon Music", "amazon_music", Cat.MUSIC, 0xFF25D1DA);
        look("com.discovery.discoveryplus.mobile", "discovery+", "discovery_plus", Cat.SERIES, 0xFF2175D9);
        look("com.discovery.discoveryplus.androidtv", "discovery+", "discovery_plus", Cat.SERIES, 0xFF2175D9);
        look("com.espn.score_center", "ESPN", "espn", Cat.SPORTS, 0xFFD00000);
        look("org.jellyfin.androidtv", "Jellyfin", "jellyfin", Cat.PLAYER, 0xFFAA5CC3);
        look("org.videolan.vlc", "VLC", "vlc", Cat.PLAYER, 0xFFFF8800);
    }

    /** The main panels on first run, in order. After that, what's pinned is saved and changed from the remote. */
    static final String[] FAVORITES = {
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.disney.disneyplus",
            "com.google.android.youtube.tv",
    };

    /**
     * Installed but not shown: manuals, firmware updaters, store demos, setup and factory tools, which come with
     * the TV but aren't apps anyone opens day to day. Kept short on purpose: anything a person might want
     * (players, casting, live TV, cleaners, help and support) stays visible.
     */
    static final String[] HIDDEN = {
            // Sharp. The MediaTek TV center is the input app, and inputs have their own section.
            "jp.co.sharp.av.android.emanual",
            "jp.co.sharp.av.android.epopdemo",
            "rcota.android.av.sharp.co.jp.rcota",
            "com.mediatek.wwtv.tvcenter",
            // Google
            "com.google.android.tungsten.setupwraith",
            "com.google.android.tvtutorials",
            // Store demo loop found on several brands
            "fusion.android.tv.demo",
            // TCL
            "com.tcl.ocean.instructions",
            "com.tcl.repairguide",
            "com.tcl.exhibit",
            "com.tcl.esticker",
            "com.tcl.versionUpdateApp",
            "com.tcl.UpdatePeripheral",
            "com.tcl.useragreement",
            // Hisense and Vu
            "com.hisense.fte",
            "com.vt.fte",
            "com.hisense.storemode",
            "com.vt.storemode",
            "com.hisense.tv.ota",
            "com.hisense.remoteupgrade",
            "com.vt.tv.ota",
            "com.vt.remoteupgrade",
            // Sony
            "com.sony.dtv.imanual",
            "com.sony.dtv.demomode",
            "com.sony.dtv.demosupport",
            "com.sony.dtv.multiscreendemo",
            // Xiaomi. The factory test app also answers Home, so it should never be one tap away.
            "com.xiaomi.mitv.handbook",
            "com.xiaomi.mitv.manualhelp",
            "com.xiaomi.mitv.updateservice",
            "com.xiaomi.mitv.upgrade",
            "com.duokan.factorytest",
            // Philips
            "org.droidtv.demome",
            "com.Smartdevice.emanual",
    };

    /**
     * The look for an installed app. Apps in the catalog get their name, category and brand color from it.
     * Any other app keeps its own label; its category comes from words in its name and package (then from
     * the category the app declares), and its color from the most distinctive color in its own banner.
     */
    static Look lookFor(Context c, PackageManager pm, ResolveInfo ri, String pkg, String label) {
        Known known = LOOKS.get(pkg);
        int emblem = emblemFor(c, known != null ? known.slug : null, pkg);
        if (known != null) {
            return new Look(known.name != null ? known.name : label, known.cat.kanji,
                    c.getString(known.cat.label), known.glow, emblem);
        }
        Cat cat = categorize(pkg, label, ri.activityInfo.applicationInfo);
        int color = logoColor(pm, ri, pkg);
        if (color == 0) color = cat.color != 0 ? cat.color : AMBER;
        return new Look(label, cat.kanji, c.getString(cat.label), color, emblem);
    }

    /**
     * A themed emblem bundled for the app, if there is one: a white-on-transparent drawable named
     * emblem_<slug> for catalog apps (e.g. emblem_netflix), or emblem_<package, dots as underscores> for any
     * app (e.g. emblem_com_example_app). It is tinted when drawn, so one image works in every theme.
     */
    static int emblemFor(Context c, String slug, String pkg) {
        Resources res = c.getResources();
        int id = slug != null ? res.getIdentifier("emblem_" + slug, "drawable", c.getPackageName()) : 0;
        if (id == 0) id = res.getIdentifier("emblem_" + slugOf(pkg), "drawable", c.getPackageName());
        return id;
    }

    /** A package name as a resource name: lower case, with anything but letters and digits turned into "_". */
    static String slugOf(String pkg) {
        return pkg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    // Matched in order against an app's label and package; the first rule with a matching word wins.
    private static final Object[][] RULES = {
            {Cat.MUSIC, "music", "música", "musica", "spotify", "deezer", "tidal", "radio", "soundcloud", "podcast", "audio"},
            {Cat.ANIME, "crunchyroll", "anime"},
            {Cat.FAMILY, "kids", "niños", "ninos", "cartoon", "infantil"},
            {Cat.GAMES, "game", "juego", "steam", "xbox", "playstation", "geforce", "arcade"},
            {Cat.SPORTS, "sport", "deporte", "espn", "dazn", "futbol", "fútbol"},
            {Cat.NEWS, "news", "noticias", "dw.com", "cnn", "bbc", "euronews"},
            {Cat.LIVE, "iptv", "xuper", "pluto", "en vivo", "livetv", "live tv", "mgstv"},
            {Cat.CASTING, "miracast", "smartshare", "wfd", "milink", "screen mirror", "cast"},
            {Cat.PHOTOS, "gallery", "galería", "galeria", "photo", "foto", "wallpaper", "fondos", "carrusel"},
            {Cat.BROWSER, "browser", "navegador", "chrome", "firefox", "opera"},
            {Cat.STORE, "store", "tienda", "vending", "aptoide"},
            {Cat.PLAYER, "player", "reproductor", "vlc", "kodi", "plex", "mediaexplorer", "videoplayer"},
            {Cat.TOOLS, "manager", "settings", "ajustes", "files", "archivos", "cleaner", "update"},
            {Cat.FILM, "movies", "películas", "peliculas", "cine", "film", "netflix", "prime video", "disney",
                    "paramount", "vix", "mubi", "hbo", "apple tv", "videos"},
            {Cat.SERIES, "series", "max", "star+"},
            {Cat.VIDEO, "youtube", "video", "twitch", "vimeo"},
    };

    static Cat categorize(String pkg, String label, ApplicationInfo ai) {
        String hay = (label + " " + pkg).toLowerCase(Locale.ROOT);
        for (Object[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) if (hay.contains((String) rule[i])) return (Cat) rule[0];
        }
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_AUDIO: return Cat.MUSIC;
                case ApplicationInfo.CATEGORY_GAME: return Cat.GAMES;
                case ApplicationInfo.CATEGORY_VIDEO: return Cat.VIDEO;
                case ApplicationInfo.CATEGORY_IMAGE: return Cat.PHOTOS;
                case ApplicationInfo.CATEGORY_NEWS: return Cat.NEWS;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return Cat.TOOLS;
                default: break;
            }
        }
        return Cat.APP;
    }

    private static final Map<String, Integer> COLOR_CACHE = new HashMap<>();

    /**
     * The color that stands out in an app's banner (or icon): colorful pixels are grouped by hue, weighted by
     * how vivid they are, and the strongest group wins. White, black and grey logos give 0, so the category
     * color is used. The result is brightened a little so it glows on the black screen.
     */
    static int logoColor(PackageManager pm, ResolveInfo ri, String pkg) {
        String key = pkg + ":" + new java.io.File(ri.activityInfo.applicationInfo.sourceDir).lastModified();
        Integer cached = COLOR_CACHE.get(key);
        if (cached != null) return cached;
        int out = 0;
        try {
            Drawable d = ri.activityInfo.loadBanner(pm);
            if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (d == null) d = ri.loadIcon(pm);
            if (d != null) {
                Bitmap b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 64, 36);
                d.draw(new Canvas(b));
                float[] w = new float[24], r = new float[24], g = new float[24], bl = new float[24], hsv = new float[3];
                int px = 0;
                for (int y = 0; y < 36; y++) {
                    for (int x = 0; x < 64; x++) {
                        int c = b.getPixel(x, y);
                        if (Color.alpha(c) < 128) continue;
                        px++;
                        Color.colorToHSV(c, hsv);
                        if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
                        int k = Math.min(23, (int) (hsv[0] / 15f));
                        float wt = hsv[1] * hsv[2];
                        w[k] += wt;
                        r[k] += Color.red(c) * wt;
                        g[k] += Color.green(c) * wt;
                        bl[k] += Color.blue(c) * wt;
                    }
                }
                b.recycle();
                int best = 0;
                for (int k = 1; k < 24; k++) if (w[k] > w[best]) best = k;
                if (px > 0 && w[best] > px * 0.03f) {
                    int c = Color.rgb(Math.round(r[best] / w[best]), Math.round(g[best] / w[best]), Math.round(bl[best] / w[best]));
                    Color.colorToHSV(c, hsv);
                    hsv[1] = Math.max(hsv[1], 0.6f);
                    hsv[2] = Math.max(hsv[2], 0.9f);
                    out = Color.HSVToColor(hsv);
                }
            }
        } catch (RuntimeException ignored) {
            out = 0;
        }
        COLOR_CACHE.put(key, out);
        return out;
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }
}
