package com.mg.tigerbeats.studio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Functional Home-Screen Music Widget (Section 12):
 * - Displays album artwork/logo, track title, artist name, playback status,
 *   Previous, Play/Pause, Next, Converter shortcut, and Open Player shortcut.
 * - Uses only RemoteViews-safe layout views and pushes live updates via updateAllWidgets().
 */
public class TigerBeatsStudioWidgetProvider extends AppWidgetProvider {
    private static final String PREFS_WIDGET = "tigerbeats_widget_state";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        String title = prefs.getString("title", "TigerBeats Studio");
        String artist = prefs.getString("artist", "Armaco MG Edition");
        String status = prefs.getString("status", "Ready");
        boolean isPlaying = prefs.getBoolean("is_playing", false);

        for (int widgetId : appWidgetIds) {
            RemoteViews views = buildRemoteViews(context, title, artist, status, isPlaying);
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }

    public static void updateAllWidgets(Context context, String title, String artist, String status, boolean isPlaying) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("title", title != null ? title : "TigerBeats Studio")
                    .putString("artist", artist != null ? artist : "Armaco MG Edition")
                    .putString("status", status != null ? status : "Ready")
                    .putBoolean("is_playing", isPlaying)
                    .apply();

            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, TigerBeatsStudioWidgetProvider.class));
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    RemoteViews views = buildRemoteViews(context, title, artist, status, isPlaying);
                    mgr.updateAppWidget(id, views);
                }
            }
        } catch (Exception ignored) {}
    }

    private static RemoteViews buildRemoteViews(
            Context context, String title, String artist, String status, boolean isPlaying
    ) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_tigerbeats_studio);
        views.setTextViewText(R.id.widget_track_title, title != null ? title : "TigerBeats Studio");
        views.setTextViewText(R.id.widget_track_artist, artist != null ? artist : "Armaco MG Edition");
        views.setTextViewText(R.id.widget_playback_status, status != null ? status : "Ready");
        views.setTextViewText(R.id.widget_play_pause, isPlaying ? "❚❚" : "▶");
        views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher);

        Intent playerIntent = new Intent(context, MainActivity.class);
        PendingIntent playerPi = PendingIntent.getActivity(
                context, 201, playerIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root_container, playerPi);
        views.setOnClickPendingIntent(R.id.widget_open_player, playerPi);

        Intent converterIntent = new Intent(context, ConverterActivity.class);
        PendingIntent converterPi = PendingIntent.getActivity(
                context, 202, converterIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_open_converter, converterPi);

        views.setOnClickPendingIntent(
                R.id.widget_previous,
                buildServicePendingIntent(context, ArmacoPlaybackService.ACTION_PREV, 203)
        );
        views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                buildServicePendingIntent(context, ArmacoPlaybackService.ACTION_TOGGLE_PLAY, 204)
        );
        views.setOnClickPendingIntent(
                R.id.widget_next,
                buildServicePendingIntent(context, ArmacoPlaybackService.ACTION_NEXT, 205)
        );

        return views;
    }

    private static PendingIntent buildServicePendingIntent(Context context, String action, int requestCode) {
        Intent svc = new Intent(context, ArmacoPlaybackService.class);
        svc.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(
                    context, requestCode, svc, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        } else {
            return PendingIntent.getService(
                    context, requestCode, svc, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }
    }
}
