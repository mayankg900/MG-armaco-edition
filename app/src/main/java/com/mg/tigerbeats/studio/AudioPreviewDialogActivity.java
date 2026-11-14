package com.mg.tigerbeats.studio;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * File-Manager Audio Opening Activity (Section 10):
 * - Supports ACTION_VIEW and ACTION_SEND across content:// and file:// schemes for audio/*, application/ogg, application/x-flac.
 * - Uses ContentResolver (OpenableColumns.DISPLAY_NAME + ParcelFileDescriptor + MediaMetadataRetriever)
 *   to resolve real track title, artist, and embedded artwork safely without assuming raw filesystem paths.
 * - Delegates playback to ArmacoPlaybackService so background audio continues reliably.
 */
public class AudioPreviewDialogActivity extends Activity {
    private boolean keepBackgroundPlay = true;
    private boolean isPlayingState = true;
    private Uri audioUri;
    private String resolvedTitle = "Audio Track";
    private String resolvedArtist = "Local Audio";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (getWindow() != null) {
            getWindow().setGravity(Gravity.BOTTOM);
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        audioUri = extractIncomingAudioUri(getIntent());
        Bitmap embeddedArt = resolveAudioMetadataViaContentResolver(audioUri);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 40, 48, 48);
        root.setBackgroundColor(0xFF0E1326);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView artView = new ImageView(this);
        if (embeddedArt != null) {
            artView.setImageBitmap(embeddedArt);
        } else {
            artView.setImageResource(R.mipmap.ic_launcher);
        }
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(128, 128);
        artLp.setMarginEnd(28);
        headerRow.addView(artView, artLp);

        LinearLayout metaCol = new LinearLayout(this);
        metaCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(resolvedTitle);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16f);
        metaCol.addView(titleView);

        TextView artistView = new TextView(this);
        artistView.setText(resolvedArtist);
        artistView.setTextColor(0xFF94A3B8);
        artistView.setTextSize(12f);
        metaCol.addView(artistView);

        headerRow.addView(metaCol);
        root.addView(headerRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 28, 0, 0);

        Button btnPlayPause = new Button(this);
        btnPlayPause.setText("❚❚ Pause");
        btnRow.addView(btnPlayPause);

        Button btnBgToggle = new Button(this);
        btnBgToggle.setText("Background: ON");
        btnRow.addView(btnBgToggle);

        Button btnOpenDaw = new Button(this);
        btnOpenDaw.setText("Open in Studio DAW");
        btnRow.addView(btnOpenDaw);

        root.addView(btnRow);
        setContentView(root);

        if (audioUri != null) {
            startPlaybackServiceWithUri(audioUri, resolvedTitle, resolvedArtist);
        } else {
            titleView.setText("Unsupported or missing audio URI");
            btnPlayPause.setEnabled(false);
        }

        btnPlayPause.setOnClickListener(v -> {
            isPlayingState = !isPlayingState;
            btnPlayPause.setText(isPlayingState ? "❚❚ Pause" : "▶ Play");
            Intent toggle = new Intent(this, ArmacoPlaybackService.class);
            toggle.setAction(ArmacoPlaybackService.ACTION_TOGGLE_PLAY);
            startServiceSafely(toggle);
        });

        btnBgToggle.setOnClickListener(v -> {
            keepBackgroundPlay = !keepBackgroundPlay;
            btnBgToggle.setText(keepBackgroundPlay ? "Background: ON" : "Background: OFF");
        });

        btnOpenDaw.setOnClickListener(v -> {
            Intent dawIntent = new Intent(this, MainActivity.class);
            if (audioUri != null) {
                dawIntent.setData(audioUri);
                dawIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                dawIntent.putExtra("open_in_studio_daw", true);
            }
            startActivity(dawIntent);
            finish();
        });
    }

    private Uri extractIncomingAudioUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            try {
                final int takeFlags = intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (takeFlags != 0) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {}
        }
        return uri;
    }

    private Bitmap resolveAudioMetadataViaContentResolver(Uri uri) {
        if (uri == null) return null;
        ContentResolver resolver = getContentResolver();

        // 1. Query OpenableColumns.DISPLAY_NAME
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        resolvedTitle = name.trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        if ("Audio Track".equals(resolvedTitle) && uri.getLastPathSegment() != null) {
            resolvedTitle = uri.getLastPathSegment();
        }

        // 2. Extract ID3/container metadata & artwork via MediaMetadataRetriever + ParcelFileDescriptor
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                mmr.setDataSource(pfd.getFileDescriptor());
                String metaTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String metaArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (metaTitle != null && !metaTitle.trim().isEmpty()) {
                    resolvedTitle = metaTitle.trim();
                }
                if (metaArtist != null && !metaArtist.trim().isEmpty()) {
                    resolvedArtist = metaArtist.trim();
                }
                byte[] artBytes = mmr.getEmbeddedPicture();
                if (artBytes != null && artBytes.length > 0) {
                    return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private void startPlaybackServiceWithUri(Uri uri, String title, String artist) {
        Intent svc = new Intent(this, ArmacoPlaybackService.class);
        svc.setAction(ArmacoPlaybackService.ACTION_PLAY_URI);
        svc.setData(uri);
        svc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        svc.putExtra("title", title);
        svc.putExtra("artist", artist);
        startServiceSafely(svc);
    }

    private void startServiceSafely(Intent svc) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {
            try { startService(svc); } catch (Exception ignored2) {}
        }
    }

    @Override
    protected void onDestroy() {
        if (!keepBackgroundPlay) {
            Intent stopSvc = new Intent(this, ArmacoPlaybackService.class);
            stopSvc.setAction(ArmacoPlaybackService.ACTION_STOP_CLEAR);
            try { startService(stopSvc); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
