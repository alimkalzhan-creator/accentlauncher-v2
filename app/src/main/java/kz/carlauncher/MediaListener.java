package kz.carlauncher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Notification access: lets the launcher read the playing track of other apps
 *  and the turn-by-turn hints that navigation apps post as notifications. */
public class MediaListener extends NotificationListenerService {

    public interface NavListener {
        void onNav(String app, String title, String text, String iconDataUri);
        void onNavGone();
    }

    static volatile NavListener navListener;
    static volatile MediaListener inst;
    private static String lastKey = null;

    static boolean isNavPkg(String p) {
        if (p == null) return false;
        return p.equals("ru.yandex.yandexnavi") || p.equals("ru.yandex.yandexmaps")
                || p.equals("ru.dublgis.dgismobile") || p.equals("com.google.android.apps.maps")
                || p.equals("com.waze") || p.equals("com.sygic.aura") || p.equals("com.navitel")
                || p.equals("cityguide.probki.net") || p.contains("navi");
    }

    @Override
    public void onListenerConnected() {
        inst = this;
        resend();
    }

    @Override
    public void onListenerDisconnected() {
        inst = null;
    }

    static void resend() {
        MediaListener m = inst;
        if (m == null) return;
        try {
            StatusBarNotification[] all = m.getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification sbn : all) {
                if (isNavPkg(sbn.getPackageName()) && (sbn.isOngoing())) { m.handle(sbn); return; }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isNavPkg(sbn.getPackageName()) && sbn.isOngoing()) handle(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn.getKey() != null && sbn.getKey().equals(lastKey)) {
            lastKey = null;
            NavListener l = navListener;
            if (l != null) l.onNavGone();
        }
    }

    private void handle(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            if (ex == null) return;
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null) text = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (title == null && text == null) return;
            String icon = "";
            Bitmap bmp = null;
            Object li = ex.get(Notification.EXTRA_LARGE_ICON);
            if (li instanceof Bitmap) bmp = (Bitmap) li;
            else if (Build.VERSION.SDK_INT >= 23 && li instanceof Icon) {
                Drawable d = ((Icon) li).loadDrawable(this);
                if (d != null) bmp = drawableToBitmap(d);
            }
            if (bmp == null && Build.VERSION.SDK_INT >= 23 && n.getLargeIcon() != null) {
                Drawable d = n.getLargeIcon().loadDrawable(this);
                if (d != null) bmp = drawableToBitmap(d);
            }
            if (bmp != null) icon = MainActivity.toDataUri(bmp, 96, false);
            lastKey = sbn.getKey();
            String app = sbn.getPackageName();
            try { app = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app, 0))); }
            catch (Exception ignored) {}
            NavListener l = navListener;
            if (l != null) l.onNav(app, title == null ? "" : title.toString(), text == null ? "" : text.toString(), icon);
        } catch (Exception ignored) {}
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        int w = Math.max(1, Math.min(192, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96));
        int h = Math.max(1, Math.min(192, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }
}
