package kz.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Built-in local music player: plays audio files from device storage / USB sticks. */
public class PlayerService extends Service implements
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener,
        AudioManager.OnAudioFocusChangeListener {

    public interface Listener {
        void onState(String json);
        void onArt(String dataUri);
        void onList(String json);
    }

    public static class Track {
        String title, artist, path;
        Uri uri;
        long dur;
    }

    public static final String ACTION_PLAY_INDEX = "kz.carlauncher.PLAY_INDEX";
    public static final String ACTION_CMD = "kz.carlauncher.CMD";
    public static final String ACTION_SEEK = "kz.carlauncher.SEEK";

    static final List<Track> tracks = Collections.synchronizedList(new ArrayList<Track>());
    static volatile Listener listener;
    static volatile PlayerService inst;
    static volatile boolean scanning = false;

    // shared state (readable even when service is not running)
    static int index = -1;
    static boolean shuffle = false;
    static int repeat = 0; // 0 = list, 1 = repeat all, 2 = repeat one
    static String lastArt = "";

    private MediaPlayer mp;
    private boolean prepared = false;
    private boolean playWhenReady = false;
    private boolean pausedByFocus = false;
    private int pendingSeek = -1;
    private MediaSession session;
    private AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final List<Integer> history = new ArrayList<>();

    // ------------------------------------------------------------ scanning

    static void scan(final Context ctx) {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Track> found = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();
                try {
                    Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.DATA};
                    Cursor c = ctx.getContentResolver().query(base, proj,
                            MediaStore.Audio.Media.DURATION + " > 20000", null, null);
                    if (c != null) {
                        while (c.moveToNext()) {
                            Track t = new Track();
                            t.uri = ContentUris.withAppendedId(base, c.getLong(0));
                            t.title = c.getString(1);
                            t.artist = c.getString(2);
                            t.dur = c.getLong(3);
                            t.path = c.getString(4);
                            if ("<unknown>".equals(t.artist)) t.artist = "";
                            if (t.path != null) seen.add(t.path);
                            found.add(t);
                        }
                        c.close();
                    }
                } catch (Exception ignored) {}

                // Fallback / extra: walk storage roots (USB sticks are often not indexed on head units)
                for (File root : roots()) walk(root, 0, found, seen);

                Collections.sort(found, new Comparator<Track>() {
                    @Override public int compare(Track a, Track b) {
                        String x = (a.artist == null ? "" : a.artist) + " " + a.title;
                        String y = (b.artist == null ? "" : b.artist) + " " + b.title;
                        return x.compareToIgnoreCase(y);
                    }
                });
                synchronized (tracks) {
                    String curPath = (index >= 0 && index < tracks.size()) ? tracks.get(index).path : null;
                    tracks.clear();
                    tracks.addAll(found);
                    index = -1;
                    if (curPath != null) {
                        for (int i = 0; i < tracks.size(); i++) if (curPath.equals(tracks.get(i).path)) { index = i; break; }
                    }
                }
                if (index < 0) restoreIndex(ctx);
                scanning = false;
                Listener l = listener;
                if (l != null) { l.onList(listJson()); l.onState(stateJson()); }
            }
        }).start();
    }

    private static List<File> roots() {
        List<File> r = new ArrayList<>();
        HashSet<String> s = new HashSet<>();
        String[] cands = {"/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/udisk",
                "/mnt/USB", "/mnt/sdcard", "/udisk", "/usb"};
        try { addRoot(r, s, Environment.getExternalStorageDirectory()); } catch (Exception ignored) {}
        for (String c : cands) {
            File f = new File(c);
            File[] kids = f.listFiles();
            if (kids == null) { addRoot(r, s, f); continue; }
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("self") || n.equals("emulated") || n.equals("enc_emulated")) continue;
                addRoot(r, s, k);
            }
        }
        return r;
    }

    private static void addRoot(List<File> r, HashSet<String> s, File f) {
        try {
            if (f == null || !f.isDirectory() || !f.canRead()) return;
            String p = f.getCanonicalPath();
            if (s.add(p)) r.add(f);
        } catch (Exception ignored) {}
    }

    private static final String[] EXT = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".wma", ".opus", ".ape"};

    private static void walk(File dir, int depth, List<Track> out, HashSet<String> seen) {
        if (depth > 7 || out.size() > 20000) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (n.equals("Android") || n.equals("DCIM") || n.equals("LOST.DIR"))) continue;
                walk(f, depth + 1, out, seen);
                continue;
            }
            String low = n.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String e : EXT) if (low.endsWith(e)) { ok = true; break; }
            if (!ok || f.length() < 200_000) continue;
            String path = f.getAbsolutePath();
            if (!seen.add(path)) continue;
            Track t = new Track();
            t.path = path;
            t.uri = Uri.fromFile(f);
            String base = n.substring(0, n.lastIndexOf('.'));
            int dash = base.indexOf(" - ");
            if (dash > 0) { t.artist = base.substring(0, dash).trim(); t.title = base.substring(dash + 3).trim(); }
            else { t.artist = ""; t.title = base; }
            t.dur = 0;
            out.add(t);
        }
    }

    static String listJson() {
        JSONArray a = new JSONArray();
        synchronized (tracks) {
            for (Track t : tracks) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", t.title == null ? "" : t.title);
                    o.put("a", t.artist == null ? "" : t.artist);
                    o.put("d", t.dur);
                    a.put(o);
                } catch (Exception ignored) {}
            }
        }
        return a.toString();
    }

    static String stateJson() {
        JSONObject o = new JSONObject();
        try {
            PlayerService s = inst;
            Track t = current();
            o.put("i", index);
            o.put("t", t == null ? "" : t.title);
            o.put("a", t == null ? "" : (t.artist == null ? "" : t.artist));
            o.put("count", tracks.size());
            o.put("shuffle", shuffle);
            o.put("repeat", repeat);
            boolean playing = false;
            long pos = 0, dur = t == null ? 0 : t.dur;
            if (s != null && s.mp != null && s.prepared) {
                try {
                    playing = s.mp.isPlaying();
                    pos = s.mp.getCurrentPosition();
                    long d = s.mp.getDuration();
                    if (d > 0) dur = d;
                } catch (Exception ignored) {}
            } else if (s == null) {
                pos = savedPos;
            }
            o.put("playing", playing);
            o.put("pos", pos);
            o.put("dur", dur);
        } catch (Exception ignored) {}
        return o.toString();
    }

    static long savedPos = 0;

    static Track current() {
        synchronized (tracks) {
            return (index >= 0 && index < tracks.size()) ? tracks.get(index) : null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("player", MODE_PRIVATE);
    }

    private static void restoreIndex(Context c) {
        SharedPreferences p = prefs(c);
        String path = p.getString("path", null);
        shuffle = p.getBoolean("shuffle", false);
        repeat = p.getInt("repeat", 0);
        savedPos = p.getLong("pos", 0);
        if (path == null) return;
        synchronized (tracks) {
            for (int i = 0; i < tracks.size(); i++) if (path.equals(tracks.get(i).path)) { index = i; return; }
        }
        savedPos = 0;
    }

    private void persist() {
        Track t = current();
        SharedPreferences.Editor e = prefs(this).edit();
        if (t != null) e.putString("path", t.path);
        long pos = 0;
        try { if (mp != null && prepared) pos = mp.getCurrentPosition(); } catch (Exception ignored) {}
        savedPos = pos;
        e.putLong("pos", pos).putBoolean("shuffle", shuffle).putInt("repeat", repeat).apply();
    }

    // ------------------------------------------------------------ service

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "AccentLauncherPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        h.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_INDEX: playIndex(intent.getIntExtra("i", 0), 0); break;
                case ACTION_SEEK: seek(intent.getIntExtra("ms", 0)); break;
                case ACTION_CMD: command(intent.getStringExtra("cmd")); break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        persist();
        h.removeCallbacksAndMessages(null);
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
        if (session != null) session.release();
        try { am.abandonAudioFocus(this); } catch (Exception ignored) {}
        inst = null;
        notifyState();
        super.onDestroy();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean playing = false;
            try { playing = mp != null && prepared && mp.isPlaying(); } catch (Exception ignored) {}
            if (playing) {
                notifyState();
                updateSessionState();
            }
            h.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------ controls

    private void command(String c) {
        if (c == null) return;
        switch (c) {
            case "toggle":
                boolean playing = false;
                try { playing = mp != null && prepared && mp.isPlaying(); } catch (Exception ignored) {}
                if (playing) pause(); else play();
                break;
            case "play": play(); break;
            case "pause": pause(); break;
            case "next": next(true); break;
            case "prev": prev(); break;
            case "shuffle": shuffle = !shuffle; persist(); notifyState(); break;
            case "repeat": repeat = (repeat + 1) % 3; persist(); notifyState(); break;
        }
    }

    private void play() {
        if (tracks.isEmpty()) return;
        if (mp != null && prepared) {
            if (!requestFocus()) return;
            mp.start();
            goForeground();
            notifyState();
            updateSessionState();
            return;
        }
        if (index < 0) index = shuffle ? rnd.nextInt(tracks.size()) : 0;
        playIndex(index, (int) savedPos);
    }

    private void pause() {
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        playWhenReady = false;
        persist();
        stopFg();
        notifyState();
        updateSessionState();
    }

    private void seek(int ms) {
        if (mp != null && prepared) { try { mp.seekTo(ms); } catch (Exception ignored) {} }
        else pendingSeek = ms;
        notifyState();
    }

    private void next(boolean user) {
        int n = tracks.size();
        if (n == 0) return;
        if (!user && repeat == 2) { playIndex(index, 0); return; }
        int ni;
        if (shuffle) {
            ni = n == 1 ? 0 : rnd.nextInt(n);
            if (ni == index && n > 1) ni = (ni + 1) % n;
        } else {
            ni = index + 1;
            if (ni >= n) {
                if (!user && repeat == 0) { pause(); seek(0); return; }
                ni = 0;
            }
        }
        if (index >= 0) { history.add(index); if (history.size() > 200) history.remove(0); }
        playIndex(ni, 0);
    }

    private void prev() {
        int n = tracks.size();
        if (n == 0) return;
        try {
            if (mp != null && prepared && mp.getCurrentPosition() > 4000) { mp.seekTo(0); notifyState(); return; }
        } catch (Exception ignored) {}
        int pi;
        if (shuffle && !history.isEmpty()) pi = history.remove(history.size() - 1);
        else pi = index - 1 < 0 ? n - 1 : index - 1;
        playIndex(pi, 0);
    }

    private void playIndex(int i, int startMs) {
        Track t;
        synchronized (tracks) {
            if (tracks.isEmpty()) return;
            if (i < 0 || i >= tracks.size()) i = 0;
            index = i;
            t = tracks.get(i);
        }
        if (!requestFocus()) return;
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} }
        prepared = false;
        playWhenReady = true;
        pendingSeek = startMs > 0 ? startMs : -1;
        mp = new MediaPlayer();
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            }
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(this, t.uri);
            mp.prepareAsync();
        } catch (Exception e) {
            h.postDelayed(new Runnable() { @Override public void run() { next(false); } }, 500);
            return;
        }
        persist();
        notifyState();
        loadArt(t);
        goForeground();
    }

    @Override
    public void onPrepared(MediaPlayer m) {
        prepared = true;
        Track t = current();
        if (t != null && t.dur <= 0) { try { t.dur = m.getDuration(); } catch (Exception ignored) {} }
        if (pendingSeek > 0) { try { m.seekTo(pendingSeek); } catch (Exception ignored) {} }
        pendingSeek = -1;
        if (playWhenReady) m.start();
        updateSessionMeta(null);
        updateSessionState();
        notifyState();
    }

    @Override
    public void onCompletion(MediaPlayer m) { next(false); }

    @Override
    public boolean onError(MediaPlayer m, int what, int extra) {
        prepared = false;
        h.postDelayed(new Runnable() { @Override public void run() { next(false); } }, 700);
        return true;
    }

    // ------------------------------------------------------------ audio focus

    private boolean requestFocus() {
        try {
            int r = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    pausedByFocus = false;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (mp != null && prepared && mp.isPlaying()) { pausedByFocus = true; mp.pause(); notifyState(); }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mp != null && prepared) mp.setVolume(0.3f, 0.3f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mp != null && prepared) {
                        mp.setVolume(1f, 1f);
                        if (pausedByFocus) { mp.start(); pausedByFocus = false; notifyState(); }
                    }
                    break;
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ art / session / notification

    private void loadArt(final Track t) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(PlayerService.this, t.uri);
                    byte[] pic = r.getEmbeddedPicture();
                    if (pic != null) b = BitmapFactory.decodeByteArray(pic, 0, pic.length);
                    if (t.dur <= 0) {
                        String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (d != null) t.dur = Long.parseLong(d);
                    }
                    String ti = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String ar = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (ti != null && ti.trim().length() > 0 && t.uri.getScheme() != null && t.uri.getScheme().equals("file")) t.title = ti.trim();
                    if (ar != null && ar.trim().length() > 0 && (t.artist == null || t.artist.isEmpty())) t.artist = ar.trim();
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
                if (b == null) {
                    // cover.jpg / folder.jpg next to the file
                    try {
                        File dir = new File(t.path).getParentFile();
                        for (String n : new String[]{"cover.jpg", "folder.jpg", "Cover.jpg", "Folder.jpg", "cover.png", "AlbumArt.jpg"}) {
                            File f = new File(dir, n);
                            if (f.exists()) {
                                BitmapFactory.Options o = new BitmapFactory.Options();
                                o.inSampleSize = 2;
                                b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                                if (b != null) break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                final Bitmap fb = b;
                final String uri = b == null ? "" : MainActivity.toDataUri(b, 300, true);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (current() != t) return;
                        lastArt = uri;
                        Listener l = listener;
                        if (l != null) l.onArt(uri);
                        updateSessionMeta(fb);
                        notifyState();
                        goForeground();
                    }
                });
            }
        }).start();
    }

    private Bitmap sessionArt;

    private void updateSessionMeta(Bitmap art) {
        if (art != null) sessionArt = art;
        Track t = current();
        if (t == null || session == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist == null ? "" : t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.dur);
        if (sessionArt != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, sessionArt);
        try { session.setMetadata(b.build()); } catch (Exception ignored) {}
    }

    private void updateSessionState() {
        if (session == null) return;
        boolean playing = false;
        long pos = 0;
        try { if (mp != null && prepared) { playing = mp.isPlaying(); pos = mp.getCurrentPosition(); } } catch (Exception ignored) {}
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                .build());
    }

    private static final String CH = "player";

    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CH) == null) {
                    NotificationChannel ch = new NotificationChannel(CH, "Музыка", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
                nb = new Notification.Builder(this, CH);
            } else {
                nb = new Notification.Builder(this);
            }
            Track t = current();
            Intent open = new Intent(this, MainActivity.class);
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            nb.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(t == null ? "Музыка" : t.title)
                    .setContentText(t == null ? "" : (t.artist == null ? "" : t.artist))
                    .setContentIntent(PendingIntent.getActivity(this, 0, open, fl))
                    .setOngoing(true)
                    .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
            if (sessionArt != null) nb.setLargeIcon(sessionArt);
            Notification n = nb.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(7, n);
            }
        } catch (Exception ignored) {}
    }

    private void stopFg() {
        try { stopForeground(false); } catch (Exception ignored) {}
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) l.onState(stateJson());
    }
}
