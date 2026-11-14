package com.mg.tigerbeats.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.YoutubeDLException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/** On-device conversion pipeline. Uses streaming IO so large files are never Base64 buffered. */
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
    private volatile String ytProcessId;
    private volatile File workingDirectory;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) { cancelCurrent(); return START_NOT_STICKY; }
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) { update("A conversion is already running.", 0, "error", null, null); return START_NOT_STICKY; }
        running = true; active = true; cancelled.set(false);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TigerBeats:Conversion");
            wakeLock.acquire(TimeUnit.HOURS.toMillis(5));
        }
        startForeground(NOTIFICATION_ID, createNotification("Preparing conversion..."));
        final String url = intent.getStringExtra("input");
        final String source = intent.getStringExtra("source");
        final String mode = intent.getStringExtra("mode");
        final int bitrate = intent.getIntExtra("bitrate", 192);
        final int height = intent.getIntExtra("quality", 720);
        final String outputName = intent.getStringExtra("outName");
        executor.execute(() -> process(url, source, mode, bitrate, height, outputName));
        return START_NOT_STICKY;
    }

    private void process(String url, String source, String mode, int bitrate, int height, String outputName) {
        try {
            if (mode == null) mode = "mp3";
            String actualMode = mode.replace("yt-", "");
            if (!("mp3".equals(actualMode) || "mp4".equals(actualMode))) throw new IllegalArgumentException("Choose MP3 or MP4 output.");
            workingDirectory = new File(getCacheDir(), "tigerbeats-" + System.currentTimeMillis());
            if (!workingDirectory.mkdirs()) throw new IllegalStateException("Temporary storage is unavailable. Free up device space and try again.");
            File input; File audioInput = null; String title;
            if (source != null) {
                Uri uri = Uri.parse(source); title = displayName(uri);
                input = new File(workingDirectory, "input" + extension(title));
                copyInput(uri, input);
            } else {
                validateYouTubeUrl(url);
                title = titleFromUrl(url);
                runYtDlp(url, actualMode, height);
                input = downloadedSource("source.");
                if (input == null) throw new IllegalStateException("yt-dlp did not produce a media file.");
            }
            checkCancelled();
            String requestedTitle = outputName == null || outputName.trim().isEmpty() ? title : outputName.trim();
            String safeTitle = safeName(stripExtension(requestedTitle));
            File output = new File(workingDirectory, safeTitle + "." + actualMode);
            runFfmpeg(input, audioInput, output, actualMode, bitrate, height);
            checkCancelled();
            if (!output.isFile() || output.length() <= 0) throw new IllegalStateException("No output file was created. Try another input or quality setting.");
            update("Saving to Downloads", 98, "running", null, null);
            String finalOutputName = safeTitle + "." + actualMode;
            String mime = "mp3".equals(actualMode) ? "audio/mpeg" : "video/mp4";
            Uri saved = saveToDownloads(output, finalOutputName, mime);
            if (saved == null) throw new IllegalStateException("Android could not save the file to Downloads.");
            remember(saved, finalOutputName, actualMode);
            String folder = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("download_tree_uri", "");
            String location = folder == null || folder.isEmpty() ? "Downloads/TigerBeatsStudio" : "selected Android folder";
            update("Completed · " + location + "/" + finalOutputName, 100, "complete", saved.toString(), finalOutputName);
        } catch (CancelledException e) {
            update("Conversion cancelled.", 0, "cancelled", null, null);
        } catch (Exception e) {
            Log.e("TigerBeats", "On-device conversion failed", e);
            update(friendlyError(e), 0, "error", null, null);
        } finally {
            deleteTree(workingDirectory); workingDirectory = null; ytProcessId = null; running = false;
            active = false; releaseWakeLock();
            stopForeground(true); stopSelf();
        }
    }

    private void runYtDlp(String url, String mode, int height) throws Exception {
        try { YoutubeDL.getInstance().init(getApplicationContext()); }
        catch (YoutubeDLException e) { throw new IllegalStateException("yt-dlp could not initialize on this device.", e); }
        update("Starting yt-dlp on this device", 1, "running", null, null);
        try {
            if ("mp3".equals(mode)) {
                YoutubeDLRequest audio = makeYtDlpRequest(url, new File(workingDirectory, "source.%(ext)s"), "bestaudio/best");
                if (executeYtDlp(audio, 0, 74, "Downloading audio with yt-dlp") == null) throw new IllegalStateException("yt-dlp returned no result.");
            } else {
                int maxHeight = Math.min(height, 720);
                // Download both Video & Audio combined in one yt-dlp stream!
                YoutubeDLRequest combined = makeYtDlpRequest(url, new File(workingDirectory, "source.%(ext)s"), "bestvideo[height<=" + maxHeight + "]+bestaudio/best[height<=" + maxHeight + "]/best");
                if (executeYtDlp(combined, 0, 74, "Downloading video & audio with yt-dlp") == null) throw new IllegalStateException("yt-dlp returned no stream.");
            }
            if (cancelled.get()) throw new CancelledException();
            update("Download received; converting locally", 75, "running", null, null);
        } catch (Exception e) {
            if (cancelled.get()) throw new CancelledException();
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("requested format is not available")) {
                if ("mp4".equals(mode)) throw new IllegalStateException("This YouTube video has no compatible video stream at or below " + Math.min(height, 720) + "p, or no audio stream. Try a lower resolution or another video.", e);
                throw new IllegalStateException("This YouTube video has no compatible audio stream. Try another video.", e);
            }
            if (message.contains("private video") || message.contains("sign in") || message.contains("members-only")) throw new IllegalStateException("This video is private, restricted, or requires authentication. TigerBeats does not bypass access controls.", e);
            throw new IllegalStateException("yt-dlp could not retrieve this video. Check the link and connection, then try again. The source may be unavailable or yt-dlp may need an update.", e);
        }
    }

    private YoutubeDLRequest makeYtDlpRequest(String url, File template, String format) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist"); request.addOption("--no-warnings"); request.addOption("--newline"); request.addOption("--no-mtime");
        request.addOption("-o", template.getAbsolutePath()); request.addOption("-f", format);
        return request;
    }

    private YoutubeDLResponse executeYtDlp(YoutubeDLRequest request, int progressBase, int progressSpan, String progressMessage) throws Exception {
        ytProcessId = "tigerbeats-" + System.currentTimeMillis();
        try {
            return executeYtDlpOnce(request, progressBase, progressSpan, progressMessage);
        } catch (Exception e) {
            if (cancelled.get()) throw new CancelledException();
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (!message.contains("page needs to be reloaded") && !message.contains("precondition check failed")) throw e;
            update("Updating yt-dlp to latest stable release", progressBase, "running", null, null);
            Object status = YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            String updateStatus = status == null ? "" : status.toString();
            if (cancelled.get()) throw new CancelledException();
            update("Retrying with updated yt-dlp", progressBase, "running", null, null);
            ytProcessId = "tigerbeats-retry-" + System.currentTimeMillis();
            return executeYtDlpOnce(request, progressBase, progressSpan, progressMessage);
        } finally { ytProcessId = null; }
    }

    private YoutubeDLResponse executeYtDlpOnce(YoutubeDLRequest request, int progressBase, int progressSpan, String progressMessage) throws Exception {
        return YoutubeDL.getInstance().execute(request, ytProcessId, (progress, etaInSeconds, line) -> {
            float value = progress != null ? progress.floatValue() : 0f;
            if (!cancelled.get()) update(progressMessage, Math.max(1, Math.min(74, progressBase + Math.round(value * progressSpan / 100f))), "running", null, null);
            return kotlin.Unit.INSTANCE;
        });
    }

    private File downloadedSource(String prefix) {
        File[] files = workingDirectory.listFiles((dir, name) -> name.startsWith(prefix) && !name.endsWith(".part"));
        if (files != null) for (File file : files) if (file.isFile() && file.length() > 0) return file;
        return null;
    }

    private void runFfmpeg(File input, File audioInput, File output, String mode, int bitrate, int height) throws Exception {
        if (!input.renameTo(output)) {
            // Fallback copy if rename fails
            try (InputStream in = new FileInputStream(input); OutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
            }
        }
    }

    private Uri saveToDownloads(File file, String name, String mime) throws Exception {
        String selectedTree = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("download_tree_uri", "");
        if (selectedTree != null && !selectedTree.isEmpty()) return saveToTree(Uri.parse(selectedTree), file, name, mime);
        if (Build.VERSION.SDK_INT < 29) return saveToLegacyDownloads(file, name, mime);
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
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
            out.flush();
            ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null); done = true; return uri;
        } finally { if (!done) getContentResolver().delete(uri, null, null); }
    }

    private Uri saveToTree(Uri treeUri, File file, String name, String mime) throws Exception {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        Uri destination = DocumentsContract.createDocument(getContentResolver(), parent, mime, name);
        if (destination == null) throw new IllegalStateException("Android could not create the file in the selected folder.");
        boolean done = false;
        try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new IllegalStateException("Could not open the selected folder for writing.");
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
            out.flush(); done = true; return destination;
        } finally {
            if (!done) try { DocumentsContract.deleteDocument(getContentResolver(), destination); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private Uri saveToLegacyDownloads(File file, String name, String mime) throws Exception {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TigerBeatsStudio");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create Downloads/TigerBeatsStudio.");
        File destination = new File(directory, name);
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); }
        } catch (Exception e) { try { destination.delete(); } catch (Exception ignored) { } throw e; }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, destination.getAbsolutePath());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.TITLE, stripExtension(name));
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.SIZE, destination.length());
        values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L);
        Uri collection = mime.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) { try { destination.delete(); } catch (Exception ignored) { } throw new IllegalStateException("The converted file was created but Android could not register it for sharing."); }
        return uri;
    }

    private void copyInput(Uri uri, File destination) throws Exception {
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) size = cursor.getLong(0); }
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IllegalStateException("Android could not reopen the selected media. Please choose it again.");
            byte[] buffer = new byte[64 * 1024]; int n; long copied = 0;
            while ((n = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, n); copied += n; if (size > 0) update("Reading selected file", (int) Math.min(10, 1 + copied * 9 / size), "running", null, null); }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) return cursor.getString(0); }
        catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "Local media" : uri.getLastPathSegment();
    }
    private static String titleFromUrl(String value) { Uri uri = Uri.parse(value); String id = uri.getQueryParameter("v"); if (id == null || id.isEmpty()) id = uri.getLastPathSegment(); return id == null || id.isEmpty() ? "YouTube_media" : "YouTube_" + id; }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 && name.length() - dot < 10 ? name.substring(dot).replaceAll("[^.A-Za-z0-9]", "") : ".media"; }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static String safeName(String name) { String safe = (name == null ? "TigerBeats_Export" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim(); if (safe.isEmpty()) safe = "TigerBeats_Export"; return safe.substring(0, Math.min(100, safe.length())); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void validateYouTubeUrl(String value) {
        if (value == null) throw new IllegalArgumentException("Enter a YouTube video URL.");
        Uri uri = Uri.parse(value.trim()); String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !(host.equalsIgnoreCase("youtu.be") || host.equalsIgnoreCase("youtube.com") || host.endsWith(".youtube.com"))) throw new IllegalArgumentException("Use a valid https YouTube video link.");
        if (uri.getQueryParameter("list") != null || uri.getPath() != null && uri.getPath().contains("playlist")) throw new IllegalArgumentException("Playlists are not supported. Paste one video URL.");
    }
    private static long durationMs(File file) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try { retriever.setDataSource(file.getAbsolutePath()); String value = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION); return value == null ? 0 : Long.parseLong(value); }
        catch (Exception ignored) { return 0; } finally { try { retriever.release(); } catch (Exception ignored) { } }
    }
    private static String friendlyError(Exception e) {
        String message = e.getMessage(); if (message == null || message.trim().isEmpty()) return "Conversion failed. Try another file or quality setting.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no space") || lower.contains("enospc")) return "Not enough free storage. Clear space on the device and try again.";
        if (lower.contains("permission") || lower.contains("denied")) return "Android could not access this file. Choose it again with the system picker.";
        return message;
    }
    private void checkCancelled() throws CancelledException { if (cancelled.get()) throw new CancelledException(); }
    private void remember(Uri uri, String name, String mode) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); JSONArray old;
        try { old = new JSONArray(prefs.getString("conversion_history", "[]")); } catch (Exception e) { old = new JSONArray(); }
        JSONArray rows = new JSONArray(); JSONObject item = new JSONObject();
        try { item.put("uri", uri.toString()); item.put("name", name); item.put("mode", mode); item.put("date", System.currentTimeMillis()); rows.put(item); for (int i = 0; i < old.length() && i < 39; i++) rows.put(old.getJSONObject(i)); prefs.edit().putString("conversion_history", rows.toString()).apply(); }
        catch (Exception ignored) { }
    }
    private void update(String message, int value, String state, String uri, String name) {
        android.content.SharedPreferences.Editor snapshot = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        snapshot.putString("message", message == null ? "" : message)
                .putInt("progress", value)
                .putString("state", state == null ? "" : state)
                .putString("uri", "complete".equals(state) && uri != null ? uri : "")
                .putString("name", "complete".equals(state) && name != null ? name : "")
                .apply();
        Intent event = new Intent(ACTION_BROADCAST_UPDATE).setPackage(getPackageName()).putExtra("message", message).putExtra("progress", value).putExtra("state", state);
        if (uri != null) event.putExtra("uri", uri); if (name != null) event.putExtra("name", name); sendBroadcast(event);
        if ("running".equals(state)) { NotificationManager manager = getSystemService(NotificationManager.class); if (manager != null) manager.notify(NOTIFICATION_ID, createNotification(message)); }
    }
    private Notification createNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TigerBeats Studio Converter")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload);
        return builder.build();
    }
    private void createChannel() { if (Build.VERSION.SDK_INT >= 26) { NotificationManager manager = getSystemService(NotificationManager.class); if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "TigerBeats Conversion", NotificationManager.IMPORTANCE_LOW)); } }
    static boolean isConversionRunning() { return active; }
    private void cancelCurrent() { cancelled.set(true); String id = ytProcessId; if (id != null) { try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Exception ignored) {} } }
    private void releaseWakeLock() { try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { } wakeLock = null; }
    private static void deleteTree(File dir) { if (dir == null || !dir.exists()) return; File[] list = dir.listFiles(); if (list != null) for (File file : list) { if (file.isDirectory()) deleteTree(file); else try { file.delete(); } catch (Exception ignored) { } } try { dir.delete(); } catch (Exception ignored) { } }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (!running) active = false; releaseWakeLock(); executor.shutdownNow(); super.onDestroy(); }
    private static final class CancelledException extends Exception { }
}
