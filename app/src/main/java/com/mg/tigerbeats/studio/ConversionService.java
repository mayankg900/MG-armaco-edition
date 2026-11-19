package com.mg.tigerbeats.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device yt-dlp download and FFmpeg-backed post-processing service. */
public class ConversionService extends Service {
    public static final String ACTION_START = "com.mg.tigerbeats.studio.ACTION_START_CONVERSION";
    public static final String ACTION_CANCEL = "com.mg.tigerbeats.studio.ACTION_CANCEL_CONVERSION";
    public static final String ACTION_BROADCAST_UPDATE = "com.mg.tigerbeats.studio.CONVERSION_UPDATE";
    public static final String PREFS_NAME = "conversion_service_prefs";
    private static final String CHANNEL_ID = "conversion_channel";
    private static final int NOTIFICATION_ID = 9403;
    private static volatile boolean active;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile String processId;
    private volatile File workDir;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "TigerBeats Conversion", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) { cancelCurrent(); return START_NOT_STICKY; }
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) { update("A download is already running.", 0, "error", null, null); return START_NOT_STICKY; }
        running = true; active = true; cancelled.set(false);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) { wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TigerBeats:Conversion"); wakeLock.acquire(TimeUnit.HOURS.toMillis(5)); }
        startForeground(NOTIFICATION_ID, notification("Preparing download…"));
        String input = intent.getStringExtra("input");
        String source = intent.getStringExtra("source");
        String mode = intent.getStringExtra("mode");
        int bitrate = intent.getIntExtra("bitrate", 192);
        int quality = intent.getIntExtra("quality", 0);
        String outputName = intent.getStringExtra("outName");
        executor.execute(() -> process(input, source, mode, bitrate, quality, outputName));
        return START_NOT_STICKY;
    }

    private void process(String url, String source, String mode, int bitrate, int quality, String outputName) {
        try {
            String format = mode == null ? "mp3" : mode.replace("yt-", "");
            if (!("mp3".equals(format) || "mp4".equals(format))) throw new IllegalArgumentException("Choose MP3 or MP4 output.");
            if (source == null) validateMediaUrl(url);
            workDir = new File(getCacheDir(), "tigerbeats-" + System.currentTimeMillis());
            if (!workDir.mkdirs()) throw new IllegalStateException("Temporary storage is unavailable. Free up device space and try again.");
            initializeEngines();

            String title;
            File inputFile;
            if (source != null) {
                Uri uri = Uri.parse(source);
                title = displayName(uri);
                File local = new File(workDir, "input" + extension(title));
                copyInput(uri, local);
                inputFile = local;
            } else {
                title = titleFromUrl(url);
                inputFile = download(url, format, bitrate, quality);
            }
            checkCancelled();
            if (source != null) inputFile = postProcessLocal(inputFile, format, bitrate);
            if (inputFile == null || !inputFile.isFile() || inputFile.length() == 0) throw new IllegalStateException("yt-dlp did not create the requested media file.");

            String requested = outputName == null || outputName.trim().isEmpty() ? title : outputName.trim();
            String name = safeName(stripExtension(requested)) + "." + format;
            update("Saving to Downloads", 95, "running", null, null);
            Uri saved = saveToDownloads(inputFile, name, "mp3".equals(format) ? "audio/mpeg" : "video/mp4");
            if (saved == null) throw new IllegalStateException("Android could not save the file to Downloads.");
            remember(saved, name, format);
            update("Completed · Downloads/TigerBeatsStudio/" + name, 100, "complete", saved.toString(), name);
        } catch (CancelledException e) {
            update("Download cancelled.", 0, "cancelled", null, null);
        } catch (Exception e) {
            Log.e("TigerBeats", "On-device download failed", e);
            update(friendlyError(e), 0, "error", null, null);
        } finally {
            deleteTree(workDir); workDir = null; processId = null; running = false; active = false;
            releaseWakeLock(); stopForeground(true); stopSelf();
        }
    }

    private void initializeEngines() throws Exception {
        try {
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
        } catch (YoutubeDLException e) { throw new IllegalStateException("The on-device download engine could not initialize.", e); }
    }

    private File download(String url, String mode, int bitrate, int quality) throws Exception {
        update("Finding media streams", 2, "running", null, null);
        YoutubeDLRequest request = makeRequest(url, mode, bitrate, quality);
        try {
            execute(request, "Downloading media", 2, 88);
        } catch (Exception first) {
            if (cancelled.get()) throw new CancelledException();
            String detail = lowerMessage(first);
            if (detail.contains("page needs to be reloaded") || detail.contains("precondition check failed") || detail.contains("http error 403")) {
                update("Updating download engine", 2, "running", null, null);
                YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                execute(makeRequest(url, mode, bitrate, quality), "Retrying download", 2, 88);
            } else throw first;
        }
        checkCancelled();
        File output = findOutput(mode);
        if (output == null) throw new IllegalStateException("The site did not provide a compatible " + mode.toUpperCase(Locale.ROOT) + " stream. Try another quality or video.");
        return output;
    }

    private YoutubeDLRequest makeRequest(String url, String mode, int bitrate, int quality) {
        File template = new File(workDir, "source.%(ext)s");
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-warnings");
        request.addOption("--newline");
        request.addOption("--no-mtime");
        request.addOption("--no-playlist");
        request.addOption("--restrict-filenames");
        request.addOption("-o", template.getAbsolutePath());
        if ("mp3".equals(mode)) {
            request.addOption("-f", "bestaudio/best");
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", Math.max(64, Math.min(320, bitrate)) + "K");
        } else {
            String selector = quality > 0
                    ? "bestvideo[height<=" + quality + "][ext=mp4]+bestaudio[ext=m4a]/best[height<=" + quality + "][ext=mp4]/best[height<=" + quality + "]/best"
                    : "bestvideo+bestaudio/best";
            request.addOption("-f", selector);
            request.addOption("--merge-output-format", "mp4");
            request.addOption("--remux-video", "mp4");
        }
        return request;
    }

    private File postProcessLocal(File input, String mode, int bitrate) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(input.getAbsolutePath());
        request.addOption("--no-warnings"); request.addOption("--no-playlist"); request.addOption("--force-overwrites");
        request.addOption("-o", new File(workDir, "source.%(ext)s").getAbsolutePath());
        if ("mp3".equals(mode)) {
            request.addOption("-x"); request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", Math.max(64, Math.min(320, bitrate)) + "K");
        } else request.addOption("--remux-video", "mp4");
        execute(request, "Converting selected file", 70, 20);
        File result = findOutput(mode);
        if (result == null) throw new IllegalStateException("Could not convert the selected file to " + mode.toUpperCase(Locale.ROOT) + ".");
        return result;
    }

    private void execute(YoutubeDLRequest request, String label, int base, int span) throws Exception {
        processId = "tigerbeats-" + System.currentTimeMillis();
        YoutubeDL.getInstance().execute(request, processId, (progress, eta, line) -> {
            if (!cancelled.get()) {
                int p = progress == null ? base : base + Math.round(progress.floatValue() * span / 100f);
                update(label, Math.max(base, Math.min(base + span, p)), "running", null, null);
            }
            return kotlin.Unit.INSTANCE;
        });
        processId = null;
    }

    private File findOutput(String mode) {
        File[] files = workDir == null ? null : workDir.listFiles((dir, name) -> name.startsWith("source.") && !name.endsWith(".part"));
        if (files == null) return null;
        String wanted = "." + mode;
        for (File file : files) if (file.isFile() && file.length() > 0 && file.getName().toLowerCase(Locale.ROOT).endsWith(wanted)) return file;
        return null;
    }

    private void validateMediaUrl(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Paste a video link.");
        Uri uri = Uri.parse(value.trim());
        String scheme = uri.getScheme(), host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null || host.trim().isEmpty() || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Use a complete HTTPS link to a supported public video.");
    }

    private Uri saveToDownloads(File file, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TigerBeatsStudio");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            boolean done = false;
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("Could not open the Downloads file.");
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
                out.flush(); ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null); done = true; return uri;
            } finally { if (!done) getContentResolver().delete(uri, null, null); }
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TigerBeatsStudio");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create the Downloads folder.");
        File dest = new File(dir, name);
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
        }
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DATA, dest.getAbsolutePath());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name); values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri collection = mime.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return getContentResolver().insert(collection, values);
    }

    private void copyInput(Uri uri, File destination) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IllegalStateException("Android could not read the selected file. Choose it again.");
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
        }
    }

    private String displayName(Uri uri) {
        String name = uri.getLastPathSegment(); return name == null ? "Local_media" : name;
    }
    private static String titleFromUrl(String value) {
        Uri uri = Uri.parse(value); String id = uri.getQueryParameter("v");
        if (id == null || id.isEmpty()) id = uri.getLastPathSegment();
        return id == null || id.isEmpty() ? "Downloaded_media" : id;
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot > 0 && name.length() - dot < 10 ? name.substring(dot).replaceAll("[^.A-Za-z0-9]", "") : ".media";
    }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static String safeName(String name) {
        String safe = (name == null ? "TigerBeats_Export" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty()) safe = "TigerBeats_Export"; return safe.substring(0, Math.min(100, safe.length()));
    }
    private static String lowerMessage(Exception e) { return e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT); }
    private String friendlyError(Exception e) {
        String message = e.getMessage(); if (message == null || message.trim().isEmpty()) return "Download failed. Check the link and try again.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no space") || lower.contains("enospc")) return "Not enough free storage. Clear space and try again.";
        if (lower.contains("private video") || lower.contains("sign in") || lower.contains("members-only") || lower.contains("login required"))
            return "This video could not be accessed. Check the link or try a publicly available video.";
        return message;
    }
    private void checkCancelled() throws CancelledException { if (cancelled.get()) throw new CancelledException(); }
    private void remember(Uri uri, String name, String mode) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); JSONArray old;
        try { old = new JSONArray(prefs.getString("conversion_history", "[]")); } catch (Exception e) { old = new JSONArray(); }
        JSONArray rows = new JSONArray(); JSONObject item = new JSONObject();
        try { item.put("uri", uri.toString()); item.put("name", name); item.put("mode", mode); item.put("date", System.currentTimeMillis()); rows.put(item);
            for (int i = 0; i < old.length() && i < 39; i++) rows.put(old.getJSONObject(i));
            prefs.edit().putString("conversion_history", rows.toString()).apply();
        } catch (Exception ignored) { }
    }
    private void update(String message, int value, String state, String uri, String name) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("message", message == null ? "" : message)
                .putInt("progress", value).putString("state", state == null ? "" : state)
                .putString("uri", "complete".equals(state) && uri != null ? uri : "")
                .putString("name", "complete".equals(state) && name != null ? name : "").apply();
        Intent event = new Intent(ACTION_BROADCAST_UPDATE).setPackage(getPackageName()).putExtra("message", message).putExtra("progress", value).putExtra("state", state);
        if (uri != null) event.putExtra("uri", uri); if (name != null) event.putExtra("name", name); sendBroadcast(event);
        if ("running".equals(state)) { NotificationManager manager = getSystemService(NotificationManager.class); if (manager != null) manager.notify(NOTIFICATION_ID, notification(message)); }
    }
    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("TigerBeats Studio Downloader")
                .setContentText(text).setSmallIcon(android.R.drawable.ic_menu_save).build();
    }
    static boolean isConversionRunning() { return active; }
    private void cancelCurrent() {
        cancelled.set(true); String id = processId;
        if (id != null) try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Exception ignored) { }
    }
    private void releaseWakeLock() { try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { } wakeLock = null; }
    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return; File[] files = dir.listFiles();
        if (files != null) for (File file : files) { if (file.isDirectory()) deleteTree(file); else file.delete(); }
        dir.delete();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (!running) active = false; releaseWakeLock(); executor.shutdownNow(); super.onDestroy(); }
    private static final class CancelledException extends Exception { }
}
