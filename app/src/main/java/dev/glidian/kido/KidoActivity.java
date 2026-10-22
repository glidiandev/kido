package dev.glidian.kido;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * KIDŌ home screen. The screen is a big board with three sections (main panels, a honeycomb of the
 * other apps, inputs and settings). Moving between sections pans the board and the new section draws
 * itself in. A fixed HUD on top holds the clock, section tabs and a status block that takes the
 * color of whatever is focused.
 */
public class KidoActivity extends Activity {
    private static final int[][] ZONES = {{0, 0}, {1900, 220}, {720, 1180}};
    private static final String[][] ZONE_NAMES = {
            {"主要", "SELECCIÓN PRINCIPAL", "INICIO"}, {"アプリ", "APLICACIONES", "APPS"}, {"制御系統", "CONTROL DEL SISTEMA", "SISTEMA"}};
    private static final String[] TAB_KANJI = {"主要", "アプリ", "制御"};
    private static final String[] PHASES = {"ENLACE", "AUTENT.", "BÚFER", "DECODIF.", "AUDIO", "VIDEO", "SINCRO", "LISTO"};
    private static final long IDLE_MS = 45_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator snap = new PathInterpolator(0.77f, 0f, 0.18f, 1f);
    // Starts fast and settles softly: the motion answers the remote at once, so the TV feels quick.
    private final PathInterpolator fluid = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private FrameLayout root, board, launch, menu;
    private View glow;
    private FrameLayout plate;
    private TextView plateKanji, plateEn, subLeft, subRight, footerZone, toast;
    private GradientDrawable plateBg, subBg, glowBg;
    private final TextView[] tabText = new TextView[6];
    private final GlowView[] tabBar = new GlowView[3];
    private final GlowView[] zoneGlow = new GlowView[3];
    private GlowView plateHalo;
    private final GradientDrawable[] tabBg = new GradientDrawable[3];
    private TextView dateText;
    private View menuOrigin;
    private boolean appsChanged;
    private boolean holding;
    private long lastMoveAt, landedAt;
    private View pendingStatus;
    private static final long HOLD_STEP_MS = 125, EDGE_PAUSE_MS = 550, SETTLE_MS = 180;
    private TextView readLink, readUpdated;
    private float wifiLevel = 1f;
    private String wifiRate = "";
    private final TraceView[] zoneFrames = new TraceView[3];
    private final View[] zoneAccents = new View[3];
    private final TextView[] zoneTags = new TextView[3];
    private final String[] zoneTagFormat = new String[3];
    private final int[] zoneTagCount = new int[3];

    private TextView readCh, readName;
    private PillsView readPills, detailPills;
    private TextView detailHead, detailRate;
    private final List<TextView> linkRows = new ArrayList<>();
    private LinearLayout linkList;
    private android.widget.ScrollView linkBox;
    private int linkTop;

    private final List<View> favViews = new ArrayList<>();
    private final List<HexView> hexViews = new ArrayList<>();
    // The honeycomb shows two rows at a time and slides up as focus goes down, so no hex is cut off.
    private static final int HEX_PITCH = 214;
    private int hiveTop;
    private boolean hiveMoved;      // the last focus change slid the honeycomb, so the glow travels with it
    private TextView hiveMore;
    private final List<View> sysViews = new ArrayList<>();
    private final List<App> netApps = new ArrayList<>();

    private FrameLayout alert, boot;
    private View scan;
    private TextView alertDetail, ticker, dataValues;
    private final Map<String, TextView> inputMeta = new HashMap<>();
    private final Map<String, String> inputLabels = new HashMap<>();
    // HDMI ports by input id: the port's own label ("HDMI 1"), its number, and the views that show the device on it.
    private final Map<String, String> inputPortLabel = new HashMap<>();
    private final Map<String, Integer> inputPortNo = new HashMap<>();
    private final Map<String, TextView> inputNameViews = new HashMap<>();
    private final Map<String, TextView> inputPortViews = new HashMap<>();
    private final Map<String, String[]> inputStatus = new HashMap<>();
    private int inputNameWidth;     // room for a device name left of the big port number
    private final Map<String, String> cecSeen = new HashMap<>();   // port id -> CEC device last seen on it
    private TvInputManager tvInputs;
    private String alertInput;
    private Beeps beeps;
    private SharedPreferences prefs;
    private long lastKeyAt, lastLogAt;
    private int inputCount, channelCount, sysCheck = 41;
    private boolean resumed;
    private ValueAnimator alertPulse;
    private final List<String> logLines = new ArrayList<>();

    private int zone = 0;
    private int plateColor = Kido.RED;
    private View lastFocused;
    private boolean idle;

    static final class App {
        final String pkg;
        final Kido.Look look;
        final Intent launch;
        String ch;

