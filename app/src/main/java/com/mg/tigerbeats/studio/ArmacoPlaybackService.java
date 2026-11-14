package com.mg.tigerbeats.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Reliable Background Audio Playback Service (Section 11):
 * - Owns a native MediaPlayer + queue so playback continues when the user leaves the app or locks the screen.
 * - Integrates MediaSessionCompat + NotificationCompat.MediaStyle lock-screen controls (Previous, Play/Pause, Next, Seek).
 * - Implements EarGuard Call Safety (AUDIOFOCUS_LOSS_TRANSIENT -> pause/duck -24dB) and
 *   Notification Safety (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> smooth attenuation).
 * - Pushes live updates to TigerBeatsStudioWidgetProvider and cleans up on onTaskRemoved().
 */
public class ArmacoPlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String CHANNEL_ID = "armaco_mg_playback_channel";
    public static final int NOTIF_ID = 9401;

    public static final String ACTION_PLAY_URI = "com.mg.tigerbeats.studio.ACTION_PLAY_URI";
    public static final String ACTION_TOGGLE_PLAY = "com.mg.tigerbeats.studio.ACTION_TOGGLE_PLAY";
    public static final String ACTION_PREV = "com.mg.tigerbeats.studio.ACTION_PREV";
    public static final String ACTION_NEXT = "com.mg.tigerbeats.studio.ACTION_NEXT";
    public static final String ACTION_SEEK = "com.mg.tigerbeats.studio.ACTION_SEEK";
    public static final String ACTION_STOP_CLEAR = "com.mg.tigerbeats.studio.ACTION_STOP_CLEAR";

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    private final List<Uri> queueUris = new ArrayList<>();
    private final List<String> queueTitles = new ArrayList<>();
    private final List<String> queueArtists = new ArrayList<>();
    private int currentIndex = -1;

    private String currentTitle = "TigerBeats Studio";
    private String currentArtist = "Armaco MG Edition";
    private boolean resumeOnFocusGain = false;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pausePlayback();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannelIfNeeded();
        initMediaSession();
        try {
            registerReceiver(noisyReceiver, new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception ignored) {}
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "TigerBeatsMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                skipToNextTrack();
            }

            @Override
            public void onSkipToPrevious() {
                skipToPreviousTrack();
            }

            @Override
            public void onSeekTo(long pos) {
                seekPlaybackTo((int) pos);
            }

            @Override
            public void onStop() {
                clearNotificationAndStop();
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP_CLEAR.equals(action)) {
            clearNotificationAndStop();
            return START_NOT_STICKY;
        } else if (ACTION_TOGGLE_PLAY.equals(action)) {
            if (isPlaying()) pausePlayback();
            else resumePlayback();
            return START_STICKY;
        } else if (ACTION_PREV.equals(action)) {
            skipToPreviousTrack();
            return START_STICKY;
        } else if (ACTION_NEXT.equals(action)) {
            skipToNextTrack();
            return START_STICKY;
        } else if (ACTION_SEEK.equals(action)) {
            int posMs = intent.getIntExtra("position_ms", 0);
            seekPlaybackTo(posMs);
            return START_STICKY;
        }

        String titleExtra = intent.getStringExtra("title");
        String artistExtra = intent.getStringExtra("artist");
        if (titleExtra != null && !titleExtra.isEmpty()) currentTitle = titleExtra;
        if (artistExtra != null && !artistExtra.isEmpty()) currentArtist = artistExtra;

        Uri dataUri = intent.getData();
        if (dataUri == null && intent.getStringExtra("uri") != null) {
            dataUri = Uri.parse(intent.getStringExtra("uri"));
        }

        if (dataUri != null) {
            playUriInternal(dataUri, currentTitle, currentArtist);
        } else {
            updateMediaSessionAndNotification();
        }
        return START_STICKY;
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(
                    this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (mediaPlayer == null) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // EarGuard Call Safety: Incoming call -> duck/pause immediately
                if (isPlaying()) {
                    resumeOnFocusGain = true;
                    mediaPlayer.setVolume(0.06f, 0.06f);
                    pausePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // EarGuard Notification Safety: Attenuate transient notification spike
                if (isPlaying()) {
                    mediaPlayer.setVolume(0.22f, 0.22f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mediaPlayer.setVolume(1.0f, 1.0f);
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    resumePlayback();
                }
                break;
            default:
                break;
        }
    }

    private void playUriInternal(Uri uri, String title, String artist) {
        try {
            if (!queueUris.contains(uri)) {
                queueUris.add(uri);
                queueTitles.add(title != null ? title : "Audio Track");
                queueArtists.add(artist != null ? artist : "TigerBeats Studio");
                currentIndex = queueUris.size() - 1;
            } else {
                currentIndex = queueUris.indexOf(uri);
            }
            currentTitle = queueTitles.get(currentIndex);
            currentArtist = queueArtists.get(currentIndex);

            requestAudioFocus();
            releaseMediaPlayerOnly();

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
            );
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.setVolume(1.0f, 1.0f);
                mp.start();
                updateMediaSessionAndNotification();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (queueUris.size() > 1) {
                    skipToNextTrack();
                } else {
                    updateMediaSessionAndNotification();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                updateMediaSessionAndNotification();
                return true;
            });
            mediaPlayer.prepareAsync();
            updateMediaSessionAndNotification();
        } catch (Exception e) {
            updateMediaSessionAndNotification();
        }
    }

    public boolean isPlaying() {
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void resumePlayback() {
        try {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                if (requestAudioFocus()) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                    mediaPlayer.start();
                }
            }
        } catch (Exception ignored) {}
        updateMediaSessionAndNotification();
    }

    private void pausePlayback() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Exception ignored) {}
        updateMediaSessionAndNotification();
    }

    private void seekPlaybackTo(int posMs) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.seekTo(Math.max(0, posMs));
            }
        } catch (Exception ignored) {}
        updateMediaSessionAndNotification();
    }

    private void skipToNextTrack() {
        if (queueUris.isEmpty()) return;
        currentIndex = (currentIndex + 1) % queueUris.size();
        playUriInternal(queueUris.get(currentIndex), queueTitles.get(currentIndex), queueArtists.get(currentIndex));
    }

    private void skipToPreviousTrack() {
        if (queueUris.isEmpty()) return;
        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3500) {
            seekPlaybackTo(0);
            return;
        }
        currentIndex = (currentIndex - 1 + queueUris.size()) % queueUris.size();
        playUriInternal(queueUris.get(currentIndex), queueTitles.get(currentIndex), queueArtists.get(currentIndex));
    }

    private void updateMediaSessionAndNotification() {
        boolean playing = isPlaying();
        long pos = 0L;
        long dur = 0L;
        try {
            if (mediaPlayer != null) {
                pos = mediaPlayer.getCurrentPosition();
                dur = Math.max(0, mediaPlayer.getDuration());
            }
        } catch (Exception ignored) {}

        if (mediaSession != null) {
            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackStateCompat.ACTION_SEEK_TO
                            | PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(
                            playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                            pos,
                            1.0f
                    )
                    .build();
            mediaSession.setPlaybackState(state);

            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                    .build();
            mediaSession.setMetadata(metadata);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent prevPi = buildServiceActionPendingIntent(ACTION_PREV, 11);
        PendingIntent playPausePi = buildServiceActionPendingIntent(ACTION_TOGGLE_PLAY, 12);
        PendingIntent nextPi = buildServiceActionPendingIntent(ACTION_NEXT, 13);
        PendingIntent stopPi = buildServiceActionPendingIntent(ACTION_STOP_CLEAR, 14);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentPi)
                .setDeleteIntent(stopPi)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPi)
                .addAction(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play",
                        playPausePi
                )
                .addAction(android.R.drawable.ic_media_next, "Next", nextPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .setStyle(
                        new MediaStyle()
                                .setMediaSession(mediaSession != null ? mediaSession.getSessionToken() : null)
                                .setShowActionsInCompactView(0, 1, 2)
                )
                .build();

        startForeground(NOTIF_ID, notif);
        TigerBeatsStudioWidgetProvider.updateAllWidgets(
                this, currentTitle, currentArtist, playing ? "Playing" : "Paused", playing
        );
    }

    private PendingIntent buildServiceActionPendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, ArmacoPlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(
                this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        clearNotificationAndStop();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        clearNotificationAndStop();
        super.onDestroy();
    }

    private void releaseMediaPlayerOnly() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void clearNotificationAndStop() {
        releaseMediaPlayerOnly();
        abandonAudioFocus();
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception ignored) {}
            mediaSession = null;
        }
        try {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
                nm.cancelAll();
            }
        } catch (Exception ignored) {}
        TigerBeatsStudioWidgetProvider.updateAllWidgets(
                this, "TigerBeats Studio", "Ready", "Stopped", false
        );
        stopSelf();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "TigerBeats Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
