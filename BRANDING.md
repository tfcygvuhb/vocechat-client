# note笔记 Android 定制说明

- Android applicationId / namespace: `com.note.notebook`
- Kotlin package: `com.note.notebook`
- App display name: `note笔记`
- Deep link scheme: `notechat://`
- Default server: build-time `--dart-define=DEFAULT_SERVER_URL=...`; empty by default, users can enter their own VoceChat server.
- Theme: WeChat-style green `#07C160`, light gray background, green launcher/splash tool icon.
- Copyright: `© 2026 note笔记。保留所有权利。`

## Signing

Generate your own new upload key with `scripts/generate_android_keystore.sh` or `scripts/generate_android_keystore.ps1` (requires JDK/keytool). Keep the keystore private and do not commit it.

Create `android/key.properties` (do not commit) or set env vars:

```properties
storeFile=../release/note-upload-key.jks
storePassword=change-me
keyAlias=note
keyPassword=change-me
```

Build locally:

```bash
flutter pub get
flutter build apk --release --dart-define=DEFAULT_SERVER_URL=https://your-server.example.com
```

## GitHub Releases

See `.github/workflows/android-release.yml`. Add these repository secrets for a stable signed APK:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then push a tag like `v1.0.0` to build and upload the APK to GitHub Releases. If no keystore secret is configured, the workflow creates a CI keystore, but that is not recommended for long-term upgrade compatibility.