        App(String pkg, Kido.Look look, Intent launch) {
            this.pkg = pkg;
            this.look = look;
            this.launch = launch;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Kido.scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        Kido.loadFonts(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Kido.VOID);
        setContentView(root);
        prefs = getSharedPreferences("kido", MODE_PRIVATE);
        Kido.applyTheme(prefs.getInt("theme", 0));
        beeps = new Beeps(prefs.getBoolean("sound", true));
        build();
        tvInputs = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tvInputs != null) tvInputs.registerCallback(inputWatch, handler);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageWatch, pf);
        maybeBoot();
    }

    @Override
    protected void onDestroy() {
        if (tvInputs != null) tvInputs.unregisterCallback(inputWatch);
        unregisterReceiver(packageWatch);
        beeps.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- building

    private void build() {
        root.removeAllViews();
        favViews.clear();
        hexViews.clear();
        hints.clear();
        sysViews.clear();
        netApps.clear();
        linkRows.clear();
        inputMeta.clear();
        inputLabels.clear();
        inputPortLabel.clear();
        inputPortNo.clear();
        inputNameViews.clear();
        inputPortViews.clear();
        inputStatus.clear();
        zone = 0;
        menu = null;

        board = new FrameLayout(this);
        board.setBackground(Backdrop.hexGrid(getResources(), Kido.withAlpha(Kido.AMBER, 0.07f)));
        root.addView(board, new FrameLayout.LayoutParams(Kido.px(3900), Kido.px(2300)));
        List<App> apps = loadApps();
        channelCount = apps.size();
        List<App> favs = new ArrayList<>(), rest = new ArrayList<>();
        for (String pkg : pins(apps)) {
            for (App a : apps) if (a.pkg.equals(pkg)) favs.add(a);
        }
        for (App a : apps) if (!favs.contains(a)) rest.add(a);
        // The honeycomb keeps the order set with MOVER; apps it has never seen go last, alphabetically.
        List<String> order = savedOrder();
        Collections.sort(rest, (a, b) -> {
            int ia = order.indexOf(a.pkg), ib = order.indexOf(b.pkg);
            if (ia < 0 && ib < 0) return 0;
            if (ia < 0) return 1;
            if (ib < 0) return -1;
            return ia - ib;
        });
        int n = 1;
        for (App a : favs) a.ch = String.format(Locale.ROOT, "%02d", n++);
        for (App a : rest) a.ch = String.format(Locale.ROOT, "%02d", n++);

        buildMain(zone(0), favs);
        buildNetwork(zone(1), rest);
        buildSystem(zone(2));
        linkEdges();

        buildHud();
        launch = new FrameLayout(this);
        launch.setBackgroundColor(0xFF050403);
        launch.setVisibility(View.GONE);
        root.addView(launch, match());
        buildAlert();
        scan = new View(this);
        scan.setBackground(Backdrop.scanlines(getResources(), 0x30000000));
        root.addView(scan, match());

        if (!favViews.isEmpty()) favViews.get(0).requestFocus();
        root.post(() -> intro(zone));
    }

    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> out = new ArrayList<>();
        List<String> hidden = Arrays.asList(Kido.HIDDEN);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            out.add(new App(pkg, Kido.lookFor(pm, ri, pkg, label), i));
        }
        Collections.sort(out, (a, b) -> a.look.name.compareToIgnoreCase(b.look.name));
        return out;
    }

    private FrameLayout zone(int index) {
        FrameLayout z = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Kido.px(1920), Kido.px(1080));
        lp.leftMargin = Kido.px(ZONES[index][0]);
        lp.topMargin = Kido.px(ZONES[index][1]);
        board.addView(z, lp);

        // Console bezel: a thin frame with a green-to-orange accent along the top.
        TraceView frame = new TraceView(this);
        // The frame starts below the section title (kanji and name), so the title never sits on its top line.
        place(z, frame, 30, 432, 1840, 608);
        zoneFrames[index] = frame;
        View accent = new View(this);
        accent.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Kido.withAlpha(Kido.LIME, 0.55f), Kido.withAlpha(Kido.AMBER, 0.5f), Kido.withAlpha(Kido.RED, 0.55f)}));
        accent.setPivotX(0);
        place(z, accent, 30, 344, 1500, 8);
        zoneAccents[index] = accent;
        GlowView g = new GlowView(this, Kido.px(44), index == 1);
        g.setAlpha(0f);
        place(z, g, 0, 0, 10, 10);
        zoneGlow[index] = g;
        place(z, new RulerView(this, ZONES[index][1] + 440), 36, 446, 64, 580);

        String[] names = ZONE_NAMES[index];
        LinearLayout title = row();
        title.addView(text(names[0], Kido.mincho, 46, Kido.AMBER, 0));
        title.addView(gap(18));
        title.addView(text(names[1], Kido.condBold, 30, Kido.INK, 0.14f));
        title.addView(gap(14));
        title.addView(text("SEC-0" + (index + 1), Kido.mono, 22, Kido.DIM, 0));
        place(z, title, 120, 360, -1, -1);
        return z;
    }

    private void buildMain(FrameLayout z, List<App> favs) {
        TextView tag = counter(0, "FAVORITOS // %02d CARGADOS", favs.size());
        tag.setGravity(Gravity.END);
        place(z, tag, 1100, 376, 700, -1);

        for (int i = 0; i < favs.size(); i++) {
            View card = card(favs.get(i));
            place(z, card, 122 + i * 340, 452, 316, 372);
            favViews.add(card);
        }

        LinearLayout read = row();
        read.setBackground(box(Kido.PANEL, Kido.LINE, 2));
        read.setPadding(Kido.px(28), 0, Kido.px(28), 0);
        read.setGravity(Gravity.CENTER_VERTICAL);
        read.addView(text("CH ", Kido.mono, 25, Kido.DIM, 0));
        readCh = text("01", Kido.mono, 25, Kido.INK, 0);
        read.addView(readCh);
        read.addView(gap(46));
        read.addView(text("WI-FI ", Kido.mono, 25, Kido.DIM, 0));
        readPills = new PillsView(this, 20);
        read.addView(readPills, new LinearLayout.LayoutParams(Kido.px(300), Kido.px(26)));
        read.addView(gap(46));
        read.addView(text("ACTUALIZ. ", Kido.mono, 25, Kido.DIM, 0));
        readUpdated = text("", Kido.mono, 25, Kido.INK, 0);
        read.addView(readUpdated);
        read.addView(gap(46));
        read.addView(text("SEÑAL ", Kido.mono, 25, Kido.DIM, 0));
        readLink = text("", Kido.mono, 25, Kido.GREEN, 0);
        read.addView(readLink);
        read.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        read.addView(text("OK ", Kido.mono, 25, Kido.DIM, 0));
        readName = text("", Kido.mono, 25, Kido.INK, 0);
        read.addView(readName);
        place(z, read, 122, 880, 1676, 74);

        place(z, text("▼ 制御 SISTEMA", Kido.condBold, 26, Kido.DIM, 0.14f), 122, 966, -1, -1);
        place(z, hint(), 760, 970, 400, -1);
        TextView right = text("アプリ APPS ▶", Kido.condBold, 26, Kido.DIM, 0.14f);
        right.setGravity(Gravity.END);
        place(z, right, 1300, 966, 500, -1);
    }

    private View card(App app) {
        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setClickable(true);
        GradientDrawable bg = box(Kido.PANEL, Kido.LINE, 2);
        c.setBackground(bg);
        TextView code = text("CH." + app.ch, Kido.mono, 26, Kido.DIM, 0);
        place(c, code, 24, 20, -1, -1);
        LinearLayout cat = row();
        cat.setGravity(Gravity.BOTTOM);
        TextView kanji = text(app.look.kanji, Kido.mincho, 30, Kido.INK, 0);
        TextView en = text(app.look.category, Kido.condBold, 20, Kido.INK, 0.14f);
        cat.addView(kanji);
        cat.addView(gap(8));
        cat.addView(en);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        lp.topMargin = Kido.px(18);
        lp.rightMargin = Kido.px(24);
        c.addView(cat, lp);
        TextView name = text(app.look.name.toUpperCase(Locale.ROOT), Kido.mincho, 50, Kido.CREAM, 0);
        fitWords(name, Kido.px(268));
        name.setGravity(Gravity.BOTTOM);
        name.setMaxLines(2);
        name.setLineSpacing(0, 0.92f);
        place(c, name, 24, 110, 268, 180);
        GlowView stripe = new GlowView(this, Kido.px(14), false);
        stripe.setColor(app.look.glow);
        place(c, stripe, 10, 286, 296, 38);
        TextView ready = text("LISTO", Kido.mono, 22, Kido.DIM, 0);
        place(c, ready, 24, 326, -1, -1);
        TextView ads = text("ANUNC. 0", Kido.mono, 22, Kido.DIM, 0);
        ads.setGravity(Gravity.END);
        place(c, ads, 160, 326, 132, -1);

        c.setTag(new Item(0, app, -1));
        c.setOnClickListener(v -> launch(app));
        c.setOnLongClickListener(v -> {
            openMenu(v, app, true);
            return true;
        });
        c.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Kido.ORANGE : Kido.PANEL);
            bg.setStroke(Kido.px(has ? 3 : 2), has ? Kido.FOCUS_RIM : Kido.LINE);
            int t = has ? Kido.DARK_TEXT : 0;
            code.setTextColor(has ? 0xFF1A0D00 : Kido.DIM);
            kanji.setTextColor(has ? t : Kido.INK);
            en.setTextColor(has ? t : Kido.INK);
            name.setTextColor(has ? t : Kido.CREAM);
            stripe.setColor(has ? 0xFF120800 : app.look.glow);
            stripe.setGlowing(!has);
            ready.setTextColor(has ? 0xFF1A0D00 : Kido.DIM);
            ads.setTextColor(has ? 0xFF1A0D00 : Kido.DIM);
            v.animate().translationY(has ? -Kido.px(12) : 0).setDuration(300).setInterpolator(snap).start();
            if (has) focused(v);
        });
        return c;
    }

    private void buildNetwork(FrameLayout z, List<App> rest) {
        TextView tag = counter(1, "CANALES // %02d EN LÍNEA", rest.size());
        tag.setGravity(Gravity.END);
        place(z, tag, 1100, 376, 700, -1);

        int i = 0, rowIndex = 0;
        while (i < rest.size()) {
            int perRow = rowIndex % 2 == 0 ? 5 : 4;
            for (int c = 0; c < perRow && i < rest.size(); c++, i++) {
                App app = rest.get(i);
                HexView hex = new HexView(this, app.look, "CH." + app.ch);
                hex.setTag(new Item(1, app, rowIndex));
                hex.setOnClickListener(v -> launch(app));
                hex.setOnLongClickListener(v -> {
                    openMenu(v, app, false);
                    return true;
                });
                hex.setOnFocusChangeListener((v, has) -> { if (has) focused(v); });
                float x = 150 + c * 246 + (rowIndex % 2 == 1 ? 123 : 0);
                float y = 452 + rowIndex * 214;
                place(z, hex, x, y, 232, 266);
                hexViews.add(hex);
                netApps.add(app);
            }
            rowIndex++;
        }

        hiveMore = text("", Kido.condBold, 26, Kido.DIM, 0.14f);
        hiveMore.setGravity(Gravity.END);
        place(z, hiveMore, 900, 966, 500, -1);
        scrollHive(0, false);

        // Link status panel: every channel in the honeycomb, plus a detail block for the focused one.
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(box(Kido.PANEL, Kido.LINE, 2));
        LinearLayout head = row();
        head.setGravity(Gravity.BOTTOM);
        head.addView(text("回線", Kido.mincho, 28, Kido.AMBER, 0));
        head.addView(gap(10));
        head.addView(text("ESTADO DE ENLACE", Kido.condBold, 26, Kido.AMBER, 0.14f));
        place(panel, head, 26, 22, -1, -1);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (App app : netApps) {
            TextView r = text("  CH." + app.ch + " " + app.look.name.toUpperCase(Locale.ROOT), Kido.mono, 22, Kido.DIM, 0);
            r.setSingleLine(true);
            r.setEllipsize(TextUtils.TruncateAt.END);
            r.setTag(app);
            list.addView(r);
            linkRows.add(r);
        }
        // The list scrolls inside a fixed window so the focused channel is never the clipped last line.
        // A ScrollView only to measure the list at full height and clip it; it never takes focus or scrolls itself.
        linkBox = new android.widget.ScrollView(this);
        linkBox.setFocusable(false);
        linkBox.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        linkBox.setVerticalScrollBarEnabled(false);
        linkBox.setOverScrollMode(View.OVER_SCROLL_NEVER);
        linkBox.addView(list, new FrameLayout.LayoutParams(-1, -2));
        linkList = list;
        linkTop = 0;
        place(panel, linkBox, 26, 70, 318, 290);
        View sep = new View(this);
        sep.setBackgroundColor(Kido.LINE);
        place(panel, sep, 0, 370, 370, 2);
        TextView rx = text("受信", Kido.mincho, 44, Kido.AMBER, 0);
        place(panel, rx, 22, 398, -1, -1);
        detailHead = text("", Kido.mono, 19, Kido.DIM, 0);
        place(panel, detailHead, 130, 392, 220, -1);
        detailPills = new PillsView(this, 20);
        place(panel, detailPills, 130, 424, 210, 18);
        detailRate = text("", Kido.mono, 19, Kido.DIM, 0);
        place(panel, detailRate, 130, 452, 220, -1);
        place(z, panel, 1430, 452, 370, 500);

        place(z, text("◀ 主要 INICIO", Kido.condBold, 26, Kido.DIM, 0.14f), 122, 966, -1, -1);
        place(z, hint(), 760, 970, 400, -1);
    }

    private void buildSystem(FrameLayout z) {
        List<String[]> entries = new ArrayList<>();      // {label, number, meta-left, meta-right, inputId}
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>();
            List<String> hdmiIds = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmiIds.add(info.getId());
            }
            // An HDMI-CEC device (a console, a player) is its own input whose parent is the HDMI port it is
            // plugged into. Only the ports become boxes; the devices name them.
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                String parent = info.getParentId();
                if (parent == null || !hdmiIds.contains(parent)) inputs.add(info);
            }
            Collections.sort(inputs, (a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
            int port = 1;
            for (TvInputInfo info : inputs) {
                String label = String.valueOf(info.loadLabel(this)).toUpperCase(Locale.ROOT);
                if (!label.contains("HDMI")) label = "HDMI " + label;
                boolean linked = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED
                        || cecDeviceOn(tim, info.getId()) != null;
                String num = label.replaceAll("[^0-9]", "");
                inputPortLabel.put(info.getId(), label);
                inputPortNo.put(info.getId(), port);
                cecSeen.put(info.getId(), cecDeviceOn(tim, info.getId()));
                String dev = deviceOn(tim, info.getId());
                entries.add(new String[]{dev != null ? dev : label, num.isEmpty() ? String.valueOf(port) : num,
                        dev != null ? label : "PUERTO " + port, linked ? "CONECTADO" : "LIBRE", info.getId()});
                port++;
            }
        }
        inputCount = entries.size();
        entries.add(new String[]{"WI-FI", "", "HOGAR", "RED", null});
        entries.add(new String[]{"AJUSTES", "", "SISTEMA", "CONFIG.", null});

        TextView tag = counter(2, "ENTRADAS EXTERNAS // %d PUERTOS", inputCount);
        tag.setGravity(Gravity.END);
        place(z, tag, 1100, 376, 700, -1);

        int n = entries.size();
        float w = Math.min(316, (1676 - 24f * (n - 1)) / n);
        for (int i = 0; i < n; i++) {
            String[] e = entries.get(i);
            boolean isInput = i < inputCount;
            String jp = isInput ? "入力" : i == inputCount ? "無線" : "設定";
            String kanji = isInput ? "入力" : i == inputCount ? "通信" : "設定";
            String en = isInput ? "ENTRADA" : i == inputCount ? "INALÁMBRICO" : "AJUSTES";
            FrameLayout box = new FrameLayout(this);
            box.setFocusable(true);
            box.setClickable(true);
            GradientDrawable bg = box(Kido.PANEL, Kido.LINE, 2);
            box.setBackground(bg);
            TextView k = text(jp, Kido.mincho, 54, Kido.AMBER, 0);
            place(box, k, 24, 18, -1, -1);
            TextView label = text(e[0], Kido.condBold, 30, Kido.CREAM, 0.12f);
            label.setSingleLine(true);
            inputNameWidth = Kido.px(w - 170);
            fitLine(label, isInput ? inputNameWidth : Kido.px(w - 40));
            place(box, label, 24, 90, (int) w - 40, -1);
            TextView num = text(e[1], Kido.condBold, 150, Kido.GREEN, 0);
            num.setShadowLayer(Kido.px(18), 0, 0, Kido.withAlpha(Kido.GREEN, 0.55f));
            num.setGravity(Gravity.END);
            place(box, num, (int) w - 140, 0, 116, -1);
            SysIconView icon = new SysIconView(this,
                    isInput ? SysIconView.PORT : i == inputCount ? SysIconView.WIFI : SysIconView.GEAR);
            place(box, icon, 24, 158, (int) w - 48, 114);
            TextView ml = text(e[2], Kido.mono, 21, Kido.DIM, 0);
            place(box, ml, 24, 288, -1, -1);
            TextView mr = text(e[3], Kido.mono, 21, Kido.DIM, 0);
            mr.setGravity(Gravity.END);
            place(box, mr, (int) w / 2, 288, (int) w / 2 - 24, -1);

            final String inputId = e[4];
            final int idx = i;
            String[] status = {kanji, en, e[0] + " // " + e[2], e[3]};
            if (inputId != null) {
                inputMeta.put(inputId, mr);
                inputLabels.put(inputId, e[0]);
                inputNameViews.put(inputId, label);
                inputPortViews.put(inputId, ml);
                inputStatus.put(inputId, status);
            }
            if (i == n - 1) box.setOnLongClickListener(v -> {
                settingsMenu(v);
                return true;
            });
            box.setTag(new Item(2, null, -1, status));
            box.setOnClickListener(v -> {
                if (inputId != null) openInput(inputId, e[0]);
                else if (idx == inputCount) openSystem(Settings.ACTION_WIFI_SETTINGS, "無線", "ABRIENDO WI-FI");
                else openSystem(Settings.ACTION_SETTINGS, "設定", "ABRIENDO AJUSTES");
            });
            box.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? Kido.GREEN : Kido.PANEL);
                bg.setStroke(Kido.px(has ? 3 : 2), has ? Kido.FOCUS_RIM : Kido.LINE);
                int t = 0xFF021A0A;
                k.setTextColor(has ? t : Kido.AMBER);
                label.setTextColor(has ? t : Kido.CREAM);
                num.setTextColor(has ? t : Kido.GREEN);
                num.setShadowLayer(has ? 0 : Kido.px(18), 0, 0, Kido.withAlpha(Kido.GREEN, 0.55f));
                icon.setColor(has ? t : Kido.AMBER);
                ml.setTextColor(has ? t : Kido.DIM);
                mr.setTextColor(has ? t : Kido.DIM);
                v.animate().translationY(has ? -Kido.px(12) : 0).setDuration(300).setInterpolator(snap).start();
                if (has) focused(v);
            });
            place(z, box, 122 + i * (w + 24), 452, w, 330);
            sysViews.add(box);
        }

        LinearLayout strip = row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackground(box(Kido.PANEL, Kido.LINE, 2));
        strip.setPadding(Kido.px(28), 0, Kido.px(28), 0);
        toast = text("待機中   EN ESPERA. ELIGE UNA FUENTE O UN AJUSTE.   MANTÉN OK EN AJUSTES PARA SONIDO Y TEMA.", Kido.mono, 25, Kido.INK, 0);
        strip.addView(toast);
        place(z, strip, 122, 830, 1676, 74);
        place(z, text("▲ 主要 INICIO", Kido.condBold, 26, Kido.DIM, 0.14f), 122, 940, -1, -1);
    }

    /** Explicit moves at section edges, so the remote always lands where the arrows on screen point. */
    private void linkEdges() {
        for (View v : favViews) v.setId(View.generateViewId());
        for (View v : hexViews) v.setId(View.generateViewId());
        for (View v : sysViews) v.setId(View.generateViewId());
        for (int i = 0; i < favViews.size(); i++) {
            View f = favViews.get(i);
            f.setNextFocusUpId(f.getId());
            if (!sysViews.isEmpty()) f.setNextFocusDownId(sysViews.get(Math.min(i, sysViews.size() - 1)).getId());
        }
        if (!favViews.isEmpty()) {
            View first = favViews.get(0), last = favViews.get(favViews.size() - 1);
            first.setNextFocusLeftId(first.getId());
            last.setNextFocusRightId(hexViews.isEmpty() ? last.getId() : hexViews.get(0).getId());
        }
        for (HexView h : hexViews) {
            int row = ((Item) h.getTag()).row;
            // Up from the first row goes back to the main panels.
            if (row == 0) h.setNextFocusUpId(favViews.isEmpty() ? h.getId() : favViews.get(favViews.size() - 1).getId());
            boolean lastRow = hexViews.get(hexViews.size() - 1).getTag() == h.getTag()
                    || ((Item) hexViews.get(hexViews.size() - 1).getTag()).row == row;
            if (lastRow && !sysViews.isEmpty()) h.setNextFocusDownId(sysViews.get(sysViews.size() - 1).getId());
        }
        // Left and right follow reading order through the honeycomb: off the end of a row onto the start of
        // the next, and back. Left from the very first hex returns to the main panels; right from the last
        // one carries on into the inputs and settings.
        for (int i = 0; i < hexViews.size(); i++) {
            HexView h = hexViews.get(i);
            int row = ((Item) h.getTag()).row;
            boolean rowStart = i == 0 || ((Item) hexViews.get(i - 1).getTag()).row != row;
            boolean rowEnd = i == hexViews.size() - 1 || ((Item) hexViews.get(i + 1).getTag()).row != row;
            if (rowStart) {
                if (i > 0) h.setNextFocusLeftId(hexViews.get(i - 1).getId());
                else if (!favViews.isEmpty()) h.setNextFocusLeftId(favViews.get(favViews.size() - 1).getId());
            }
            if (rowEnd) {
                View next = i < hexViews.size() - 1 ? hexViews.get(i + 1) : sysViews.isEmpty() ? h : sysViews.get(0);
                h.setNextFocusRightId(next.getId());
            }
        }
        for (int i = 0; i < sysViews.size(); i++) {
            View s = sysViews.get(i);
            if (!favViews.isEmpty()) s.setNextFocusUpId(favViews.get(Math.min(i, favViews.size() - 1)).getId());
            s.setNextFocusDownId(s.getId());
        }
        if (!sysViews.isEmpty()) {
            View first = sysViews.get(0);
            first.setNextFocusLeftId(hexViews.isEmpty() ? first.getId() : hexViews.get(hexViews.size() - 1).getId());
            View last = sysViews.get(sysViews.size() - 1);
            last.setNextFocusRightId(last.getId());
        }
    }

    private void buildHud() {
        FrameLayout hud = new FrameLayout(this);
        root.addView(hud, match());
        View mask = new View(this);
        mask.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Kido.VOID, Kido.VOID, Kido.VOID, 0x00040404}));
        place(hud, mask, 0, 0, 1920, 340);

        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(Kido.px(560));
        glowBg.setColors(new int[]{Kido.withAlpha(plateColor, 0.34f), 0x00000000});
        glow.setBackground(glowBg);
        place(hud, glow, 880, -320, 1300, 1000);

        LinearLayout lab = row();
        lab.setGravity(Gravity.BOTTOM);
        TextView jp = text("現在時刻", Kido.mincho, 30, 0xFF120800, 0);
        jp.setBackgroundColor(Kido.INK);
        jp.setPadding(Kido.px(10), Kido.px(2), Kido.px(10), Kido.px(4));
        lab.addView(jp);
        lab.addView(gap(16));
        lab.addView(text("HORA LOCAL:", Kido.condBold, 26, Kido.INK, 0.12f));
        place(hud, lab, 100, 54, -1, -1);
        place(hud, new SegClockView(this), 86, 98, 680, 164);
        dateText = text(dateLine(), Kido.mono, 26, Kido.DIM, 0.06f);
        place(hud, dateText, 100, 268, -1, -1);

        TextView dataLabels = text("VERIF.\nMEM LIBRE\nACTIVO\nENTRADAS", Kido.mono, 17, Kido.DIM, 0.06f);
        dataLabels.setLineSpacing(Kido.px(6), 1f);
        place(hud, dataLabels, 800, 76, -1, -1);
        dataValues = text("", Kido.mono, 17, Kido.INK, 0.06f);
        dataValues.setLineSpacing(Kido.px(6), 1f);
        place(hud, dataValues, 930, 76, 220, -1);

        for (int i = 0; i < 3; i++) {
            FrameLayout tab = new FrameLayout(this);
            tabBg[i] = box(Kido.withAlpha(Kido.GREEN, 0.1f), Kido.withAlpha(Kido.GREEN, 0.45f), 2);
            tab.setBackground(tabBg[i]);
            tabText[i * 2] = text(TAB_KANJI[i], Kido.mincho, 22, Kido.withAlpha(Kido.LIME, 0.75f), 0);
            tabText[i * 2 + 1] = text(ZONE_NAMES[i][2], Kido.condBold, 22, Kido.withAlpha(Kido.LIME, 0.85f), 0.12f);
            place(tab, tabText[i * 2], 12, 6, -1, -1);
            place(tab, tabText[i * 2 + 1], 12, 38, -1, -1);
            place(hud, tab, 730 + i * 146, 214, 136, 74);
            tabBar[i] = new GlowView(this, Kido.px(12), false);
            tabBar[i].setColor(Kido.RED);
            place(hud, tabBar[i], 724 + i * 146, 284, 148, 32);
        }

        plate = new FrameLayout(this);
        plateBg = new GradientDrawable();
        plateBg.setColor(plateColor);
        plate.setBackground(plateBg);
        plateKanji = text("映画", Kido.mincho, 104, 0xFF0A0505, 0);
        place(plate, plateKanji, 26, 6, 470, -1);
        plateEn = text("CINE", Kido.condBold, 38, 0xFF0A0505, 0.1f);
        place(plate, plateEn, 30, 136, 470, -1);
        place(plate, new Stripes(this, 0x8C000000), 490, 0, 150, 196);
        plateHalo = new GlowView(this, Kido.px(60), false);
        plateHalo.setColor(Kido.withAlpha(plateColor, 0.85f));
        place(hud, plateHalo, 1120, -10, 760, 316);
        place(hud, plate, 1180, 50, 640, 196);

        FrameLayout sub = new FrameLayout(this);
        subBg = box(0xE6040404, plateColor, 2);
        sub.setBackground(subBg);
        subLeft = text("", Kido.condBold, 30, Kido.CREAM, 0.1f);
        subLeft.setSingleLine(true);
        place(sub, subLeft, 22, 12, 470, -1);
        subRight = text("LISTO", Kido.mono, 22, Kido.DIM, 0);
        subRight.setGravity(Gravity.END);
        place(sub, subRight, 460, 18, 158, -1);
        place(hud, sub, 1180, 262, 640, 64);

        LinearLayout mark = row();
        mark.setGravity(Gravity.BOTTOM);
        mark.addView(text("起動", Kido.mincho, 24, Kido.DIM, 0));
        mark.addView(gap(10));
        mark.addView(text("KIDŌ", Kido.condBold, 22, Kido.DIM, 0.16f));
        mark.addView(gap(10));
        mark.addView(text("v" + versionName(), Kido.mono, 20, Kido.DIM, 0.08f));
        place(hud, mark, 100, 1046, -1, -1);
        ticker = text("", Kido.mono, 18, Kido.DIM, 0.06f);
        ticker.setSingleLine(true);
        place(hud, ticker, 430, 1050, 860, -1);
        footerZone = text("SEC-01 // INICIO", Kido.mono, 20, Kido.DIM, 0.08f);
        footerZone.setGravity(Gravity.END);
        place(hud, footerZone, 1300, 1050, 520, -1);
        showTabs(0);
    }

    // ---------------------------------------------------------------- focus

    static final class Item {
        final int zone, row;
        final App app;
        final String[] status;

        Item(int zone, App app, int row) {
            this(zone, app, row, null);
        }

        Item(int zone, App app, int row, String[] status) {
            this.zone = zone;
            this.app = app;
            this.row = row;
            this.status = status;
        }
    }

    private void focused(View v) {
        lastFocused = v;
        Item item = (Item) v.getTag();
        hiveMoved = item.zone == 1 && item.app != null && scrollHive(item.row, true);
        glowBehind(v);
        boolean byKey = SystemClock.uptimeMillis() - lastKeyAt < 400;
        boolean newZone = item.zone != zone;
        if (newZone) {
            if (byKey) beeps.section();
            moveTo(item.zone);
        } else if (byKey) {
            beeps.tick();
        }
        if (newZone || !holding) landedAt = SystemClock.uptimeMillis();
        // While an arrow is held, the status block waits until focus comes to rest, so it doesn't
        // flash through every app's color on the way past.
        handler.removeCallbacks(settle);
        if (holding) {
            pendingStatus = v;
            handler.postDelayed(settle, SETTLE_MS);
        } else {
            showStatus(v);
        }
    }

    private final Runnable settle = () -> {
        if (pendingStatus != null) showStatus(pendingStatus);
        pendingStatus = null;
    };

    private void showStatus(View v) {
        Item item = (Item) v.getTag();
        if (item.app != null) log("FOCO CH." + item.app.ch + " " + item.app.look.name.toUpperCase(Locale.ROOT));
        else if (item.status != null) log("FOCO " + item.status[2]);
        if (item.app != null) {
            App a = item.app;
            setStatus(a.look.kanji, a.look.category, a.look.name.toUpperCase(Locale.ROOT) + " // CH." + a.ch, "LISTO", a.look.glow);
            if (item.zone == 0) {
                readCh.setText(a.ch);
                readUpdated.setText(updatedOn(a.pkg));
                readName.setText("ABRIR " + a.look.name.toUpperCase(Locale.ROOT));
            } else {
                scrollLinkList(a);
                for (TextView r : linkRows) {
                    boolean on = r.getTag() == a;
                    r.setTextColor(on ? Kido.INK : Kido.DIM);
                    r.setText((on ? "▶ " : "  ") + "CH." + ((App) r.getTag()).ch + " " + ((App) r.getTag()).look.name.toUpperCase(Locale.ROOT));
                }
                detailHead.setText("CH." + a.ch + " // ENLACE OK");
            }
        } else if (item.status != null) {
            int color = item.status[0].equals("入力") ? 0xFFB46CFF : item.status[0].equals("通信") ? Kido.GREEN : Kido.AMBER;
            setStatus(item.status[0], item.status[1], item.status[2], item.status[3], color);
        }
    }

    /** Moves the zone's glow behind the focused tile: orange for apps, green for inputs and settings. */
    private void glowBehind(View v) {
        Item item = (Item) v.getTag();
        for (int i = 0; i < 3; i++) if (i != item.zone) zoneGlow[i].animate().alpha(0f).setDuration(200).start();
        GlowView g = zoneGlow[item.zone];
        FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) v.getLayoutParams();
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) g.getLayoutParams();
        int pad = Kido.px(item.zone == 1 ? 40 : 34);
        int lift = item.zone == 1 ? 0 : Kido.px(12);
        if (gp.width != vp.width + pad * 2 || gp.height != vp.height + pad * 2) {
            gp.width = vp.width + pad * 2;
            gp.height = vp.height + pad * 2;
            g.setLayoutParams(gp);
        }
        g.setColor(Kido.withAlpha(item.zone == 2 ? Kido.GREEN : Kido.ORANGE, 0.7f));
        float gx = vp.leftMargin - pad;
        float gy = vp.topMargin - pad - lift + (item.zone == 1 ? -Kido.px(hiveTop * HEX_PITCH) : 0);
        if (moving != null) g.setColor(Kido.withAlpha(Kido.WARN, 0.85f));
        if (g.getAlpha() > 0.05f) {
            // The glow slides straight from the last tile to the new one, diagonals included, moving X and Y
            // together (and with the honeycomb when it scrolls). While moving an app its alpha pulses instead.
            android.view.ViewPropertyAnimator a = g.animate().translationX(gx).translationY(gy)
                    .setDuration(hiveMoved ? 340 : 240).setInterpolator(fluid);
            if (moving == null) a.alpha(1f);
            a.start();
            return;
        }
        g.animate().cancel();
        g.setTranslationX(gx);
        g.setTranslationY(gy);
        g.animate().alpha(1f).setDuration(220).start();
    }

    /**
     * Slides the honeycomb so the focused row is fully on screen: two rows show at a time, the rows above
     * fade out under the section title and the ones below wait off screen, with a hint that there are more.
     */
    private boolean scrollHive(int row, boolean animate) {
        if (hexViews.isEmpty()) return false;
        int lastRow = ((Item) hexViews.get(hexViews.size() - 1).getTag()).row;
        int top = Math.max(0, Math.min(row - 1, lastRow - 1));
        boolean moved = top != hiveTop;
        hiveTop = top;
        float shift = -Kido.px(top * HEX_PITCH);
        for (HexView h : hexViews) {
            int r = ((Item) h.getTag()).row;
            float alpha = r >= top && r <= top + 1 ? 1f : 0f;
            if (animate) {
                h.animate().translationX(0).translationY(shift).alpha(alpha).setDuration(340)
                        .setInterpolator(fluid).withLayer().start();
            } else {
                h.setTranslationX(0);
                h.setTranslationY(shift);
                h.setAlpha(alpha);
            }
        }
        if (hiveMore != null) {
            boolean above = top > 0, below = top + 1 < lastRow;
            hiveMore.setText(above || below ? (above ? "▲ " : "") + (below ? "▼ " : "") + "MÁS CANALES" : "");
        }
        return moved;
    }

    /** Keeps the focused channel inside the link status list, with a line of context above and below. */
    private void scrollLinkList(App a) {
        if (linkList == null || linkBox == null || linkRows.isEmpty()) return;
        int rowH = linkRows.get(0).getHeight();
        int boxH = linkBox.getHeight();
        if (rowH <= 0 || boxH <= 0) return;
        int visible = Math.max(1, boxH / rowH);
        int n = linkRows.size();
        int i = 0;
        while (i < n && linkRows.get(i).getTag() != a) i++;
        if (i >= n || n <= visible) {
            linkTop = 0;
        } else {
            if (i < linkTop + 1) linkTop = i - 1;
            if (i > linkTop + visible - 2) linkTop = i - visible + 2;
            linkTop = Math.max(0, Math.min(linkTop, n - visible));
        }
        linkList.animate().translationY(-linkTop * rowH).setDuration(260).setInterpolator(fluid).start();
    }

    private void moveTo(int z) {
        zone = z;
        board.animate().translationX(-Kido.px(ZONES[z][0])).translationY(-Kido.px(ZONES[z][1]))
                .setDuration(620).setInterpolator(fluid).start();
        showTabs(z);
        footerZone.setText("SEC-0" + (z + 1) + " // " + ZONE_NAMES[z][2]);
        log("SECTOR 0" + (z + 1) + " " + ZONE_NAMES[z][1]);
        // Draw the section in as the pan settles; a newer move cancels it.
        handler.removeCallbacks(arrive);
        handler.postDelayed(arrive, 420);
    }

    private final Runnable arrive = () -> intro(zone);

    /** Draws a section in: the frame traces itself, the accent bar extends and the header count runs up. */
    private void intro(int z) {
        if (zoneFrames[z] == null) return;
        zoneFrames[z].trace(620);
        View a = zoneAccents[z];
        a.setScaleX(0f);
        a.animate().scaleX(1f).setDuration(560).setInterpolator(snap).start();
        TextView tag = zoneTags[z];
        if (tag == null) return;
        String format = zoneTagFormat[z];
        ValueAnimator count = ValueAnimator.ofInt(0, zoneTagCount[z]);
        count.setDuration(520);
        count.addUpdateListener(v -> tag.setText(String.format(Locale.ROOT, format, (int) v.getAnimatedValue())));
        count.start();
    }

    /** A section header count, e.g. CHANNELS // 07 ONLINE, which runs up from zero when the section draws in. */
    private TextView counter(int z, String format, int count) {
        TextView t = text(String.format(Locale.ROOT, format, count), Kido.mono, 22, Kido.DIM, 0.08f);
        zoneTags[z] = t;
        zoneTagFormat[z] = format;
        zoneTagCount[z] = count;
        return t;
    }

    private void showTabs(int z) {
        for (int i = 0; i < 3; i++) {
            boolean on = i == z;
            tabBg[i].setColor(on ? Kido.withAlpha(Kido.RED, 0.18f) : Kido.withAlpha(Kido.GREEN, 0.1f));
            tabBg[i].setStroke(Kido.px(2), on ? Kido.RED : Kido.withAlpha(Kido.GREEN, 0.45f));
            tabText[i * 2].setTextColor(on ? Kido.RED : Kido.withAlpha(Kido.LIME, 0.75f));
            tabText[i * 2 + 1].setTextColor(on ? Kido.RED : Kido.withAlpha(Kido.LIME, 0.85f));
            tabBar[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Long names are shortened with "…" before the " // CH.xx" part, so the status line stays on one line. */
    private String fitStatus(String s) {
        int max = Kido.px(428);
        android.text.TextPaint p = subLeft.getPaint();
        if (p.measureText(s) <= max) return s;
        int cut = s.lastIndexOf(" // ");
        String tail = cut > 0 ? s.substring(cut) : "";
        String head = cut > 0 ? s.substring(0, cut) : s;
        while (head.length() > 1 && p.measureText(head.trim() + "…" + tail) > max) head = head.substring(0, head.length() - 1);
        return head.trim() + "…" + tail;
    }

    private void setStatus(String kanji, String en, String left, String right, int color) {
        if (!kanji.equals(plateKanji.getText().toString()) || !en.equals(plateEn.getText().toString())) {
            plateKanji.setText(kanji);
            plateEn.setText(en);
            plateKanji.setAlpha(0f);
            plateKanji.setTranslationX(-Kido.px(14));
            plateKanji.animate().alpha(1f).translationX(0).setDuration(300).start();
        }
        subLeft.setText(fitStatus(left));
        subRight.setText(right);
        if (color == plateColor) return;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), plateColor, color);
        anim.setDuration(450);
        anim.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            plateBg.setColor(c);
            plateHalo.setColor(Kido.withAlpha(c, 0.85f));
            subBg.setStroke(Kido.px(2), c);
            glowBg.setColors(new int[]{Kido.withAlpha(c, 0.34f), 0x00000000});
        });
        anim.start();
        plateColor = color;
    }

    // ---------------------------------------------------------------- actions

    private void launch(App app) {
        launch.removeAllViews();
        int glowColor = app.look.glow;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView bits = text(randomBits(160), Kido.mono, 18, Kido.withAlpha(glowColor, 0.6f), 0);
        bits.setSingleLine(true);
        col.addView(bits);
        col.addView(glowRule(glowColor));

        LinearLayout body = row();
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(0, Kido.px(30), 0, Kido.px(26));
        // Title-card type: heavy white Mincho, squeezed narrow and set big.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = row();
        top.setGravity(Gravity.BOTTOM);
        TextView stamp = text("起動", Kido.mincho, 230, 0xFFFFFFFF, 0);
        stamp.setTextScaleX(0.78f);
        stamp.setShadowLayer(Kido.px(26), 0, 0, Kido.withAlpha(glowColor, 0.7f));
        top.addView(stamp);
        top.addView(gap(30));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, 0, 0, Kido.px(30));
        TextView circuit = text("第" + app.ch + "回線", Kido.mincho, 64, 0xFFFFFFFF, 0);
        circuit.setTextScaleX(0.8f);
        side.addView(circuit);
        side.addView(text("INICIANDO // " + app.look.category, Kido.condBold, 30, glowColor, 0.16f));
        top.addView(side);
        left.addView(top);
        TextView name = text(app.look.name.toUpperCase(Locale.ROOT), Kido.mincho, 130, 0xFFFFFFFF, 0);
        name.setTextScaleX(0.82f);
        name.setSingleLine(true);
        fitWords(name, Kido.px(960));
        left.addView(name);
        body.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout ladder = new LinearLayout(this);
        ladder.setOrientation(LinearLayout.VERTICAL);
        List<View> slots = new ArrayList<>();
        List<TextView> states = new ArrayList<>();
        for (String p : PHASES) {
            LinearLayout r = row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(text(p, Kido.condBold, 22, glowColor, 0.12f), new LinearLayout.LayoutParams(Kido.px(130), -2));
            View slot = new View(this);
            GradientDrawable sd = new GradientDrawable();
            sd.setCornerRadius(Kido.px(11));
            sd.setColor(0xFF3A0006);
            slot.setBackground(sd);
            r.addView(slot, new LinearLayout.LayoutParams(0, Kido.px(22), 1f));
            TextView st = text(" ESPERA", Kido.mono, 20, Kido.withAlpha(glowColor, 0.4f), 0);
            r.addView(st, new LinearLayout.LayoutParams(Kido.px(80), -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Kido.px(9);
            ladder.addView(r, lp);
            slots.add(slot);
            states.add(st);
        }
        body.addView(ladder, new LinearLayout.LayoutParams(Kido.px(520), -2));
        col.addView(body);
        col.addView(glowRule(glowColor));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Kido.px(1520), -2, Gravity.CENTER);
        launch.addView(col, lp);
        unclip(stamp);
        launch.setVisibility(View.VISIBLE);
        launch.setAlpha(0f);
        launch.animate().alpha(1f).setDuration(150).start();
        beeps.launch();
        log("ABRIR CH." + app.ch + " " + app.look.name.toUpperCase(Locale.ROOT));

        for (int i = 0; i < PHASES.length; i++) {
            final int k = i;
            handler.postDelayed(() -> {
                ((GradientDrawable) slots.get(k).getBackground()).setColor(Kido.GREEN);
                states.get(k).setText("  OK");
                states.get(k).setTextColor(Kido.GREEN);
            }, 90L + i * 70L);
        }
        handler.postDelayed(() -> open(app.launch), 90L + PHASES.length * 70L + 120L);
    }

    private void openInput(String inputId, String label) {
        flashToast("切替", "CAMBIANDO A " + label + " ...");
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    private void openSystem(String action, String kanji, String message) {
        flashToast(kanji, message + " ...");
        open(new Intent(action));
    }

    private void flashToast(String kanji, String message) {
        toast.setText(kanji + "   " + message);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            launch.setVisibility(View.GONE);
            Toast.makeText(this, "No se puede abrir aquí", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- lifecycle and keys

    private final Runnable goIdle = () -> {
        idle = true;
        setStatus("待機", "EN ESPERA", "EN ESPERA // SIN ACTIVIDAD", "INACTIVO", Kido.AMBER);
        log("EN ESPERA // SIN ACTIVIDAD");
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        handler.removeCallbacks(goIdle);
        handler.postDelayed(goIdle, IDLE_MS);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN, up = event.getAction() == KeyEvent.ACTION_UP;
        if (down) lastKeyAt = SystemClock.uptimeMillis();
        if (boot != null) {
            if (up) endBoot();
            return true;
        }
        if (menu != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (up) closeMenu();
            return true;
        }
        if (moving != null && menu == null) {
            int k = event.getKeyCode();
            if (isArrow(k)) {
                long now = SystemClock.uptimeMillis();
                if (down && (event.getRepeatCount() == 0 || now - lastMoveAt >= HOLD_STEP_MS * 2)) {
                    lastMoveAt = now;
                    if (swapAt >= 0) swapStep(k);
                    else moveStep(k);
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                if (up) finishMove(k != KeyEvent.KEYCODE_BACK);
                return true;
            }
        }
        if (idle && down) {
            idle = false;
            if (lastFocused != null) focused(lastFocused);
        }
        if (alert.getVisibility() == View.VISIBLE) {
            int k = event.getKeyCode();
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (ok || k == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    String id = alertInput;
                    hideAlert.run();
                    if (ok) openInput(id, inputLabels.get(id));
                }
                return true;
            }
        }
        if (launch.getVisibility() == View.VISIBLE) return true;
        if (isArrow(event.getKeyCode())) {
            if (up) {
                holding = false;
                handler.removeCallbacks(settle);
                settle.run();
            } else if (down && event.getRepeatCount() > 0) {
                holding = true;
                if (!holdStepAllowed(event.getKeyCode())) return true;
                lastMoveAt = SystemClock.uptimeMillis();
            } else if (down) {
                holding = false;
                lastMoveAt = SystemClock.uptimeMillis();
            }
        }    // ignore keys during the launch sequence
        return super.dispatchKeyEvent(event);
    }

    private static boolean isArrow(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /**
     * Held arrows step about eight times a second, and stop briefly at the edge of a section so the pan
     * can catch up before carrying on into the next one. A fresh press is never held back.
     */
    private boolean holdStepAllowed(int key) {
        long now = SystemClock.uptimeMillis();
        if (now - lastMoveAt < HOLD_STEP_MS) return false;
        View cur = getCurrentFocus();
        if (cur == null || !(cur.getTag() instanceof Item)) return true;
        int dir = key == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
        View next = cur.focusSearch(dir);
        boolean crossing = next != null && next.getTag() instanceof Item && ((Item) next.getTag()).zone != ((Item) cur.getTag()).zone;
        if (crossing && now - Math.max(landedAt, lastMoveAt) < EDGE_PAUSE_MS) return false;
        return true;
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to: Back returns to the main panels instead.
        if (zone != 0 && !favViews.isEmpty()) favViews.get(0).requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already home: back to the first panel.
        if (!favViews.isEmpty()) favViews.get(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(goIdle, IDLE_MS);
        handler.post(dataTick);
        launch.setVisibility(View.GONE);
        hideAlert.run();
        if (appsChanged) {
            appsChanged = false;
            Item last = lastFocused != null ? (Item) lastFocused.getTag() : null;
            rebuild(last != null && last.app != null ? last.app.pkg : null);
        } else if (lastFocused != null) {
            lastFocused.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(goIdle);
        handler.removeCallbacks(dataTick);
        super.onPause();
    }

    // ---------------------------------------------------------------- pinned apps and options

    /** The apps pinned to the main panels. First run starts from Kido.FAVORITES, topped up to five. */
    private List<String> pins(List<App> apps) {
        String saved = prefs.getString("pins", null);
        if (saved != null) return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
        List<String> out = new ArrayList<>();
        for (String pkg : Kido.FAVORITES) {
            for (App a : apps) if (a.pkg.equals(pkg)) out.add(pkg);
        }
        for (App a : apps) if (out.size() < 5 && !out.contains(a.pkg)) out.add(a.pkg);
        savePins(out);
        return out;
    }

    private List<String> savedOrder() {
        String saved = prefs.getString("order", "");
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    private void saveOrder() {
        List<String> out = new ArrayList<>();
        for (HexView h : hexViews) out.add(((Item) h.getTag()).app.pkg);
        prefs.edit().putString("order", TextUtils.join(",", out)).apply();
    }

    private void savePins(List<String> pins) {
        prefs.edit().putString("pins", TextUtils.join(",", pins)).apply();
    }

    private List<String> shownPins() {
        List<String> out = new ArrayList<>();
        for (View v : favViews) out.add(((Item) v.getTag()).app.pkg);
        return out;
    }

    private final List<TextView> hints = new ArrayList<>();

    private TextView hint() {
        TextView t = text("MANTÉN OK // OPCIONES", Kido.mono, 20, Kido.DIM, 0.08f);
        hints.add(t);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** Hold OK on an app: move it, pin or unpin it, or open its system info page. Back closes. */
    private void openMenu(View origin, App app, boolean pinned) {
        List<String> pins = shownPins();
        int at = pins.indexOf(app.pkg);
        List<String[]> rows = new ArrayList<>();
        if (pinned ? pins.size() > 1 : hexViews.size() > 1) rows.add(new String[]{"移動", "MOVER", "move"});
        if (pinned) {
            rows.add(new String[]{"解除", "QUITAR DE INICIO", "unpin"});
        } else if (pins.size() < 5) {
            rows.add(new String[]{"固定", "FIJAR EN INICIO", "pin"});
        } else {
            App last = ((Item) favViews.get(favViews.size() - 1).getTag()).app;
            rows.add(new String[]{"固定", "FIJAR // REEMPLAZA A " + last.look.name.toUpperCase(Locale.ROOT), "pin"});
        }
        rows.add(new String[]{"情報", "INFORMACIÓN DE LA APP", "info"});
        showMenu(origin, "CH." + app.ch + " // " + app.look.name.toUpperCase(Locale.ROOT), rows,
                action -> menuAction(action, app, at));
        log("OPCIONES CH." + app.ch + " " + app.look.name.toUpperCase(Locale.ROOT));
    }

    /** Hold OK on Settings: sound on or off, and the color theme. */
    private void settingsMenu(View origin) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"音響", beeps.isOn() ? "SONIDO ACTIVADO // DESACTIVAR" : "SONIDO DESACTIVADO // ACTIVAR", "sound"});
        rows.add(new String[]{"入力", inputKeyOn() ? "BOTÓN INPUT: SÍ // APAGAR" : "BOTÓN INPUT: NO // ENCENDER", "inputkey"});
        for (int i = 0; i < Kido.THEMES.length; i++) {
            rows.add(new String[]{Kido.THEMES[i][0], "TEMA // " + Kido.THEMES[i][1] + (i == Kido.theme ? "  ◀" : ""), "theme" + i});
        }
        showMenu(origin, "KIDŌ v" + versionName() + " // GLIDIAN.DEV", rows, action -> {
            if (action.equals("sound")) {
                closeMenu();
                toggleSound();
                return;
            }
            if (action.equals("inputkey")) {
                closeMenu();
                toggleInputKey();
                return;
            }
            int t = Integer.parseInt(action.substring(5));
            prefs.edit().putInt("theme", t).apply();
            Kido.applyTheme(t);
            log("TEMA " + Kido.THEMES[t][1]);
            build();
            if (!sysViews.isEmpty()) sysViews.get(sysViews.size() - 1).requestFocus();
        });
    }

    /** A small menu over the screen. Each row is {kanji, label, action}; focus stays inside until Back. */
    private void showMenu(View origin, String subtitle, List<String[]> rows, Consumer<String> pick) {
        menuOrigin = origin;
        menu = new FrameLayout(this);
        menu.setBackgroundColor(0xD9000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(0xFF050403, Kido.AMBER, 2));
        panel.setPadding(Kido.px(34), Kido.px(26), Kido.px(34), Kido.px(30));
        LinearLayout head = row();
        head.setGravity(Gravity.BOTTOM);
        head.addView(text("操作", Kido.mincho, 58, Kido.AMBER, 0));
        head.addView(gap(16));
        head.addView(text("OPCIONES", Kido.condBold, 34, Kido.INK, 0.16f));
        panel.addView(head);
        TextView sub = text(subtitle, Kido.mono, 24, Kido.DIM, 0.06f);
        sub.setPadding(0, Kido.px(6), 0, Kido.px(18));
        panel.addView(sub);
        List<View> options = new ArrayList<>();
        for (String[] r : rows) {
            LinearLayout o = row();
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setFocusable(true);
            o.setClickable(true);
            o.setPadding(Kido.px(20), 0, Kido.px(20), 0);
            GradientDrawable bg = box(Kido.PANEL, Kido.LINE, 2);
            o.setBackground(bg);
            TextView k = text(r[0], Kido.mincho, 32, Kido.AMBER, 0);
            TextView en = text(r[1], Kido.condBold, 30, Kido.CREAM, 0.12f);
            o.addView(k);
            o.addView(gap(18));
            o.addView(en);
            o.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? Kido.ORANGE : Kido.PANEL);
                bg.setStroke(Kido.px(2), has ? Kido.FOCUS_RIM : Kido.LINE);
                k.setTextColor(has ? Kido.DARK_TEXT : Kido.AMBER);
                en.setTextColor(has ? Kido.DARK_TEXT : Kido.CREAM);
                if (has && SystemClock.uptimeMillis() - lastKeyAt < 400) beeps.tick();
            });
            o.setOnClickListener(v -> pick.accept(r[2]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Kido.px(70));
            lp.topMargin = Kido.px(12);
            panel.addView(o, lp);
            o.setId(View.generateViewId());
            options.add(o);
        }
        // Keep focus inside the menu.
        for (int i = 0; i < options.size(); i++) {
            View o = options.get(i);
            o.setNextFocusLeftId(o.getId());
            o.setNextFocusRightId(o.getId());
            o.setNextFocusUpId(options.get(Math.max(0, i - 1)).getId());
            o.setNextFocusDownId(options.get(Math.min(options.size() - 1, i + 1)).getId());
        }
        menu.addView(panel, new FrameLayout.LayoutParams(Kido.px(720), -2, Gravity.CENTER));
        root.addView(menu, root.indexOfChild(scan), match());
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setDuration(150).start();
        options.get(0).requestFocus();
    }

    private void closeMenu() {
        if (menu == null) return;
        root.removeView(menu);
        menu = null;
        if (menuOrigin != null) menuOrigin.requestFocus();
    }

    private void menuAction(String action, App app, int at) {
        List<String> pins = shownPins();
        switch (action) {
            case "move":
                closeMenu();
                startMove(app);
                return;
            case "left":
                Collections.swap(pins, at, at - 1);
                break;
            case "right":
                Collections.swap(pins, at, at + 1);
                break;
            case "unpin":
                pins.remove(app.pkg);
                break;
            case "pin":
                if (pins.size() >= 5) pins.remove(pins.size() - 1);
                pins.add(app.pkg);
                break;
            default:
                closeMenu();
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
                return;
        }
        savePins(pins);
        log((action.equals("unpin") ? "QUITADO " : action.equals("pin") ? "FIJADO " : "MOVIDO ")
                + app.look.name.toUpperCase(Locale.ROOT));
        rebuild(app.pkg);
    }

    /** Rebuilds the whole screen (after pins or installed apps change) and puts focus back on an app if it can. */
    private void rebuild(String focusPkg) {
        build();
        if (focusPkg == null) return;
        View target = null;
        for (View v : favViews) if (((Item) v.getTag()).app.pkg.equals(focusPkg)) target = v;
        for (HexView h : hexViews) if (((Item) h.getTag()).app.pkg.equals(focusPkg)) target = h;
        if (target == null) return;
        Item it = (Item) target.getTag();
        if (it.zone != zone) {
            // Stay on the section the app is in, without panning over from the main panels.
            zone = it.zone;
            board.animate().cancel();
            board.setTranslationX(-Kido.px(ZONES[zone][0]));
            board.setTranslationY(-Kido.px(ZONES[zone][1]));
            showTabs(zone);
            footerZone.setText("SEC-0" + (zone + 1) + " // " + ZONE_NAMES[zone][2]);
        }
        if (it.zone == 1) scrollHive(it.row, false);
        target.requestFocus();
    }

    // ---------------------------------------------------------------- moving an app

    private View moving;            // the tile being moved with the arrows, or null
    private ValueAnimator movePulse;

    /** MOVER: the tile lights up red and the arrows carry it to a new place; OK drops it, Back cancels. */
    private void startMove(App app) {
        View v = null;
        for (View f : favViews) if (((Item) f.getTag()).app == app) v = f;
        for (HexView h : hexViews) if (((Item) h.getTag()).app == app) v = h;
        if (v == null) return;
        moving = v;
        v.requestFocus();
        swapAt = -1;
        glowBehind(v);
        pulse(zoneGlow[((Item) v.getTag()).zone]);
        for (TextView h : hints) h.setText("◀▶▲▼ UBICAR // OK SOLTAR // ATRÁS CANCELAR");
        setStatus("移動", "MOVIENDO", app.look.name.toUpperCase(Locale.ROOT) + " // ELIGE EL LUGAR", "OK SOLTAR", Kido.WARN);
        beeps.section();
        log("MOVER " + app.look.name.toUpperCase(Locale.ROOT));
    }

    /** One step of a move: the tile takes the next place and the others slide over to make room. */
    private void moveStep(int key) {
        Item it = (Item) moving.getTag();
        boolean hive = it.zone == 1;
        List<View> seq = new ArrayList<>(hive ? hexViews : favViews);
        int i = seq.indexOf(moving), n = seq.size(), j = i;
        if (hive && key == KeyEvent.KEYCODE_DPAD_UP && it.row == 0 && !favViews.isEmpty()) {
            startSwap();
            return;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) j = i - 1;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) j = i + 1;
        else if (hive) {
            float[] here = hexSlot(i);
            int row = (int) here[2] + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            float best = Float.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                float[] s = hexSlot(k);
                if ((int) s[2] != row) continue;
                float d = Math.abs(s[0] - here[0]);
                if (d < best) {
                    best = d;
                    j = k;
                }
            }
        }
        if (j < 0 || j >= n || j == i) return;
        seq.remove(i);
        seq.add(j, moving);
        int movedRow = 0;
        for (int k = 0; k < n; k++) {
            View v = seq.get(k);
            float x = hive ? hexSlot(k)[0] : 122 + k * 340, y = hive ? hexSlot(k)[1] : 452;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int nx = Kido.px(x), ny = Kido.px(y);
            if (lp.leftMargin != nx || lp.topMargin != ny) {
                // Jump to the new place in the layout, then slide there from where it was on screen.
                float ox = v.getX(), oy = v.getY();
                lp.leftMargin = nx;
                lp.topMargin = ny;
                v.setLayoutParams(lp);
                v.setTranslationX(ox - nx);
                v.setTranslationY(oy - ny);
            }
            if (hive) {
                int row = (int) hexSlot(k)[2];
                v.setTag(new Item(1, ((Item) v.getTag()).app, row));
                if (v == moving) movedRow = row;
            }
        }
        if (hive) {
            hexViews.clear();
            for (View v : seq) hexViews.add((HexView) v);
            scrollHive(movedRow, true);
            hiveMoved = true;
        } else {
            favViews.clear();
            favViews.addAll(seq);
            for (View v : favViews) {
                v.animate().translationX(0).translationY(v == moving ? -Kido.px(12) : 0)
                        .setDuration(240).setInterpolator(fluid).start();
            }
        }
        glowBehind(moving);
        beeps.tick();
    }

    private int swapAt = -1;        // while moving an app up into the main panels: the panel it would replace

    /** Up from the first row while moving: KIDŌ shows the main panels and lets you pick which one to swap. */
    private void startSwap() {
        App app = ((Item) moving.getTag()).app;
        int x = Math.round(moving.getX() / Math.max(1f, Kido.scale));
        swapAt = Math.max(0, Math.min(favViews.size() - 1, Math.round((x - 122) / 340f)));
        moveTo(0);
        showSwap(app);
    }

    private void showSwap(App app) {
        View target = favViews.get(swapAt);
        App out = ((Item) target.getTag()).app;
        for (View f : favViews) {
            f.animate().translationY(f == target ? -Kido.px(12) : 0).setDuration(200).setInterpolator(fluid).start();
        }
        GlowView g = zoneGlow[0];
        hiveMoved = false;
        glowBehind(target);
        pulse(g);
        setStatus("移動", "CAMBIAR", app.look.name.toUpperCase(Locale.ROOT) + " // POR " + out.look.name.toUpperCase(Locale.ROOT),
                "OK CAMBIAR", Kido.WARN);
        beeps.tick();
    }

    private void swapStep(int key) {
        App app = ((Item) moving.getTag()).app;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Back down into the honeycomb, still moving.
            for (View f : favViews) f.animate().translationY(0).setDuration(200).start();
            swapAt = -1;
            moveTo(1);
            glowBehind(moving);
            pulse(zoneGlow[1]);
            setStatus("移動", "MOVIENDO", app.look.name.toUpperCase(Locale.ROOT) + " // ELIGE EL LUGAR", "OK SOLTAR", Kido.WARN);
            return;
        }
        int next = swapAt + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0);
        if (next < 0 || next >= favViews.size() || next == swapAt) return;
        swapAt = next;
        showSwap(app);
    }

    /** The moved app takes the chosen main panel; the app that was there goes to the moved app's place. */
    private void commitSwap() {
        App in = ((Item) moving.getTag()).app;
        App out = ((Item) favViews.get(swapAt).getTag()).app;
        List<String> pins = shownPins();
        pins.set(swapAt, in.pkg);
        savePins(pins);
        List<String> order = new ArrayList<>();
        for (HexView h : hexViews) {
            String pkg = ((Item) h.getTag()).app.pkg;
            order.add(pkg.equals(in.pkg) ? out.pkg : pkg);
        }
        prefs.edit().putString("order", TextUtils.join(",", order)).apply();
        if (movePulse != null) movePulse.cancel();
        movePulse = null;
        moving = null;
        swapAt = -1;
        log("CAMBIO " + in.look.name.toUpperCase(Locale.ROOT) + " // " + out.look.name.toUpperCase(Locale.ROOT));
        rebuild(in.pkg);
    }

    private void pulse(GlowView g) {
        if (movePulse != null) movePulse.cancel();
        for (GlowView other : zoneGlow) if (other != null && other != g) other.setAlpha(0f);
        movePulse = ValueAnimator.ofFloat(1f, 0.35f);
        movePulse.setDuration(650);
        movePulse.setRepeatMode(ValueAnimator.REVERSE);
        movePulse.setRepeatCount(ValueAnimator.INFINITE);
        movePulse.addUpdateListener(a -> g.setAlpha((float) a.getAnimatedValue()));
        movePulse.start();
    }

    /** OK keeps the new order (and renumbers the channels); Back puts everything back as it was. */
    private void finishMove(boolean keep) {
        if (moving == null) return;
        if (swapAt >= 0) {
            if (keep) {
                commitSwap();
                return;
            }
            swapAt = -1;
        }
        Item it = (Item) moving.getTag();
        if (keep) {
            if (it.zone == 0) savePins(shownPins());
            else saveOrder();
        }
        if (movePulse != null) movePulse.cancel();
        movePulse = null;
        moving = null;
        log((keep ? "MOVIDO " : "SIN CAMBIOS ") + it.app.look.name.toUpperCase(Locale.ROOT));
        rebuild(it.app.pkg);
    }

    /** The place of the k-th hex in reading order: x, y and row, in alternating rows of five and four. */
    private static float[] hexSlot(int k) {
        int row = 0, start = 0;
        while (true) {
            int per = row % 2 == 0 ? 5 : 4;
            if (k < start + per) {
                int c = k - start;
                return new float[]{150 + c * 246 + (row % 2 == 1 ? 123 : 0), 452 + row * 214, row};
            }
            start += per;
            row++;
        }
    }


    /** Apps installed, removed or updated: rebuild now if KIDŌ is on screen, otherwise when it comes back. */
    private final BroadcastReceiver packageWatch = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (resumed && menu == null && launch.getVisibility() != View.VISIBLE) rebuild(null);
            else appsChanged = true;
        }
    };

    // ---------------------------------------------------------------- readouts and log

    /** Refreshes the data block under the clock once a second, and keeps the log ticking over when idle. */
    private final Runnable dataTick = new Runnable() {
        @Override
        public void run() {
            int linked = 0;
            for (TextView t : inputMeta.values()) if ("CONECTADO".contentEquals(t.getText())) linked++;
            long up = SystemClock.elapsedRealtime() / 1000;
            updateWifi();
            String date = dateLine();
            if (!date.contentEquals(dateText.getText())) dateText.setText(date);
            dataValues.setText(String.format(Locale.ROOT, "%04d  OK\n%d MB\n%02d:%02d:%02d\n%d // %d CONECT.",
                    sysCheck, freeMb(), up / 3600, up / 60 % 60, up % 60, inputCount, linked));
            renderTicker();
            if (SystemClock.uptimeMillis() - lastLogAt > 6000) {
                log(String.format(Locale.ROOT, "VERIF. %04d // PARIDAD %04X // OK", ++sysCheck, new Random().nextInt(0x10000)));
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Adds a line to the footer log. The newest entry is bright; the two before it trail off to the right. */
    private void log(String line) {
        lastLogAt = SystemClock.uptimeMillis();
        logLines.add(0, line);
        while (logLines.size() > 3) logLines.remove(3);
        renderTicker();
    }

    /** The footer: a live clock (it ticks every second, like the big one) and the latest log lines after it. */
    private void renderTicker() {
        if (ticker == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String now = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        sb.append(now);
        sb.setSpan(new ForegroundColorSpan(Kido.AMBER), 0, sb.length(), 0);
        for (int i = 0; i < logLines.size(); i++) {
            sb.append(i == 0 ? "  " : "   //   ");
            int start = sb.length();
            sb.append(logLines.get(i));
            if (i == 0) sb.setSpan(new ForegroundColorSpan(Kido.INK), start, sb.length(), 0);
        }
        ticker.setText(sb);
    }

    /** Real Wi-Fi strength for the signal bars and link readouts. A wired connection shows full bars. */
    private void updateWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        // Checked by transport: Android hides the Wi-Fi network id from apps without location access.
        boolean onWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        WifiInfo info = onWifi && wm != null ? wm.getConnectionInfo() : null;
        String quality;
        if (info != null && info.getRssi() > -127) {
            wifiLevel = WifiManager.calculateSignalLevel(info.getRssi(), 21) / 20f;
            wifiRate = info.getLinkSpeed() + " MBPS // " + info.getRssi() + " DBM";
            quality = wifiLevel > 0.7f ? "FUERTE" : wifiLevel > 0.45f ? "BUENA" : wifiLevel > 0.25f ? "REGULAR" : "DÉBIL";
        } else if (caps != null) {
            wifiLevel = 1f;
            wifiRate = "CABLE // OK";
            quality = "CABLE";
        } else {
            wifiLevel = 0f;
            wifiRate = "SIN ENLACE";
            quality = "SIN RED";
        }
        readLink.setText(quality);
        readLink.setTextColor(wifiLevel > 0.45f ? Kido.GREEN : wifiLevel > 0.25f ? Kido.AMBER : Kido.WARN);
        readPills.setLevel(wifiLevel);
        detailPills.setLevel(wifiLevel);
        detailRate.setText(wifiRate);
    }

    /** When an app was last updated, e.g. 12 OCT, with the year added if it isn't this year. */
    private String updatedOn(String pkg) {
        try {
            long t = getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
            boolean thisYear = new SimpleDateFormat("yyyy", Locale.US).format(new Date(t))
                    .equals(new SimpleDateFormat("yyyy", Locale.US).format(new Date()));
            return new SimpleDateFormat(thisYear ? "dd MMM" : "dd MMM yy", new Locale("es")).format(new Date(t)).replace(".", "").toUpperCase(Locale.ROOT);
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }

    private long freeMb() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
        return info.availMem / (1024 * 1024);
    }

    // ---------------------------------------------------------------- the Input button helper

    private static final String INPUT_SERVICE = "dev.glidian.kido/dev.glidian.kido.InputKeyService";

    private boolean inputKeyOn() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && list.contains(INPUT_SERVICE);
    }

    /**
     * Switches the Input button helper on or off. With it on, Input opens the inputs panel from any app, but
     * Android stops repeating a held volume key (it steps once per press); with it off, volume is continuous.
     * Needs WRITE_SECURE_SETTINGS, granted once over ADB.
     */
    private void toggleInputKey() {
        boolean on = !inputKeyOn();
        try {
            android.content.ContentResolver cr = getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<String> items = new ArrayList<>();
            if (list != null) for (String s : list.split(":")) if (!s.isEmpty() && !s.equals(INPUT_SERVICE)) items.add(s);
            if (on) items.add(INPUT_SERVICE);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", items));
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, items.isEmpty() ? 0 : 1);
            flashToast("入力", on ? "BOTÓN INPUT ENCENDIDO. EL VOLUMEN SUBE UN PASO POR PULSACIÓN."
                    : "BOTÓN INPUT APAGADO. EL VOLUMEN VUELVE A SUBIR SEGUIDO.");
            log("BOTÓN INPUT " + (on ? "SÍ" : "NO"));
        } catch (SecurityException e) {
            flashToast("入力", "FALTA EL PERMISO: pm grant dev.glidian.kido android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private void toggleSound() {
        boolean on = !beeps.isOn();
        beeps.setOn(on);
        prefs.edit().putBoolean("sound", on).apply();
        beeps.tick();
        flashToast("音響", on ? "SONIDO ACTIVADO. MANTÉN OK EN AJUSTES PARA SILENCIAR." : "SONIDO DESACTIVADO. MANTÉN OK EN AJUSTES PARA VOLVER A ACTIVARLO.");
        log("SONIDO " + (on ? "ACTIVADO" : "DESACTIVADO"));
    }

    // ---------------------------------------------------------------- input alert

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            TextView meta = inputMeta.get(inputId);
            if (meta == null) {
                refreshSoon();      // a CEC device on a port changed: its name may have changed too
                return;
            }
            boolean linked = state == TvInputManager.INPUT_STATE_CONNECTED;
            refreshDevices();
            if (linked && resumed) showAlert(inputId);
        }

        @Override
        public void onInputAdded(String inputId) {
            // A console that turns on announces itself over HDMI-CEC as a new input under its port.
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            TvInputInfo info = tim != null ? tim.getTvInputInfo(inputId) : null;
            String port = info != null ? info.getParentId() : null;
            boolean isNew = port != null && inputMeta.containsKey(port) && cecSeen.get(port) == null;
            refreshDevices();
            if (isNew && cecSeen.get(port) != null && resumed) showAlert(port);
        }

        @Override
        public void onInputRemoved(String inputId) {
            refreshSoon();
        }

        @Override
        public void onInputUpdated(String inputId) {
            refreshSoon();
        }

        @Override
        public void onTvInputInfoUpdated(TvInputInfo info) {
            refreshSoon();
        }
    };

    private final Runnable refreshDevicesRun = this::refreshDevices;

    /** CEC devices often announce themselves in a burst; wait for it to settle before relabelling. */
    private void refreshSoon() {
        handler.removeCallbacks(refreshDevicesRun);
        handler.postDelayed(refreshDevicesRun, 400);
    }

    /** Relabels every HDMI box with the device now on that port, or the port's own name when there is none. */
    private void refreshDevices() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return;
        for (Map.Entry<String, TextView> e : inputNameViews.entrySet()) {
            String id = e.getKey();
            String port = inputPortLabel.get(id);
            String dev = deviceOn(tim, id);
            String shown = dev != null ? dev : port;
            if (!shown.contentEquals(e.getValue().getText())) {
                e.getValue().setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Kido.px(30));
                e.getValue().setText(shown);
                fitLine(e.getValue(), inputNameWidth > 0 ? inputNameWidth : Kido.px(146));
            }
            String left = dev != null ? port : "PUERTO " + inputPortNo.get(id);
            TextView ml = inputPortViews.get(id);
            if (ml != null) ml.setText(left);
            String cec = cecDeviceOn(tim, id);
            cecSeen.put(id, cec);
            boolean linked = tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED || cec != null;
            String state = linked ? "CONECTADO" : "LIBRE";
            TextView meta = inputMeta.get(id);
            if (meta != null && !state.contentEquals(meta.getText())) meta.setText(state);
            String[] st = inputStatus.get(id);
            if (st != null) {
                st[2] = shown + " // " + left;
                st[3] = state;
            }
            inputLabels.put(id, shown);
        }
        if (alertInput != null && alert != null && alert.getVisibility() == View.VISIBLE) {
            alertDetail.setText(inputLabels.get(alertInput) + " // SEÑAL DETECTADA");
        }
    }

    /**
     * The name of what is plugged into an HDMI port: the name an HDMI-CEC device announces (for example
     * "PlayStation 5"), or failing that the name given to the port in the TV's input settings. Null when
     * neither is known, so the box keeps showing the port.
     */
    private String deviceOn(TvInputManager tim, String portId) {
        if (tim == null || portId == null) return null;
        String cec = cecDeviceOn(tim, portId);
        if (cec != null) return cec;
        TvInputInfo self = tim.getTvInputInfo(portId);
        return self != null ? nameOf(self, false) : null;
    }

    /** The name an HDMI-CEC device on this port announces, or null when the TV lists none under it. */
    private String cecDeviceOn(TvInputManager tim, String portId) {
        if (tim == null || portId == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!portId.equals(info.getParentId())) continue;
            String name = nameOf(info, true);
            if (name != null) return name;
        }
        return null;
    }

    private String nameOf(TvInputInfo info, boolean orLabel) {
        CharSequence custom = info.loadCustomLabel(this);
        if (custom != null && custom.toString().trim().length() > 0) return custom.toString().trim().toUpperCase(Locale.ROOT);
        if (!orLabel) return null;
        CharSequence label = info.loadLabel(this);
        return label != null && label.toString().trim().length() > 0 ? label.toString().trim().toUpperCase(Locale.ROOT) : null;
    }

    /** The panel that drops in when something is plugged into an HDMI port. OK switches to it. */
    private void buildAlert() {
        alert = new FrameLayout(this);
        alert.setBackground(box(0xFF050403, Kido.WARN, 3));
        alert.setVisibility(View.GONE);
        FrameLayout pulse = new FrameLayout(this);
        place(pulse, new Stripes(this, Kido.WARN), 0, 0, 1240, 26);
        place(pulse, new Stripes(this, Kido.WARN), 0, 254, 1240, 26);
        TextView k = text("入力検知", Kido.mincho, 110, Kido.WARN, 0);
        k.setTextScaleX(0.8f);
        k.setShadowLayer(Kido.px(24), 0, 0, Kido.withAlpha(Kido.WARN, 0.8f));
        place(pulse, k, 40, 74, -1, -1);
        place(pulse, text("ENTRADA DETECTADA", Kido.condBold, 52, Kido.WARN, 0.14f), 470, 62, -1, -1);
        alert.addView(pulse, match());
        alertDetail = text("", Kido.mono, 28, Kido.CREAM, 0.04f);
        place(alert, alertDetail, 474, 134, 740, -1);
        place(alert, text("OK  CAMBIAR AHORA      ATRÁS  DESCARTAR", Kido.mono, 22, Kido.DIM, 0.06f), 474, 192, -1, -1);
        place(root, alert, 340, 400, 1240, 280);
        unclip(k);
        // A slow pulse, well under three flashes a second.
        alertPulse = ValueAnimator.ofFloat(1f, 0.45f);
        alertPulse.setDuration(700);
        alertPulse.setRepeatMode(ValueAnimator.REVERSE);
        alertPulse.setRepeatCount(ValueAnimator.INFINITE);
        alertPulse.addUpdateListener(a -> pulse.setAlpha((float) a.getAnimatedValue()));
    }

    private void showAlert(String inputId) {
        alertInput = inputId;
        String label = inputLabels.get(inputId);
        alertDetail.setText(label + " // SEÑAL DETECTADA");
        alert.animate().cancel();
        alert.setVisibility(View.VISIBLE);
        alert.setAlpha(0f);
        alert.setTranslationY(-Kido.px(40));
        alert.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
        alertPulse.start();
        beeps.alert();
        log("ALERTA // ENTRADA DETECTADA " + label);
        handler.removeCallbacks(hideAlert);
        handler.postDelayed(hideAlert, 8000);
    }

    private final Runnable hideAlert = () -> {
        handler.removeCallbacks(this.hideAlert);
        if (alert.getVisibility() != View.VISIBLE) return;
        alert.animate().cancel();
        alert.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            alert.setVisibility(View.GONE);
            alertPulse.cancel();
        }).start();
    };

    // ---------------------------------------------------------------- boot check

    /** Runs the system check once per TV boot, the first time KIDŌ comes up. */
    private void maybeBoot() {
        int count = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (count == -1 || count == prefs.getInt("boot", -2)) return;
        prefs.edit().putInt("boot", count).apply();
        showBoot();
    }

    /** A short system check: lines come up one by one, a bar fills, then it fades to the home screen. Any key skips it. */
    private void showBoot() {
        FrameLayout b = new FrameLayout(this);
        b.setBackgroundColor(Kido.VOID);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = row();
        head.setGravity(Gravity.BOTTOM);
        TextView k = text("起動確認", Kido.mincho, 120, 0xFFFFFFFF, 0);
        k.setTextScaleX(0.8f);
        k.setShadowLayer(Kido.px(24), 0, 0, Kido.withAlpha(Kido.ORANGE, 0.6f));
        head.addView(k);
        head.addView(gap(30));
        LinearLayout hs = new LinearLayout(this);
        hs.setOrientation(LinearLayout.VERTICAL);
        hs.setPadding(0, 0, 0, Kido.px(18));
        hs.addView(text("VERIFICACIÓN DEL SISTEMA", Kido.condBold, 44, Kido.AMBER, 0.16f));
        hs.addView(text("KIDŌ v" + versionName(), Kido.mono, 24, Kido.DIM, 0.06f));
        head.addView(hs);
        col.addView(head);
        col.addView(glowRule(Kido.AMBER));

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        String[][] checks = {
                {"SUBSISTEMA 01", "承認 APROBADO"},
                {"SUBSISTEMA 02", "承認 APROBADO"},
                {"SUBSISTEMA 03", "承認 APROBADO"},
                {"PANTALLA // " + dm.widthPixels + "x" + dm.heightPixels, "OK"},
                {"BUS DE ENTRADA // " + inputCount + " PUERTOS", "OK"},
                {"CANALES // " + channelCount + " CARGADOS", "OK"},
                {"MEMORIA LIBRE // " + freeMb() + " MB", "OK"}};
        List<View> steps = new ArrayList<>();
        for (String[] c : checks) {
            LinearLayout r = row();
            r.addView(text(c[0], Kido.mono, 30, Kido.INK, 0.04f), new LinearLayout.LayoutParams(Kido.px(820), -2));
            r.addView(text(c[1], Kido.mono, 30, Kido.GREEN, 0.04f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Kido.px(14);
            r.setAlpha(0f);
            col.addView(r, lp);
            steps.add(r);
        }
        TextView done = text("全系統 正常   TODOS LOS SISTEMAS NORMALES", Kido.condBold, 44, Kido.GREEN, 0.12f);
        done.setShadowLayer(Kido.px(18), 0, 0, Kido.withAlpha(Kido.GREEN, 0.55f));
        done.setAlpha(0f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Kido.px(30);
        col.addView(done, dlp);
        steps.add(done);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(box(0, Kido.LINE, 2));
        View fill = new View(this);
        fill.setBackgroundColor(Kido.GREEN);
        fill.setPivotX(0);
        fill.setScaleX(0f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1);
        flp.setMargins(Kido.px(5), Kido.px(5), Kido.px(5), Kido.px(5));
        track.addView(fill, flp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, Kido.px(28));
        tlp.topMargin = Kido.px(24);
        col.addView(track, tlp);
        TextView build = text("VERSIÓN // GLIDIAN.DEV", Kido.mono, 20, Kido.DIM, 0.12f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Kido.px(22);
        col.addView(build, blp);
        b.addView(col, new FrameLayout.LayoutParams(Kido.px(1300), -2, Gravity.CENTER));
        root.addView(b, root.indexOfChild(scan), match());
        unclip(k);
        boot = b;

        for (int i = 0; i < steps.size(); i++) {
            View s = steps.get(i);
            b.postDelayed(() -> s.animate().alpha(1f).setDuration(120).start(), 300L + i * 170L);
        }
        long filled = 300L + steps.size() * 170L;
        b.postDelayed(() -> fill.animate().scaleX(1f).setDuration(450).setInterpolator(snap).start(), filled);
        b.postDelayed(() -> {
            if (boot == b) beeps.boot();
        }, filled + 450);
        b.postDelayed(this::endBoot, filled + 1000);
    }

    private void endBoot() {
        if (boot == null) return;
        FrameLayout b = boot;
        boot = null;
        b.animate().alpha(0f).setDuration(400).withEndAction(() -> root.removeView(b)).start();
        intro(zone);
        log("ARRANQUE COMPLETO // TODOS LOS SISTEMAS NORMALES");
    }

    // ---------------------------------------------------------------- small helpers

    private TextView text(String s, Typeface tf, float size, int color, float spacing) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Kido.px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (spacing != 0) t.setLetterSpacing(spacing);
        return t;
    }

    /** Shrinks the text until its longest word fits on one line, so names never break mid-word. */
    /** Shrinks a single-line label until it fits the width, for long device names on narrow input boxes. */
    private static void fitLine(TextView t, int width) {
        if (width <= 0) return;
        float size = t.getTextSize();
        Paint p = new Paint(t.getPaint());
        p.setTextSize(size);
        float w = p.measureText(t.getText().toString());
        if (w > width) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * width / w * 0.98f);
    }

    private static void fitWords(TextView t, int width) {
        Paint p = new Paint(t.getPaint());
        float size = t.getTextSize();
        for (String word : t.getText().toString().split(" ")) {
            p.setTextSize(size);
            float w = p.measureText(word);
            if (w > width) size = size * width / w;
        }
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
    }

    /** Lets a glowing view draw its shadow past its own edges by turning off clipping in every parent above it. */
    private static void unclip(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ((ViewGroup) p).setClipChildren(false);
        }
    }

    private static String dateLine() {
        return new SimpleDateFormat("EEE dd MMM yyyy", new Locale("es")).format(new Date()).replace(".", "").toUpperCase(Locale.ROOT) + "  //  SISTEMA NORMAL";
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private View gap(float w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Kido.px(w), 1));
        return v;
    }

    /** A 6px rule in the app's color with a soft glow; negative margins keep the spacing of a plain rule. */
    private View glowRule(int color) {
        GlowView g = new GlowView(this, Kido.px(14), false);
        g.setColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Kido.px(34));
        lp.topMargin = -Kido.px(14);
        lp.bottomMargin = -Kido.px(14);
        g.setLayoutParams(lp);
        return g;
    }

    private static GradientDrawable box(int fill, int stroke, float strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Kido.px(strokeWidth), stroke);
        return d;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    /** Places a child on the 1920x1080 grid; a negative width or height means wrap content. */
    private static void place(ViewGroup parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w < 0 ? -2 : Kido.px(w), h < 0 ? -2 : Kido.px(h));
        lp.leftMargin = Kido.px(x);
        lp.topMargin = Kido.px(y);
        parent.addView(v, lp);
    }

    private static String randomBits(int n) {
        StringBuilder b = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < n; i++) b.append(r.nextBoolean() ? '1' : '0');
        return b.toString();
    }

    /** Diagonal hazard stripes. */
    static final class Stripes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Stripes(Context c, int color) {
            super(c);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), step = Kido.px(44), band = Kido.px(22);
            path.reset();
            for (float x = -h; x < getWidth() + h; x += step) {
                path.moveTo(x, h);
                path.lineTo(x + h, 0);
                path.lineTo(x + h + band, 0);
                path.lineTo(x + band, h);
                path.close();
            }
            c.drawPath(path, paint);
        }
    }
}
