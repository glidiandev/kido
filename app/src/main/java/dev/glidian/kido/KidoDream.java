package dev.glidian.kido;

import android.app.ActivityManager;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * KIDŌ's screensaver: a 待機 standby block with the clock and a few live readouts on black. The block
 * drifts slowly to a new spot every 20 seconds so nothing sits still long enough to burn in.
 */
public class KidoDream extends DreamService {
    private static final int W = 940, H = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout block;
    private TextView date, data;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long up = SystemClock.elapsedRealtime() / 1000;
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
            date.setText(new SimpleDateFormat("EEE dd MMM yyyy", new Locale("es")).format(new Date()).replace(".", "").toUpperCase(Locale.ROOT));
            data.setText(String.format(Locale.ROOT, "ACTIVO %02d:%02d:%02d   //   MEM LIBRE %d MB   //   SIN ACTIVIDAD",
                    up / 3600, up / 60 % 60, up % 60, mem.availMem / (1024 * 1024)));
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            block.animate().translationX(spot(1920 - W)).translationY(spot(1080 - H))
                    .setDuration(6000).setInterpolator(new AccelerateDecelerateInterpolator()).start();
            handler.postDelayed(this, 20_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Kido.scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        Kido.loadFonts(this);
        Kido.applyTheme(getSharedPreferences("kido", MODE_PRIVATE).getInt("theme", 0));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        block = new FrameLayout(this);
        block.setAlpha(0.85f);

        TextView k = text("待機", Kido.mincho, 230, 0xFFFFFFFF, 0);
        k.setTextScaleX(0.76f);
        place(block, k, 0, 0, -1, -1);
        place(block, new SegClockView(this), 390, 28, 560, 150);
        date = text("", Kido.mono, 24, Kido.DIM, 0.06f);
        place(block, date, 404, 186, -1, -1);
        place(block, text("EN ESPERA // KIDŌ v" + versionName(), Kido.condBold, 34, Kido.AMBER, 0.16f), 404, 224, -1, -1);
        View rule = new View(this);
        rule.setBackgroundColor(Kido.withAlpha(Kido.AMBER, 0.6f));
        place(block, rule, 6, 300, W - 6, 4);
        data = text("", Kido.mono, 22, Kido.DIM, 0.06f);
        place(block, data, 6, 324, -1, -1);

        root.addView(block, new FrameLayout.LayoutParams(Kido.px(W), Kido.px(H)));
        block.setTranslationX(spot(1920 - W));
        block.setTranslationY(spot(1080 - H));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.post(tick);
        handler.postDelayed(drift, 20_000);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    /** A random position along one axis, keeping a margin from the screen edge. */
    private float spot(int range) {
        int margin = 60;
        return Kido.px(margin + random.nextInt(Math.max(1, range - margin * 2)));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private TextView text(String s, Typeface tf, float size, int color, float spacing) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Kido.px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (spacing != 0) t.setLetterSpacing(spacing);
        return t;
    }

    private static void place(ViewGroup parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : Kido.px(w), h < 0 ? -2 : Kido.px(h));
        lp.leftMargin = Kido.px(x);
        lp.topMargin = Kido.px(y);
        parent.addView(v, lp);
    }
}
