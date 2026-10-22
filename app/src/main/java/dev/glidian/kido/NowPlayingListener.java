package dev.glidian.kido;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can give KIDŌ access to media sessions for the NOW PLAYING readout: Android ties
 * that access to a notification listener. It overrides nothing, so it never looks at notifications.
 */
public class NowPlayingListener extends NotificationListenerService {
}
