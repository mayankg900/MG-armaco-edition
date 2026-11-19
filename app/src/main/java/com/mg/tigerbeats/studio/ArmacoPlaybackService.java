package com.mg.tigerbeats.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Owns audio playback independently of the WebView/Activity lifecycle. */
public class ArmacoPlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String CHANNEL_ID = "armaco_mg_playback_channel";
    public static final int NOTIF_ID = 9401;
    public static final String ACTION_PLAY_URI = "com.mg.tigerbeats.studio.ACTION_PLAY_URI";
    public static final String ACTION_TOGGLE_PLAY = "com.mg.tigerbeats.studio.ACTION_TOGGLE_PLAY";
    public static final String ACTION_PREV = "com.mg.tigerbeats.studio.ACTION_PREV";
    public static final String ACTION_NEXT = "com.mg.tigerbeats.studio.ACTION_NEXT";
    public static final String ACTION_SEEK = "com.mg.tigerbeats.studio.ACTION_SEEK";
    public static final String ACTION_STOP_CLEAR = "com.mg.tigerbeats.studio.ACTION_STOP_CLEAR";
    private static volatile boolean activePlayback;

    private final List<Uri> queueUris = new ArrayList<>();
    private final List<String> queueTitles = new ArrayList<>();
    private final List<String> queueArtists = new ArrayList<>();
    private MediaPlayer player;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int currentIndex = -1;
    private String currentTitle = "TigerBeats Studio";
    private String currentArtist = "Armaco MG Edition";
    private boolean resumeAfterFocus = false;
    private boolean prepared = false;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pausePlayback();
        }
    };

    static boolean isPlaybackActive() { return activePlayback; }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "TigerBeats Playback", NotificationManager.IMPORTANCE_LOW));
        }
        initMediaSession();
        try { registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); } catch (Exception ignored) { }
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "TigerBeatsMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { resumePlayback(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onSkipToNext() { skipToNextTrack(); }
            @Override public void onSkipToPrevious() { skipToPreviousTrack(); }
            @Override public void onSeekTo(long position) { seekPlaybackTo((int) position); }
            @Override public void onStop() { clearNotificationAndStop(); }
        });
        mediaSession.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP_CLEAR.equals(action)) { clearNotificationAndStop(); return START_NOT_STICKY; }
        if (ACTION_TOGGLE_PLAY.equals(action)) { if (isPlaying()) pausePlayback(); else resumePlayback(); return START_STICKY; }
        if (ACTION_PREV.equals(action)) { skipToPreviousTrack(); return START_STICKY; }
        if (ACTION_NEXT.equals(action)) { skipToNextTrack(); return START_STICKY; }
        if (ACTION_SEEK.equals(action)) { seekPlaybackTo(intent.getIntExtra("position_ms", 0)); return START_STICKY; }

        String queueJson = intent.getStringExtra("queue");
        Uri uri = intent.getData();
        if (uri == null) {
            String rawUri = intent.getStringExtra("uri");
            if (rawUri != null && !rawUri.isEmpty()) try { uri = Uri.parse(rawUri); } catch (Exception ignored) { }
        }
        if (queueJson != null) loadQueue(queueJson);
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        if (uri != null) playUri(uri, title, artist);
        else if (player != null && isPlaying()) updateMediaSessionAndNotification();
        return START_NOT_STICKY;
    }

    private void loadQueue(String json) {
        try {
            JSONArray items = new JSONArray(json);
            List<Uri> uris = new ArrayList<>(); List<String> titles = new ArrayList<>(); List<String> artists = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                String value = item.optString("uri", ""); if (value.isEmpty()) continue;
                uris.add(Uri.parse(value)); titles.add(item.optString("title", "Audio Track")); artists.add(item.optString("artist", "TigerBeats Studio"));
            }
            if (!uris.isEmpty()) { queueUris.clear(); queueUris.addAll(uris); queueTitles.clear(); queueTitles.addAll(titles); queueArtists.clear(); queueArtists.addAll(artists); }
        } catch (Exception e) { android.util.Log.w("TigerBeatsPlayback", "Ignoring invalid playback queue", e); }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(this).build();
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(this);
    }

    @Override public void onAudioFocusChange(int change) {
        if (player == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeAfterFocus = isPlaying(); pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeAfterFocus = false; pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try { if (isPlaying()) player.setVolume(0.20f, 0.20f); } catch (Exception ignored) { }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            try { if (player != null) player.setVolume(1f, 1f); } catch (Exception ignored) { }
            if (resumeAfterFocus) { resumeAfterFocus = false; resumePlayback(); }
        }
    }

    private void playUri(Uri uri, String title, String artist) {
        int index = queueUris.indexOf(uri);
        if (index >= 0) currentIndex = index;
        else {
            queueUris.add(uri); queueTitles.add(title == null || title.isEmpty() ? "Audio Track" : title);
            queueArtists.add(artist == null || artist.isEmpty() ? "TigerBeats Studio" : artist);
            currentIndex = queueUris.size() - 1;
        }
        currentTitle = title == null || title.isEmpty() ? queueTitles.get(currentIndex) : title;
        currentArtist = artist == null || artist.isEmpty() ? queueArtists.get(currentIndex) : artist;
        releasePlayer();
        if (!requestAudioFocus()) { updateMediaSessionAndNotification(); return; }
        try {
            player = new MediaPlayer(); prepared = false;
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setOnPreparedListener(mp -> { if (player != mp) return; prepared = true; try { mp.start(); activePlayback = true; } catch (IllegalStateException e) { releasePlayer(); } updateMediaSessionAndNotification(); });
            player.setOnCompletionListener(mp -> { if (player != mp) return; if (queueUris.size() > 1) skipToNextTrack(); else { activePlayback = false; updateMediaSessionAndNotification(); } });
            player.setOnErrorListener((mp, what, extra) -> { android.util.Log.e("TigerBeatsPlayback", "MediaPlayer error " + what + "/" + extra); activePlayback = false; releasePlayer(); updateMediaSessionAndNotification(); return true; });
            player.setDataSource(this, uri);
            updateMediaSessionAndNotification();
            player.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("TigerBeatsPlayback", "Could not open media URI", e); activePlayback = false; releasePlayer(); updateMediaSessionAndNotification();
        }
    }

    private boolean isPlaying() { try { return player != null && prepared && player.isPlaying(); } catch (Exception e) { return false; } }
    private void resumePlayback() {
        try { if (player != null && prepared && !player.isPlaying() && requestAudioFocus()) { player.setVolume(1f, 1f); player.start(); activePlayback = true; } }
        catch (Exception e) { android.util.Log.w("TigerBeatsPlayback", "Resume failed", e); }
        updateMediaSessionAndNotification();
    }
    private void pausePlayback() {
        try { if (player != null && prepared && player.isPlaying()) player.pause(); } catch (Exception ignored) { }
        activePlayback = false; updateMediaSessionAndNotification();
    }
    private void seekPlaybackTo(int positionMs) {
        try { if (player != null && prepared) player.seekTo(Math.max(0, positionMs)); } catch (Exception ignored) { }
        updateMediaSessionAndNotification();
    }
    private void skipToNextTrack() {
        if (queueUris.isEmpty()) return;
        currentIndex = (currentIndex + 1) % queueUris.size(); playUri(queueUris.get(currentIndex), queueTitles.get(currentIndex), queueArtists.get(currentIndex));
    }
    private void skipToPreviousTrack() {
        if (queueUris.isEmpty()) return;
        try { if (player != null && prepared && player.getCurrentPosition() > 3500) { seekPlaybackTo(0); return; } } catch (Exception ignored) { }
        currentIndex = (currentIndex - 1 + queueUris.size()) % queueUris.size(); playUri(queueUris.get(currentIndex), queueTitles.get(currentIndex), queueArtists.get(currentIndex));
    }

    private void updateMediaSessionAndNotification() {
        boolean playing = isPlaying(); long pos = 0, duration = 0;
        try { if (player != null && prepared) { pos = player.getCurrentPosition(); duration = Math.max(0, player.getDuration()); } } catch (Exception ignored) { }
        if (mediaSession != null) {
            long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions).setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, pos, 1f).build());
            mediaSession.setMetadata(new MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist).putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration).build());
        }
        PendingIntent open = PendingIntent.getActivity(this, 10, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent previous = servicePendingIntent(ACTION_PREV, 11), playPause = servicePendingIntent(ACTION_TOGGLE_PLAY, 12), next = servicePendingIntent(ACTION_NEXT, 13), stop = servicePendingIntent(ACTION_STOP_CLEAR, 14);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(currentTitle).setContentText(currentArtist)
                .setContentIntent(open).setDeleteIntent(stop).setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", previous)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", playPause)
                .addAction(android.R.drawable.ic_media_next, "Next", next)
                .setStyle(new MediaStyle().setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
        startForeground(NOTIF_ID, notification);
        TigerBeatsStudioWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, playing ? "Playing" : (prepared ? "Paused" : "Loading"), playing);
    }

    private PendingIntent servicePendingIntent(String action, int code) {
        Intent intent = new Intent(this, ArmacoPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void releasePlayer() {
        MediaPlayer old = player; player = null; prepared = false;
        if (old != null) { try { old.setOnPreparedListener(null); old.setOnCompletionListener(null); old.setOnErrorListener(null); old.reset(); } catch (Exception ignored) { } try { old.release(); } catch (Exception ignored) { } }
        activePlayback = false;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep audio alive when the launcher task is swiped away; stop is an explicit user action.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        releasePlayer(); abandonAudioFocus();
        if (mediaSession != null) { try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) { } mediaSession = null; }
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void clearNotificationAndStop() {
        releasePlayer(); abandonAudioFocus();
        if (mediaSession != null) { try { mediaSession.setActive(false); } catch (Exception ignored) { } }
        stopForeground(true); NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.cancel(NOTIF_ID);
        TigerBeatsStudioWidgetProvider.updateAllWidgets(this, "TigerBeats Studio", "Ready", "Stopped", false); stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
