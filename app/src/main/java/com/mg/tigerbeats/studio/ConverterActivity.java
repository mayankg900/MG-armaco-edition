package com.mg.tigerbeats.studio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Dedicated entry activity for launching the On-Device yt-dlp + FFmpeg Converter
 * from the Home-Screen Widget or Android Share sheet (`ACTION_SEND` text/plain URL).
 */
public class ConverterActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra("open_converter_tab", true);

        Intent incoming = getIntent();
        if (incoming != null) {
            String sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                launch.putExtra("shared_youtube_url", sharedText.trim());
            }
        }
        startActivity(launch);
        finish();
    }
}
