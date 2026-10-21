package kz.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Opens the launcher when the head unit boots or wakes up on ACC ON. */
public class BootReceiver extends BroadcastReceiver {

    static boolean enabled(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("autostart", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply();
    }

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (!enabled(ctx)) return;
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        // Give the factory software a moment to finish its own start-up, then bring us up.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(app, MainActivity.class)
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    app.startActivity(i);
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, 2500);
    }
}
