package dev.glidian.kido;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the remote's Input button open KIDŌ's inputs panel from anywhere, as the stock launcher did.
 * It only looks at that one button: every other key goes on untouched, and it reads no screen content.
 * Trade-off on this TV: while any key filter is bound, Android stops repeating a held volume key, so
 * KIDŌ's settings menu can switch this helper off (BOTÓN INPUT).
 */
public class InputKeyService extends AccessibilityService {
    /** The keys an Input or Source button sends: TV_INPUT on the Mi TV P1 remote, MEDIA_RECORD on some Xiaomi firmware. */
    static final int[] INPUT_KEYS = {KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_MEDIA_RECORD};

    static boolean isInputKey(int code) {
        for (int k : INPUT_KEYS) if (k == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (!isInputKey(c)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0 && !e.isCanceled()) {
            if (!InputPanelActivity.closeIfOpen()) {
                Intent i = new Intent(this, InputPanelActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(i);
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
