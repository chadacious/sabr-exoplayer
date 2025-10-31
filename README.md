# sabr-exoplayer

An Android library that integrates YouTube's SABR (Server-Managed Adaptive Bitrate) playback
strategy with [ExoPlayer](https://exoplayer.dev). It includes manifest parsing, request/session
management, protos, a default SABR data source/fetcher implementation, and a sample app that can be
run directly on an emulator/device.

## Modules

- **:sabr-exoplayer** – the reusable library module containing the SABR implementation (manifest
  parser, session manager, session state, SABR data source/fetcher, protobuf models, logging
  utilities, etc.).
- **:sample** – a minimal app demonstrating the library. Launch it on an emulator/device to play the
  included sample SABR DASH manifest via ExoPlayer.

## Features

- Parses SABR DASH manifests (including `SupplementalProperty` SABR payloads).
- Tracks session state (SABR contexts, request metadata, playback cookies, buffered ranges).
- Provides a custom `DataSource.Factory` and `DefaultSabrSegmentFetcher` to intercept SABR stream
  requests and negotiate follow-up/fallback segments with the backend.
- Includes the protos (lite) required to model SABR requests/responses, compiled with protoc 3.24.4.
- Ships a sample manifest and a demo app to verify SABR playback end-to-end.

## Requirements

- Android Gradle Plugin 8.1+
- Kotlin 1.9+
- minSdk 21, targetSdk 34
- JDK 17 for building (the sample app and library both assume JDK 17 and set `org.gradle.java.home`
  accordingly).

## Build

```bash
./gradlew :sabr-exoplayer:assembleDebug   # Build the library
./gradlew :sample:assembleDebug           # Build the demo app
```

The sample APK (`sample/build/outputs/apk/debug/`) can be installed on an emulator/device. It plays
a bundled SABR DASH manifest using the library.

## Publish / Consume

For development you can include the module as a local project. For production, add publishing logic
(e.g., Maven publishing) to push artifacts to Maven Central or GitHub Packages. Once published you
can use the dependency from another project, for example:

```groovy
dependencies {
    implementation 'com.yourorg:sabr-exoplayer:<version>'
}
```

## Getting Started (Sample App)

1. Install the APK or run the `:sample` app from Android Studio.
2. The sample app loads `sabr/sample_sabr_manifest.mpd` and plays it via ExoPlayer using the library.

## Directory Structure

```
sabr-exoplayer/
├── sabr-exoplayer/             # library module
│   ├── src/main/java/com/googlevideo/sabr/...
│   ├── src/main/proto/com/googlevideo/sabr/...
├── sample/                    # demo app module
│   ├── src/main/java/com/googlevideo/sabr/sample/...
│   └── src/main/assets/sabr/sample_sabr_manifest.mpd
├── build.gradle
├── gradle.properties
├── settings.gradle
└── README.md
```

## License

Add an appropriate license file (e.g., Apache 2.0, MIT) here before publishing.
