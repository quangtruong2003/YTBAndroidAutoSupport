# YTBAndroidAutoSupport

A focused **Fermata Web+** fork for using YouTube through Android Auto in a same-device/head-unit setup.

This fork keeps the YouTube WebView as the primary UI, synchronizes YouTube playback with Android's media session, and fixes **Play/Pause, Previous and Next** so Android Auto controls the actual YouTube player instead of trying to play synthetic media URLs.

> **Safety:** video playback is intended for **parked / stationary use only**. This project does not spoof vehicle speed, fake driving state, or attempt to bypass Android Auto's driver-distraction restrictions.

## What this fork changes

- Focuses Fermata on **Web / YouTube**.
- Keeps YouTube's own player UI instead of switching to Fermata's generic player UI.
- Hides Fermata playback chrome while YouTube is active, including fullscreen transitions.
- Publishes YouTube title/playback state through Android's MediaSession.
- Routes Android Auto **Previous / Next** directly to the YouTube WebView player.
- Adds best-effort YouTube recommendation data for Android Auto media surfaces.
- Adds best-effort media activation hooks for the same-device Open Headunit setup.

Android Auto still controls its own launcher, taskbar ordering, icon cache and dashboard rendering, so the exact appearance can vary by Android Auto version.

## Fastest installation

The easiest method is to install the prebuilt APK from the **latest GitHub Release**:

https://github.com/quangtruong2003/YTBAndroidAutoSupport/releases/latest

### Option A — install directly on the Android phone

1. Download the APK from the latest release.
2. Open the APK on the Android phone.
3. Allow installation from that source if Android asks.
4. Open **Fermata Web+** once and grant the permissions it needs.
5. Connect Android Auto / Open Headunit.
6. Open **Fermata Web+** from Android Auto and use the built-in YouTube page.

### Option B — install with ADB

On Windows:

    C:\platform-tools\adb.exe install -r .\YTBAndroidAutoSupport.apk

Then launch it once:

    C:\platform-tools\adb.exe shell am start -n me.aap.fermata.webplus.auto.debug/me.aap.fermata.ui.activity.MainActivity

If a sideloaded app is not shown by Android Auto, check Android Auto's developer settings and enable **Unknown sources** where supported. Availability depends on the Android Auto build and device configuration.

## How to use

1. Open **Fermata Web+** in Android Auto.
2. Open the YouTube section.
3. Sign in to YouTube if you want your account/history/recommendations.
4. Start a video while the vehicle is parked.
5. Use YouTube's own fullscreen/player UI.

When a YouTube video is active, Android Auto media controls are synchronized with Fermata:

- **Play / Pause** controls the current YouTube video element.
- **Next** calls the YouTube player's next action directly.
- **Previous** calls the YouTube player's previous action directly.
- The media title is read from the current YouTube page/video and sent to the Android media session.

## How it works

The main integration path is:

    Android Auto media button
            |
            v
    Fermata MediaSession
            |
            v
    MediaSessionCallback
            |
            v
    YoutubeMediaEngine
            |
            v
    YoutubeWebView
            |
            v
    YouTube HTML5 player / JavaScript

The important difference from upstream behavior is that YouTube Previous/Next are handled by the YouTube-backed media engine itself. They are not converted into synthetic URLs such as http://youtube.com/next, which Android's normal media player cannot play.

For the UI, MainActivityDelegate.setWebUiOnly() and ControlPanelView suppress Fermata's own playback controls while the YouTube WebView owns the screen. This prevents Fermata controls from reappearing during fullscreen transitions.

The recommendation bridge collects visible YouTube recommendation metadata and exposes it as media items on a best-effort basis. Android Auto decides whether and where those items are rendered.

## Tested setup

Current development/testing setup:

- Samsung SM-G990E
- Android 16 / SDK 36
- Android Auto 17.x
- Open Headunit
- ARM64
- Same Android device running both Android Auto and the head-unit receiver

Other devices may behave differently, especially around Android Auto sideload visibility and dashboard media surfaces.

## Build from source

Requirements:

- JDK compatible with the Gradle project
- Android SDK
- Android SDK Platform 36
- ARM64 Android device for the current tested configuration

Windows PowerShell example:

    $env:FERMATA_WEB_ONLY = "1"

    .\gradlew.bat :fermata:packageAutoDebugUniversalApk `
      -PWEB_ONLY=1 `
      -PAPP_ID=me.aap.fermata.webplus `
      -PAPP_NAME="Fermata Web+" `
      -PABI=arm64-v8a `
      --no-daemon

The universal debug APK is generated under:

    fermata/build/outputs/apk_from_bundle/autoDebug/

Install it with:

    C:\platform-tools\adb.exe install -r .\fermata\build\outputs\apk_from_bundle\autoDebug\fermata-2.0.2-auto-debug-universal.apk

## Project status

This is an experimental fork built for a specific Android Auto + Open Headunit workflow. Core YouTube playback and direct media transport integration are the main focus.

Some Android Auto surfaces are controlled entirely by Google/Android Auto and cannot be forced by the app, including exact taskbar icon placement, app recency ordering and whether the dashboard chooses to render a recommendation row.

## Credits

Based on [Fermata](https://github.com/AndreyPavlenko/Fermata) by Andrey Pavlenko.

This repository keeps the upstream license: **GNU General Public License v3.0**. See [LICENSE](LICENSE).
