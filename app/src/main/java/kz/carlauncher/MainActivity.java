package kz.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 11;
    private static final int REQ_CAR_PHOTO = 12;
    private static final int REQ_AUDIO = 13;
    private static final int REQ_BG_PHOTO = 14;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> iconCache = new HashMap<>();
    private LocationManager lm;
    private boolean locationStarted = false;

    private MediaSessionManager msm;
    private MediaController controller;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // The page never plays sound itself, so it must never grab audio focus from the radio.
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });
        web.setHapticFeedbackEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideBars();
        registerCarReceiver();
    }

    // ---------------------------------------------------------------- car state (lights / doors)

    private boolean lastNight = false;

    boolean isNight() {
        int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        boolean n = isNight();
        if (n != lastNight) {
            lastNight = n;
            js("window.onNight&&onNight(" + n + ")");
        }
    }

    private android.content.BroadcastReceiver carReceiver;

    private static boolean flag(Bundle b, String k) {
        if (b == null || !b.containsKey(k)) return false;
        Object v = b.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v != null) { String s = v.toString().trim().toLowerCase(Locale.ROOT); return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("open"); }
        return false;
    }

    private void registerCarReceiver() {
        carReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                Bundle b = intent.getExtras();
                if (b == null) return;
                try {
                    JSONObject o = new JSONObject();
                    for (String k : new String[]{"lights", "fl", "fr", "rl", "rr", "trunk", "hood"}) {
                        if (b.containsKey(k)) o.put(k, flag(b, k));
                    }
                    js("window.onCar&&onCar(" + q(o.toString()) + ")");
                } catch (Exception ignored) {}
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter("kz.carlauncher.CAR_STATE");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(carReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(carReceiver, f);
        } catch (Exception ignored) {}
        lastNight = isNight();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        startMediaWatch();
        attachPlayer();
        if (web != null) { web.onResume(); web.resumeTimers(); }
        if (locationWanted) startLocation();
        js("window.onResume&&onResume()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // While another app (radio, navigator...) is on screen the launcher goes quiet:
        // no GPS polling, no JS timers, no redraws -> less CPU/RAM pressure on the head unit,
        // so the system does not kill the radio or other background apps.
        stopLocation();
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        synchronized (iconCache) { iconCache.clear(); }
        bgCache = null;
        if (web != null && level >= TRIM_MEMORY_RUNNING_LOW) web.freeMemory();
    }

    private boolean locationWanted = false;

    // ---------------------------------------------------------------- default launcher / autostart

    boolean isDefaultHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private static final int REQ_ROLE_HOME = 15;

    void requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQ_ROLE_HOME);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // Older Android: briefly enable a dummy home activity so the system forgets the old
        // default and shows the "Choose home app / Always" dialog.
        try {
            PackageManager pm = getPackageManager();
            ComponentName fake = new ComponentName(this, FakeHome.class);
            pm.setComponentEnabledSetting(fake, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            pm.setComponentEnabledSetting(fake, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 21 && startSafe(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
            startSafe(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        stopMediaWatch();
        PlayerService.listener = null;
        try { if (carReceiver != null) unregisterReceiver(carReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public void onBackPressed() {
        // Launcher: back closes overlays in the page, never exits.
        js("window.onBack&&onBack()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while already home -> close overlays.
        js("window.onBack&&onBack()");
    }

    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ---------------------------------------------------------------- location

    private void startLocation() {
        locationWanted = true;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (locationStarted) return;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000, 0, locListener, Looper.getMainLooper());
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            }
            locationStarted = true;
            if (best != null) sendLocation(best);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        try { if (lm != null) lm.removeUpdates(locListener); } catch (Exception ignored) {}
        locationStarted = false;
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { sendLocation(l); }
        @Override public void onStatusChanged(String p, int st, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void sendLocation(Location l) {
        js("window.onLocation&&onLocation(" + l.getLatitude() + "," + l.getLongitude() + ","
                + (l.hasAltitude() ? l.getAltitude() : "null") + ","
                + l.getBearing() + "," + (l.hasSpeed() ? l.getSpeed() : 0) + ","
                + (l.hasBearing() ? "true" : "false") + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }
        if (requestCode == REQ_AUDIO) {
            PlayerService.scan(this);
        }
    }

    // ---------------------------------------------------------------- local player

    private boolean playerAttached = false;

    private void attachPlayer() {
        MediaListener.navListener = new MediaListener.NavListener() {
            @Override public void onNav(String app, String title, String text, String icon) {
                js("window.onNav&&onNav(" + q(app) + "," + q(title) + "," + q(text) + "," + q(icon) + ")");
            }
            @Override public void onNavGone() { js("window.onNavGone&&onNavGone()"); }
        };
        MediaListener.resend();
        PlayerService.listener = new PlayerService.Listener() {
            @Override public void onState(String json) { js("window.onLocalState&&onLocalState(" + q(json) + ")"); }
            @Override public void onArt(String uri) { js("window.onLocalArt&&onLocalArt(" + q(uri) + ")"); }
            @Override public void onList(String json) { js("window.onLocalList&&onLocalList(" + q(json) + ")"); }
        };
        if (!playerAttached) {
            playerAttached = true;
            if (hasAudioPermission() && PlayerService.tracks.isEmpty()) PlayerService.scan(this);
        }
    }

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void playerIntent(String action, String key, Object val) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        if (val instanceof Integer) i.putExtra(key, (Integer) val);
        else if (val != null) i.putExtra(key, String.valueOf(val));
        try { startService(i); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- weather

    private void fetchWeather(final double lat, final double lon) {
        new Thread(new Runnable() {
            @Override public void run() {
                String city = "";
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> a = new Geocoder(MainActivity.this, new Locale("ru")).getFromLocation(lat, lon, 1);
                        if (a != null && !a.isEmpty()) {
                            Address ad = a.get(0);
                            city = ad.getLocality() != null ? ad.getLocality()
                                    : (ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea());
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    String u = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,surface_pressure,uv_index"
                                    + "&wind_speed_unit=ms&timezone=auto", lat, lon);
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    c.disconnect();
                    js("window.onWeather&&onWeather(" + q(sb.toString()) + "," + q(city) + ")");
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ---------------------------------------------------------------- media

    private boolean hasMusicAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void startMediaWatch() {
        if (Build.VERSION.SDK_INT < 21 || !hasMusicAccess()) {
            js("window.onMusicAccess&&onMusicAccess(false)");
            return;
        }
        try {
            if (msm == null) msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            final ComponentName cn = new ComponentName(this, MediaListener.class);
            if (sessionsListener == null) {
                sessionsListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override public void onActiveSessionsChanged(List<MediaController> list) { pickController(list); }
                };
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
            }
            pickController(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            js("window.onMusicAccess&&onMusicAccess(false)");
        } catch (Exception ignored) {}
    }

    private void stopMediaWatch() {
        try {
            if (msm != null && sessionsListener != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
            if (controller != null) controller.unregisterCallback(mediaCb);
        } catch (Exception ignored) {}
        sessionsListener = null;
        controller = null;
    }

    private void pickController(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            List<MediaController> other = new java.util.ArrayList<>();
            for (MediaController mc : list) if (!getPackageName().equals(mc.getPackageName())) other.add(mc);
            list = other;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { chosen = mc; break; }
            }
            if (chosen == null && !list.isEmpty()) chosen = list.get(0);
        }
        if (controller != null) {
            try { controller.unregisterCallback(mediaCb); } catch (Exception ignored) {}
        }
        controller = chosen;
        if (controller != null) {
            controller.registerCallback(mediaCb, ui);
        }
        pushMedia(true);
    }

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { pushMedia(true); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { pushMedia(false); }
        @Override public void onSessionDestroyed() {
            controller = null;
            pushMedia(true);
        }
    };

    private void pushMedia(boolean withArt) {
        String title = "", artist = "", app = "", art = null;
        boolean playing = false;
        if (controller != null) {
            MediaMetadata md = controller.getMetadata();
            if (md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                if (withArt) {
                    Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    art = b != null ? toDataUri(b, 220, true) : "";
                }
            }
            PlaybackState st = controller.getPlaybackState();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            app = labelOf(controller.getPackageName());
        } else if (withArt) {
            art = "";
        }
        js("window.onMedia&&onMedia(" + q(title) + "," + q(artist) + "," + q(app) + ","
                + (art == null ? "null" : q(art)) + "," + playing + ")");
    }

    private void mediaCommand(String cmd) {
        if (controller != null) {
            MediaController.TransportControls t = controller.getTransportControls();
            PlaybackState st = controller.getPlaybackState();
            switch (cmd) {
                case "prev": t.skipToPrevious(); return;
                case "next": t.skipToNext(); return;
                default:
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                    return;
            }
        }
        int code = "prev".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : "next".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        }
    }

    // ---------------------------------------------------------------- apps & icons

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Bitmap drawableToBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    static String toDataUri(Bitmap b, int maxSide, boolean jpeg) {
        int w = b.getWidth(), h = b.getHeight();
        float k = Math.min(1f, (float) maxSide / Math.max(w, h));
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 85, bos);
        return (jpeg ? "data:image/jpeg;base64," : "data:image/png;base64,")
                + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private File carFile() { return new File(getFilesDir(), "car.png"); }
    private File bgFile() { return new File(getFilesDir(), "bg.jpg"); }
    private volatile String bgCache = null;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE_HOME) { js("window.onResume&&onResume()"); return; }
        if ((requestCode != REQ_CAR_PHOTO && requestCode != REQ_BG_PHOTO) || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean isBg = requestCode == REQ_BG_PHOTO;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in = getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(in, null, o);
                    if (in != null) in.close();
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > (isBg ? 2600 : 1600)) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    in = getContentResolver().openInputStream(uri);
                    Bitmap b = BitmapFactory.decodeStream(in, null, o2);
                    if (in != null) in.close();
                    if (b == null) return;
                    float k = Math.min(1f, (isBg ? 1920f : 1200f) / Math.max(b.getWidth(), b.getHeight()));
                    if (k < 1f) b = Bitmap.createScaledBitmap(b, (int) (b.getWidth() * k), (int) (b.getHeight() * k), true);
                    if (isBg) {
                        FileOutputStream f2 = new FileOutputStream(bgFile());
                        b.compress(Bitmap.CompressFormat.JPEG, 88, f2);
                        f2.close();
                        bgCache = null;
                        js("window.onBgPhoto&&onBgPhoto()");
                        return;
                    }
                    FileOutputStream fos = new FileOutputStream(carFile());
                    b.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    js("window.onCarPhoto&&onCarPhoto()");
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ---------------------------------------------------------------- JS bridge

    public class Bridge {

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "1.0"; }
        }

        @JavascriptInterface
        public String getApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                HashMap<String, Boolean> seen = new HashMap<>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || seen.containsKey(pkg)) continue;
                    seen.put(pkg, true);
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", String.valueOf(ri.loadLabel(pm)));
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface
        public String getIcon(String pkg) {
            synchronized (iconCache) {
                String c = iconCache.get(pkg);
                if (c != null) return c;
            }
            String r = "";
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                r = toDataUri(drawableToBitmap(d, 96), 96, false);
            } catch (Exception ignored) {}
            synchronized (iconCache) { iconCache.put(pkg, r); }
            return r;
        }

        @JavascriptInterface
        public boolean isInstalled(String pkg) {
            try {
                return getPackageManager().getLaunchIntentForPackage(pkg) != null;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean launch(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openSystemSettings() {
            startSafe(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public void openUrl(String url) {
            startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }

        @JavascriptInterface
        public void openBtSettings() {
            if (!startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) startSafe(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public boolean isNight() { return MainActivity.this.isNight(); }

        @JavascriptInterface
        public boolean isDefaultHome() { return MainActivity.this.isDefaultHome(); }

        @JavascriptInterface
        public void requestDefaultHome() {
            ui.post(new Runnable() { @Override public void run() { MainActivity.this.requestDefaultHome(); } });
        }

        @JavascriptInterface
        public boolean getAutostart() { return BootReceiver.enabled(MainActivity.this); }

        @JavascriptInterface
        public void setAutostart(boolean on) { BootReceiver.setEnabled(MainActivity.this, on); }

        @JavascriptInterface
        public boolean canDrawOverlays() { return MainActivity.this.canDrawOverlays(); }

        @JavascriptInterface
        public void requestOverlay() {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                if (!startSafe(i)) startSafe(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        }

        @JavascriptInterface
        public void openHomeSettings() {
            if (Build.VERSION.SDK_INT >= 21 && startSafe(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
            startSafe(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public void openMusicAccess() {
            if (!startSafe(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) {
                startSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        }

        @JavascriptInterface
        public boolean hasMusicAccess() {
            return MainActivity.this.hasMusicAccess();
        }

        @JavascriptInterface
        public void media(final String cmd) {
            ui.post(new Runnable() { @Override public void run() { mediaCommand(cmd); } });
        }

        @JavascriptInterface
        public void pickCarPhoto() {
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(Intent.createChooser(i, "Фото машины"), REQ_CAR_PHOTO); }
                    catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getCarPhoto() {
            File f = carFile();
            if (!f.exists()) return "";
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            return b == null ? "" : toDataUri(b, 1200, false);
        }

        @JavascriptInterface
        public void clearCarPhoto() {
            //noinspection ResultOfMethodCallIgnored
            carFile().delete();
        }

        @JavascriptInterface
        public void requestLocation() {
            ui.post(new Runnable() { @Override public void run() { startLocation(); } });
        }

        @JavascriptInterface
        public void fetchWeather(double lat, double lon) {
            MainActivity.this.fetchWeather(lat, lon);
        }

        @JavascriptInterface
        public void localScan() {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (hasAudioPermission()) PlayerService.scan(MainActivity.this);
                    else if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{audioPermission()}, REQ_AUDIO);
                }
            });
        }

        @JavascriptInterface
        public void pickBgPhoto() {
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(Intent.createChooser(i, "Фото фона"), REQ_BG_PHOTO); }
                    catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getBgPhoto() {
            if (bgCache != null) return bgCache;
            File f = bgFile();
            if (!f.exists()) return "";
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                in.close();
                bgCache = "data:image/jpeg;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
                return bgCache;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean navigate(double lat, double lon, String pkg) {
            String ll = String.format(Locale.US, "%.6f,%.6f", lat, lon);
            Intent i = null;
            if ("ru.yandex.yandexnavi".equals(pkg)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                        "yandexnavi://build_route_on_map?lat_to=%.6f&lon_to=%.6f", lat, lon)));
                i.setPackage(pkg);
            } else if ("ru.yandex.yandexmaps".equals(pkg)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                        "yandexmaps://maps.yandex.ru/?rtext=~%.6f,%.6f&rtt=auto", lat, lon)));
                i.setPackage(pkg);
            } else if ("ru.dublgis.dgismobile".equals(pkg)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                        "dgis://2gis.ru/routeSearch/rsType/car/to/%.6f,%.6f", lon, lat)));
                i.setPackage(pkg);
            } else if ("com.google.android.apps.maps".equals(pkg)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + ll));
                i.setPackage(pkg);
            } else if ("com.waze".equals(pkg)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=" + ll + "&navigate=yes"));
                i.setPackage(pkg);
            }
            if (i != null && startSafe(i)) return true;
            Intent g = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll));
            if (pkg != null && pkg.length() > 0) {
                g.setPackage(pkg);
                if (startSafe(g)) return true;
                g.setPackage(null);
            }
            return startSafe(g);
        }

        @JavascriptInterface
        public String localList() { return PlayerService.listJson(); }

        @JavascriptInterface
        public String localState() {
            String st = PlayerService.stateJson();
            if (PlayerService.lastArt.length() > 0) js("window.onLocalArt&&onLocalArt(" + q(PlayerService.lastArt) + ")");
            return st;
        }

        @JavascriptInterface
        public void localPlay(int i) { playerIntent(PlayerService.ACTION_PLAY_INDEX, "i", i); }

        @JavascriptInterface
        public void localCmd(String cmd) { playerIntent(PlayerService.ACTION_CMD, "cmd", cmd); }

        @JavascriptInterface
        public void localSeek(int ms) { playerIntent(PlayerService.ACTION_SEEK, "ms", ms); }
    }

    private boolean startSafe(final Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
