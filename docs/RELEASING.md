# Releasing (maintainers)

## Versioning

One version for the whole project, following [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- The single source of truth is `"version"` in [`buildspec.json`](../buildspec.json). The plugin's CMake build and the Android build both read it, and CI derives everything else from it.
- Android `versionName` is that version; `versionCode` is `MAJOR × 10000 + MINOR × 100 + PATCH` (so `1.4.2` is `10402`).
- Bump MAJOR when the [protocol](PROTOCOL.md) changes in a way older apps or plugins can't follow, MINOR for new features, PATCH for fixes. List changes in [CHANGELOG.md](../CHANGELOG.md).

| Thing | Value |
| --- | --- |
| Release tag | `v<version>`, for example `v0.1.0` |
| Release title | `OBS Director <version>` |
| App | `OBS-Director-<version>.apk` |
| Plugin (Windows only) | `OBS-Director-<version>-Windows-Setup.exe` |
| Marked latest | every release; a pre-release (`0.2.0-beta1`) never is |

## To release

Change `"version"` in `buildspec.json`, add its section to `CHANGELOG.md`, and merge to `main`. The push to `main` builds everything and publishes `v<version>`, marked latest; the app on everyone's phone then offers it. Pushing `main` again without a bump replaces that release's files. A pre-release is never offered by the update check.

## Android signing

Release APKs must always be signed with the same key, or updates won't install over each other. The key lives only in the repository's Actions secrets, never in the repository:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Pull requests build without them, as a debug app. A push to `main` refuses to publish without them, because an APK signed with any other key would not install over earlier versions.

To make a key (only once; keep the file and passwords somewhere safe and offline, since losing them means everyone has to uninstall to get updates):

```bash
keytool -genkeypair -v -keystore release.jks -alias <alias> -keyalg RSA -keysize 4096 -validity 36500
```

```bash
base64 -w0 release.jks
```

Paste the second command's output into `ANDROID_KEYSTORE_BASE64`, then delete any copy of the key from the working tree. `.gitignore` blocks `*.jks`, `*.keystore` and other key files from being committed.

## Windows installer

The plugin is built for Windows only. [`cmake/windows/resources/installer.iss`](../cmake/windows/resources/installer.iss) (Inno Setup 6) installs it in OBS Studio 33's plugin layout, `C:\ProgramData\obs-studio\plugins\obs-director\obs-director.dll` with `data\` beside it. It also removes copies from the legacy locations (Program Files, and the old `bin\64bit` layout), which OBS stops loading in version 34. It refuses to install over an OBS older than 33.

Keep the installer's `AppId` unchanged: Windows uses it to match upgrades and the uninstaller.

The installer is not code-signed yet, so Windows SmartScreen warns on first run. To sign it, add a code-signing certificate as Actions secrets and a `signtool` step after `Package-Windows.ps1`.
