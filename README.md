# TigerBeats Studio — ARMaco MG Edition

A themed Android music studio, player, and on-device media converter.

## Studio screenshots

The images below are cropped to remove Android status and navigation bars. They show the Player in its dark Obsidian and light Creamy Pink themes.

### Player — Obsidian 3D

![TigerBeats Studio player in Obsidian 3D theme](docs/screenshots/player-obsidian.png)

### Player — Creamy Pink

![TigerBeats Studio player in Creamy Pink theme](docs/screenshots/player-creamy-pink.png)

## Core features

- Local music library and playback
- Native yt-dlp/FFmpeg conversion for supported public links
- Studio DAW, DSP presets, and player themes
- Android MediaSession, foreground playback, widget, and PiP integration

## Build

```bash
./gradlew assembleDebug
```

Release signing uses `ANDROID_RELEASE_STORE_FILE`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD`. Do not commit credentials or keystores.

The WebView studio source is `app/src/main/assets/index.html`. Do not replace it with a marketing page or redirect. GitHub Pages showcase HTML remains separate at the repository root.
