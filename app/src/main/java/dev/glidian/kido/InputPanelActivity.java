package dev.glidian.kido;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The inputs panel the remote's Input button opens over any app, like the stock launcher's side panel:
 * every HDMI port (named after the console on it when the TV knows), AV and TV. OK switches, Back closes.
 */
public class InputPanelActivity extends Activity {
    private static WeakReference<InputPanelActivity> open = new WeakReference<>(null);

    /** The Input key again while the panel is up closes it. */
    static boolean closeIfOpen() {
        InputPanelActivity a = open.get();
        if (a == null || a.isFinishing()) return false;
        a.close();
        return true;
    }

    private static final int W = 560;
    private final PathInterpolator fluid = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private LinearLayout panel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Kido.scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        Kido.loadFonts(this);
        Kido.applyTheme(getSharedPreferences("kido", MODE_PRIVATE).getInt("theme", 0));
        open = new WeakReference<>(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> close());
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2050403);
        bg.setStroke(Kido.px(2), Kido.AMBER);
        panel.setBackground(bg);
        panel.setPadding(Kido.px(34), Kido.px(40), Kido.px(34), Kido.px(30));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.addView(text("入力", Kido.mincho, 64, Kido.AMBER, 0));
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(Kido.px(18), 1));
        head.addView(text("ENTRADAS", Kido.condBold, 38, Kido.INK, 0.16f));
        panel.addView(head);
        TextView sub = text("ELIGE UNA FUENTE // ATRÁS CERRAR", Kido.mono, 20, Kido.DIM, 0.06f);
        sub.setPadding(0, Kido.px(8), 0, Kido.px(22));
        panel.addView(sub);

        List<View> rows = new ArrayList<>();
        for (Entry e : entries()) {
            View r = row(e);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Kido.px(96));
            lp.topMargin = Kido.px(12);
            panel.addView(r, lp);
            rows.add(r);
        }
        if (rows.isEmpty()) panel.addView(text("SIN ENTRADAS", Kido.condBold, 30, Kido.DIM, 0.12f));
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setId(View.generateViewId());
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
        }

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Kido.px(W), -1, Gravity.END);
        root.addView(panel, plp);
        setContentView(root);
        panel.setTranslationX(Kido.px(W));
        panel.animate().translationX(0).setDuration(260).setInterpolator(fluid).start();
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (open.get() == this) open = new WeakReference<>(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean closing;

    private void close() {
        if (closing) return;
        closing = true;
        panel.animate().translationX(Kido.px(W)).setDuration(200).setInterpolator(fluid)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    // ---------------------------------------------------------------- the list

    static final class Entry {
        final String kanji, label, meta, state;
        final Intent intent;

        Entry(String kanji, String label, String meta, String state, Intent intent) {
            this.kanji = kanji;
            this.label = label;
            this.meta = meta;
            this.state = state;
            this.intent = intent;
        }
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> all = tim.getTvInputList();
        List<String> hdmiIds = new ArrayList<>();
        for (TvInputInfo i : all) if (i.isPassthroughInput() && i.getType() == TvInputInfo.TYPE_HDMI) hdmiIds.add(i.getId());
        List<TvInputInfo> ports = new ArrayList<>(), av = new ArrayList<>();
        for (TvInputInfo i : all) {
            if (!i.isPassthroughInput()) continue;
            if (i.getType() == TvInputInfo.TYPE_HDMI) {
                String parent = i.getParentId();
                if (parent == null || !hdmiIds.contains(parent)) ports.add(i);
            } else if (i.getType() == TvInputInfo.TYPE_COMPOSITE || i.getType() == TvInputInfo.TYPE_COMPONENT
                    || i.getType() == TvInputInfo.TYPE_SVIDEO || i.getType() == TvInputInfo.TYPE_SCART
                    || i.getType() == TvInputInfo.TYPE_VGA) {
                av.add(i);
            }
        }
        Collections.sort(ports, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        int n = 1;
        for (TvInputInfo p : ports) {
            String port = label(p);
            if (!port.contains("HDMI")) port = "HDMI " + port;
            String dev = null;
            for (TvInputInfo c : all) {
                if (!p.getId().equals(c.getParentId())) continue;
                dev = name(c, true);
                if (dev != null) break;
            }
            if (dev == null) dev = name(p, false);
            boolean linked = tim.getInputState(p.getId()) == TvInputManager.INPUT_STATE_CONNECTED || hasChild(all, p.getId());
            out.add(new Entry("入力", dev != null ? dev : port, dev != null ? port : "PUERTO " + n, linked ? "CONECTADO" : "LIBRE",
                    passthrough(p.getId())));
            n++;
        }
        for (TvInputInfo a : av) {
            String l = label(a);
            out.add(new Entry("入力", l.isEmpty() ? "AV" : l, "ANALÓGICA", tim.getInputState(a.getId()) == TvInputManager.INPUT_STATE_CONNECTED ? "CONECTADO" : "LIBRE",
                    passthrough(a.getId())));
        }
        Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
        if (tv != null) out.add(new Entry("放送", "TV", "ANTENA // CABLE", "", tv));
        return out;
    }

    private static boolean hasChild(List<TvInputInfo> all, String id) {
        for (TvInputInfo c : all) if (id.equals(c.getParentId())) return true;
        return false;
    }

    private static Intent passthrough(String id) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel");
    }

    private String label(TvInputInfo i) {
        CharSequence l = i.loadLabel(this);
        return l == null ? "" : l.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String name(TvInputInfo i, boolean orLabel) {
        CharSequence c = i.loadCustomLabel(this);
        if (c != null && c.toString().trim().length() > 0) return c.toString().trim().toUpperCase(Locale.ROOT);
        if (!orLabel) return null;
        String l = label(i);
        return l.isEmpty() ? null : l;
    }

    private View row(Entry e) {
        FrameLayout r = new FrameLayout(this);
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Kido.PANEL);
        bg.setStroke(Kido.px(2), Kido.LINE);
        r.setBackground(bg);
        TextView k = text(e.kanji, Kido.mincho, 40, Kido.AMBER, 0);
        place(r, k, 22, 22);
        TextView l = text(e.label, Kido.condBold, 32, Kido.CREAM, 0.1f);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(Kido.px(W - 68 - 130 - 24), -2);
        llp.leftMargin = Kido.px(110);
        llp.topMargin = Kido.px(14);
        r.addView(l, llp);
        TextView m = text(e.meta, Kido.mono, 20, Kido.DIM, 0.04f);
        place(r, m, 110, 58);
        TextView s = text(e.state, Kido.mono, 20, Kido.DIM, 0.04f);
        s.setGravity(Gravity.END);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Kido.px(150), -2, Gravity.END);
        slp.rightMargin = Kido.px(20);
        slp.topMargin = Kido.px(58);
        r.addView(s, slp);
        r.setOnFocusChangeListener((v, has) -> {
            int dark = 0xFF021A0A;
            bg.setColor(has ? Kido.GREEN : Kido.PANEL);
            bg.setStroke(Kido.px(has ? 3 : 2), has ? Kido.FOCUS_RIM : Kido.LINE);
            k.setTextColor(has ? dark : Kido.AMBER);
            l.setTextColor(has ? dark : Kido.CREAM);
            m.setTextColor(has ? dark : Kido.DIM);
            s.setTextColor(has ? dark : Kido.DIM);
            v.animate().translationX(has ? -Kido.px(10) : 0).setDuration(180).setInterpolator(fluid).start();
        });
        r.setOnClickListener(v -> {
            try {
                startActivity(e.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ex) {
                Toast.makeText(this, "No se puede abrir esa entrada", Toast.LENGTH_SHORT).show();
            }
            finish();
            overridePendingTransition(0, 0);
        });
        return r;
    }

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

    private static void place(FrameLayout parent, View v, float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Kido.px(x);
        lp.topMargin = Kido.px(y);
        parent.addView(v, lp);
    }
}
