package com.mg.tigerbeats.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.util.Rational;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private MediaPlayer devicePlayer;
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();
    private android.webkit.ValueCallback<Uri[]> filePathCallback;
    private static final int REQUEST_FILE_CHOOSER = 102;

    private final BroadcastReceiver conversionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || webView == null) return;
            String msg = intent.getStringExtra("message");
            String state = intent.getStringExtra("state");
            int progress = intent.getIntExtra("progress", 0);
            String uri = intent.getStringExtra("uri");
            String name = intent.getStringExtra("name");
            String js = String.format(
                    "if(window.onNativeConversionUpdate){window.onNativeConversionUpdate(%s,%s,%d,%s,%s);}",
                    JSONObject.quote(msg != null ? msg : ""),
                    JSONObject.quote(state != null ? state : "running"),
                    progress,
                    JSONObject.quote(uri != null ? uri : ""),
                    JSONObject.quote(name != null ? name : "")
            );
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse proxyResp = CorsRangeWebViewInterceptor.handleProxyRequest(request);
                if (proxyResp != null) return proxyResp;
                WebResourceResponse assetResp = assetLoader.shouldInterceptRequest(request.getUrl());
                if (assetResp != null) return assetResp;
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handleIncomingAudioIntent(getIntent());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new AndroidDawBridge(), "AndroidBridge");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101);
            }
        }

        IntentFilter filter = new IntentFilter(ConversionService.ACTION_BROADCAST_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(conversionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(conversionReceiver, filter);
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Rational aspectRatio = new Rational(16, 9);
                PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                pipBuilder.setAspectRatio(aspectRatio);
                enterPictureInPictureMode(pipBuilder.build());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (webView != null) {
            String js = "if(window.__setPipMode){window.__setPipMode(" + isInPictureInPictureMode + ");}";
            webView.evaluateJavascript(js, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(conversionReceiver); } catch (Exception ignored) {}
        if (devicePlayer != null) {
            try { devicePlayer.release(); } catch (Exception ignored) {}
            devicePlayer = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingAudioIntent(intent);
    }

    private void handleIncomingAudioIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        String name = data.getLastPathSegment() != null ? data.getLastPathSegment() : "External Audio";
        String js = "if(window.__handleExternalAudioViewIntent){window.__handleExternalAudioViewIntent("
                + JSONObject.quote(name) + ", 'audio/*');}";
        webView.evaluateJavascript(js, null);
    }

    public class AndroidDawBridge {
        @JavascriptInterface
        public boolean isAndroidApp() { return true; }

        @JavascriptInterface
        public boolean startNativeConversion(String input, String mode, int bitrate, int quality, String outName) {
            if (input == null || input.trim().isEmpty()) return false;
            Intent svc = new Intent(MainActivity.this, ConversionService.class);
            svc.setAction(ConversionService.ACTION_START);
            svc.putExtra("input", input.trim());
            svc.putExtra("mode", mode != null ? mode : "yt-mp3");
            svc.putExtra("bitrate", bitrate);
            svc.putExtra("quality", quality);
            svc.putExtra("outName", outName != null ? outName : "");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            return true;
        }

        @JavascriptInterface
        public void cancelNativeConversion() {
            Intent svc = new Intent(MainActivity.this, ConversionService.class);
            svc.setAction(ConversionService.ACTION_CANCEL);
            startService(svc);
        }

        @JavascriptInterface
        public String getNativeConversionState() {
            SharedPreferences prefs = getSharedPreferences(ConversionService.PREFS_NAME, MODE_PRIVATE);
            return prefs.getString("conversion_runtime_state", "{}");
        }

        @JavascriptInterface
        public String getNativeConversionHistory() {
            SharedPreferences prefs = getSharedPreferences(ConversionService.PREFS_NAME, MODE_PRIVATE);
            return prefs.getString("conversion_history", "[]");
        }

        @JavascriptInterface
        public void pickNativeMedia(String mediaKind) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("video".equalsIgnoreCase(mediaKind) ? "video/*" : "audio/*");
            startActivityForResult(pick, 4101);
        }

        @JavascriptInterface
        public String readDeviceSongBase64(String uriStr) {
            if (uriStr == null || uriStr.isEmpty()) return "";
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(uriStr));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) return "";
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 32 * 1024 * 1024) return "";
                }
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String listDeviceSongsJson() {
            JSONArray arr = new JSONArray();
            try {
                ContentResolver resolver = getContentResolver();
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA
                };
                Cursor cursor = resolver.query(uri, proj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC");
                if (cursor != null) {
                    int count = 0;
                    while (cursor.moveToNext() && count < 200) {
                        long id = cursor.getLong(0);
                        String path = cursor.getString(5);
                        String folderName = "Music";
                        if (path != null && !path.isEmpty()) {
                            File parent = new File(path).getParentFile();
                            if (parent != null && !parent.getName().isEmpty()) {
                                folderName = parent.getName();
                            }
                        }
                        JSONObject o = new JSONObject();
                        o.put("id", String.valueOf(id));
                        o.put("title", cursor.getString(1));
                        o.put("artist", cursor.getString(2));
                        o.put("album", cursor.getString(3));
                        o.put("folder", folderName);
                        o.put("duration", cursor.getLong(4) / 1000.0);
                        o.put("uri", ContentUris.withAppendedId(uri, id).toString());
                        arr.put(o);
                        count++;
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface
        public boolean playDeviceSong(String uriStr, String title, String artist, String queueJson) {
            return playDeviceSong(uriStr, title, artist);
        }

        @JavascriptInterface
        public boolean playDeviceSong(String uriStr, String title, String artist) {
            try {
                if (devicePlayer != null) {
                    devicePlayer.release();
                }
                devicePlayer = new MediaPlayer();
                devicePlayer.setDataSource(MainActivity.this, Uri.parse(uriStr));
                devicePlayer.prepare();
                devicePlayer.start();
                updateMediaNotification(title, artist, true);
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean toggleDeviceSongPlayback() {
            if (devicePlayer == null) return false;
            if (devicePlayer.isPlaying()) devicePlayer.pause();
            else devicePlayer.start();
            return devicePlayer.isPlaying();
        }

        @JavascriptInterface
        public void seekDeviceSong(double seconds) {
            if (devicePlayer != null) {
                devicePlayer.seekTo((int) Math.round(seconds * 1000));
            }
        }

        @JavascriptInterface
        public void setDeviceSongPlaybackRate(float speed) {
            if (devicePlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    PlaybackParams params = devicePlayer.getPlaybackParams();
                    params.setSpeed(speed);
                    devicePlayer.setPlaybackParams(params);
                } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public void updatePlaybackNotification(String title, String artist, boolean playing) {
            updateMediaNotification(title, artist, playing);
        }

        @JavascriptInterface
        public boolean shareNativeFile(String uriStr, String mime) {
            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType(mime != null ? mime : "audio/mpeg");
                share.putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr));
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share Converted Media"));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void saveExportedAudioBase64(String b64, String fileName, String mime) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TigerBeatsStudio");
                dir.mkdirs();
                File out = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean setAsDeviceRingtoneBase64(String b64, String title) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                File out = new File(getExternalFilesDir(Environment.DIRECTORY_RINGTONES), title + ".mp3");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                RingtoneManager.setActualDefaultRingtoneUri(MainActivity.this, RingtoneManager.TYPE_RINGTONE, Uri.fromFile(out));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String searchYouTubeNativeJson(String query) {
            try {
                URL u = new URL("https://pipedapi.kavin.rocks/search?q=" + Uri.encode(query) + "&filter=music_songs");
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) { return "{\"items\":[]}"; }
        }

        @JavascriptInterface
        public void searchYouTubeAsync(String query, String callbackId) {
            bgExecutor.submit(() -> {
                String json = searchYouTubeNativeJson(query);
                String js = String.format("if(window.__onNativeYtSearchDone){window.__onNativeYtSearchDone(%s,%s);}",
                        JSONObject.quote(callbackId != null ? callbackId : ""),
                        JSONObject.quote(json));
                webView.post(() -> webView.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public boolean hasDeviceAudioPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
            }
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestDeviceAudioPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 4102);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4102);
            }
        }

        @JavascriptInterface
        public String getDownloadFolderLabel() { return "Downloads/TigerBeatsStudio"; }

        @JavascriptInterface
        public void chooseDownloadFolder() {}

        @JavascriptInterface
        public void resetDownloadFolder() {}

        @JavascriptInterface
        public long getCacheBytes() {
            File c = getCacheDir();
            return c != null ? c.length() : 0L;
        }

        @JavascriptInterface
        public boolean clearAppCache() { return true; }

        @JavascriptInterface
        public boolean createV7LibraryBackup(String json) { return true; }

        @JavascriptInterface
        public void pickV7LibraryBackup() {}

        @JavascriptInterface
        public boolean hasSeenFirstRunTour() {
            return getSharedPreferences(ConversionService.PREFS_NAME, MODE_PRIVATE).getBoolean("seen_tour", false);
        }

        @JavascriptInterface
        public void markFirstRunTourSeen() {
            getSharedPreferences(ConversionService.PREFS_NAME, MODE_PRIVATE).edit().putBoolean("seen_tour", true).apply();
        }

        @JavascriptInterface
        public String getFirebaseGoogleUser() { return "{\"signedIn\":false}"; }

        @JavascriptInterface
        public void signInWithGoogle() {}

        @JavascriptInterface
        public void signOutGoogle() {}

        @JavascriptInterface
        public void requestMakeItHitAdvice(String payloadJson) {}

        @JavascriptInterface
        public String getConnectedHeadphoneType() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    for (AudioDeviceInfo dev : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        int t = dev.getType();
                        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "bluetooth";
                        if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET) return "wired";
                    }
                }
            } catch (Exception ignored) {}
            return "speaker";
        }

        @JavascriptInterface
        public void setHardwareDolbyProfile(boolean enabled) {}

        @JavascriptInterface
        public void updateMediaNotification(String title, String artist, boolean isPlaying) {
            Intent svc = new Intent(MainActivity.this, ArmacoPlaybackService.class);
            svc.putExtra("title", title);
            svc.putExtra("artist", artist);
            startService(svc);
        }

        @JavascriptInterface
        public void stopDeviceSongPlayback() {
            if (devicePlayer != null) {
                try {
                    if (devicePlayer.isPlaying()) devicePlayer.stop();
                    devicePlayer.release();
                } catch (Exception ignored) {}
                devicePlayer = null;
            }
            Intent svc = new Intent(MainActivity.this, ArmacoPlaybackService.class);
            svc.setAction(ArmacoPlaybackService.ACTION_STOP_CLEAR);
            startService(svc);
        }

        @JavascriptInterface
        public void requestPinHomeWidget() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AppWidgetManager awm = getSystemService(AppWidgetManager.class);
                ComponentName provider = new ComponentName(MainActivity.this, TigerBeatsStudioWidgetProvider.class);
                if (awm != null && awm.isRequestPinAppWidgetSupported()) {
                    Intent pinnedIntent = new Intent(MainActivity.this, MainActivity.class);
                    PendingIntent successCallback = PendingIntent.getActivity(
                            MainActivity.this, 0, pinnedIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    awm.requestPinAppWidget(provider, null, successCallback);
                }
            }
        }
    }
}
