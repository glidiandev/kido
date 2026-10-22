package dev.glidian.kido;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
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

    /** Theme names as shown in the settings menu: kanji, English. */
    static final String[][] THEMES = {{"標準", "ESTÁNDAR"}, {"警報", "ALERTA"}, {"紫電", "VIOLETA"}};

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

    /** What KIDŌ knows about an app: its name, a category label in kanji and English, and a brand color. */
    static final class Look {
        final String name, kanji, category;
        final int glow;

        Look(String name, String kanji, String category, int glow) {
            this.name = name;
            this.kanji = kanji;
            this.category = category;
            this.glow = glow;
        }
    }

    private static final Map<String, Look> LOOKS = new HashMap<>();

    private static void look(String pkg, String name, String kanji, String category, int glow) {
        LOOKS.put(pkg, new Look(name, kanji, category, glow));
    }

    static {
        look("com.netflix.ninja", "Netflix", "映画", "CINE", 0xFFE50914);
        look("com.amazon.amazonvideo.livingroom", "Prime Video", "映画", "CINE", 0xFF00A8E1);
        look("com.wbd.stream", "HBO Max", "番組", "SERIES", 0xFF2D55FF);
        look("com.disney.disneyplus", "Disney+", "番組", "SERIES", 0xFF3B5BFF);
        look("com.google.android.youtube.tv", "YouTube", "動画", "VIDEO", 0xFFFF2A3D);
        look("com.hulu.livingroomplus", "Hulu", "番組", "SERIES", 0xFF1CE783);
        look("com.peacocktv.peacockandroid", "Peacock", "番組", "SERIES", 0xFF7B61FF);
        look("com.crunchyroll.crunchyroid", "Crunchyroll", "アニメ", "ANIME", 0xFFF47521);
        look("com.spotify.tv.android", "Spotify", "音楽", "MÚSICA", 0xFF1DB954);
        look("com.valvesoftware.steamlink", "Steam Link", "遊戯", "JUEGOS", 0xFF66C0F4);
        look("com.android.vending", "Play Store", "店", "TIENDA", 0xFF01A971);
        look("com.mediatek.wwtv.mediaplayer", "Media Player", "再生", "REPRODUCTOR", 0xFFFFB21E);
        look("com.google.android.youtube.tvmusic", "YouTube Music", "音楽", "MÚSICA", 0xFFFF0033);
        look("com.google.android.youtube.tvkids", "YouTube Kids", "家族", "FAMILIA", 0xFFFF2A3D);
        look("com.mitv.dream", "Fondos Mi", "写真", "FONDOS", 0xFFFFC23D);
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
     * Installed but not shown: the manual, the updater and the input app (inputs have their own section).
     * These are the ones on a Sharp AQUOS; other TVs have their own.
     */
    static final String[] HIDDEN = {
            "jp.co.sharp.av.android.emanual",
            "rcota.android.av.sharp.co.jp.rcota",
            "com.mediatek.wwtv.tvcenter",
    };

    static Look lookFor(String pkg, String label) {
        Look l = LOOKS.get(pkg);
        return l != null ? l : new Look(label, "アプリ", "APP", AMBER);
    }

    /**
     * An app the catalog doesn't know: its category comes from words in its name and package (then from
     * the category the app declares), and its color from the most distinctive color in its own banner.
     */
    static Look lookFor(PackageManager pm, ResolveInfo ri, String pkg, String label) {
        Look known = LOOKS.get(pkg);
        if (known != null) return known;
        String[] cat = categorize(pkg, label, ri.activityInfo.applicationInfo);
        int color = logoColor(pm, ri, pkg);
        if (color == 0) color = cat[2] != null ? (int) Long.parseLong(cat[2], 16) : AMBER;
        return new Look(label, cat[0], cat[1], color);
    }

    // {kanji, category, fallback color}. The kanji are the ones the Mincho subset in res/font carries.
    private static final String[] CINE = {"映画", "CINE", "FFE50914"}, SERIES = {"番組", "SERIES", "FF2D55FF"},
            FAMILIA = {"家族", "FAMILIA", "FF3B5BFF"}, VIDEO = {"動画", "VIDEO", "FFFF2A3D"},
            ANIME = {"アニメ", "ANIME", "FFF47521"}, MUSICA = {"音楽", "MÚSICA", "FF1DB954"},
            JUEGOS = {"遊戯", "JUEGOS", "FF66C0F4"}, TIENDA = {"店", "TIENDA", "FF01A971"},
            REPRODUCTOR = {"再生", "REPRODUCTOR", "FFFFB21E"}, NOTICIAS = {"情報", "NOTICIAS", "FF4FA3FF"},
            FOTOS = {"写真", "FOTOS", "FFFFC23D"}, PROYECCION = {"映写", "PROYECCIÓN", "FF3BD1C8"},
            EN_VIVO = {"生放送", "TV EN VIVO", "FFFF5A36"}, HERRAMIENTAS = {"操作", "HERRAMIENTAS", "FF9AA7B0"},
            NAVEGADOR = {"網", "NAVEGADOR", "FF4FA3FF"}, DEPORTES = {"放送", "DEPORTES", "FF3BD16F"},
            APP = {"アプリ", "APP", null};

    private static final Object[][] RULES = {
            {MUSICA, "music", "música", "musica", "spotify", "deezer", "tidal", "radio", "soundcloud", "podcast", "audio"},
            {ANIME, "crunchyroll", "anime"},
            {FAMILIA, "kids", "niños", "ninos", "cartoon", "infantil"},
            {JUEGOS, "game", "juego", "steam", "xbox", "playstation", "geforce", "arcade"},
            {DEPORTES, "sport", "deporte", "espn", "dazn", "futbol", "fútbol"},
            {NOTICIAS, "news", "noticias", "dw.com", "cnn", "bbc", "euronews"},
            {EN_VIVO, "iptv", "xuper", "pluto", "en vivo", "livetv", "live tv", "mgstv"},
            {PROYECCION, "miracast", "smartshare", "wfd", "milink", "screen mirror", "cast"},
            {FOTOS, "gallery", "galería", "galeria", "photo", "foto", "wallpaper", "fondos", "carrusel"},
            {NAVEGADOR, "browser", "navegador", "chrome", "firefox", "opera"},
            {TIENDA, "store", "tienda", "vending", "aptoide"},
            {REPRODUCTOR, "player", "reproductor", "vlc", "kodi", "plex", "mediaexplorer", "videoplayer"},
            {HERRAMIENTAS, "manager", "settings", "ajustes", "files", "archivos", "cleaner", "update"},
            {CINE, "movies", "películas", "peliculas", "cine", "film", "netflix", "prime video", "disney",
                    "paramount", "vix", "mubi", "hbo", "apple tv", "videos"},
            {SERIES, "series", "max", "star+"},
            {VIDEO, "youtube", "video", "twitch", "vimeo"},
    };

    private static String[] categorize(String pkg, String label, ApplicationInfo ai) {
        String hay = (label + " " + pkg).toLowerCase(java.util.Locale.ROOT);
        for (Object[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) if (hay.contains((String) rule[i])) return (String[]) rule[0];
        }
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_AUDIO: return MUSICA;
                case ApplicationInfo.CATEGORY_GAME: return JUEGOS;
                case ApplicationInfo.CATEGORY_VIDEO: return VIDEO;
                case ApplicationInfo.CATEGORY_IMAGE: return FOTOS;
                case ApplicationInfo.CATEGORY_NEWS: return NOTICIAS;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return HERRAMIENTAS;
                default: break;
            }
        }
        return APP;
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
