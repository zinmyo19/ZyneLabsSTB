# ZyneLabs STB

A simple Stalker portal TV player for Android phones and Android TV boxes.
Enter your provider's Stalker portal URL and box MAC address, browse the
channel list, and play streams with Media3 ExoPlayer.

Built by [ZyneLabs](https://github.com/zinmyo19). All code here is original.

## Features (v1)

- Stalker portal settings (portal URL + MAC), saved locally on device
- Stalker Middleware API client: handshake, get_profile, get_all_channels,
  create_link (auto re-handshake on token expiry)
- Channel list (name + number), fully D-pad navigable with visible focus
- Media3 ExoPlayer playback with standard controls (D-pad OK toggles them)
- Android TV support: Leanback launcher entry + banner, D-pad throughout
- Dark theme with ZyneLabs teal accent (#00E5CC)
- English-only UI

## Configure

1. Install the app and open it.
2. Enter your provider's **Stalker portal URL**
   (e.g. `http://example.com:8080/c`) and your box **MAC address**
   (`00:1A:79:XX:XX:XX`).
3. Tap **Connect**. The channel list loads after a successful handshake.

No portal URLs, MACs, or credentials are bundled with the app — you
configure your own. Use only portals you are entitled to use.

## Build

Requirements: Android Studio Ladybug or newer (or JDK 17 + Gradle 8.7).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper jar is not committed; Android Studio generates it on
first open, or run `gradle wrapper` with a local Gradle install.

## Project layout

```
app/src/main/java/com/zynelabs/stb/
  MainActivity.kt        portal settings + connect
  ChannelListActivity.kt channel list (RecyclerView, D-pad)
  PlayerActivity.kt      ExoPlayer playback
  StalkerApi.kt          Stalker portal.php client (OkHttp)
  Channel.kt             channel data class
  Prefs.kt               SharedPreferences helpers + validation
```

## Notes

- v1 loads channel logos? No — name + number only, to keep v1 dependency-free.
- Some portals require specific User-Agent or Referer headers; see
  `StalkerApi.kt` if your portal rejects the handshake.

## License

MIT — see LICENSE (to be added).
