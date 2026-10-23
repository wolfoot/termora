# Termora for Android

[中文说明](README.zh_CN.md)

A standalone Android application alongside the desktop project. Requires Android 9 or newer. The default APK targets ARM64 phones; x86_64 is available for emulator testing. USB serial support is excluded.

Implemented features:

- SSH terminal with the desktop ANSI parser, password/public-key/keyboard-interactive authentication, ordered jump hosts, login scripts, environment variables and startup commands.
- SFTP browsing, document-picker upload/download, UTF-8 text editing, rename/delete, chmod and remote ZIP/tar compression/extraction.
- Local and remote SSH forwarding and SOCKS5 CONNECT, with a foreground connection service and an explicit disconnect action.
- Host folders, tags, search, snippets and byte-compatible desktop macros.
- Android Keystore authenticated local storage; RSA, Ed25519 and ECDSA keys; import/export and encrypted backups.
- Termora account login and synchronization using the desktop browser callback/API/encryption contract. WebDAV and GitHub Gist sync use desktop `termora-pbe-v2` files and timestamp/deletion merging.
- S3, OSS, COS, OBS, HTTPS WebDAV, FTP and SMB file clients. Object storage profiles specify a bucket and HTTPS endpoint. S3 supports path-style and virtual-host addresses; OSS uses V4 authentication.
- VNC rendered in the app, with touch, pan/zoom, drag, right click, keyboard and clipboard support.
- **Embedded RDP** through FreeRDP 3.32.2, with bitmap rendering, touch/mouse, virtual-key and Unicode input, clipboard and Android hardware H.264 decoding. It does not launch another RDP app.
- Remote CPU/memory/load, filesystem usage and NVIDIA GPU snapshots over SSH.
- Monospaced terminal rendering with adjustable font size and extra letter spacing (default: zero).

## Build

The Android build is independent of the desktop Gradle build. Use a Linux x86_64 build host with JDK 17, Python 3, CMake/Ninja, Android SDK platform 36 and NDK 28.2.13676358. Set `ANDROID_HOME`, or use the default `$HOME/Android/Sdk`. Put `sdk.dir` in an untracked `android/local.properties` if necessary.

```sh
cd android
export JAVA_HOME=/path/to/jdk17
./scripts/build-rdp.sh arm64-v8a
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

The developer debug APK is `app/build/outputs/apk/debug/app-debug.apk`. For the locally signed, non-debuggable test delivery, run `./gradlew :app:assembleRelease -PtermoraTestSigning=true`; its APK is `app/build/outputs/apk/release/app-release.apk`. Without this explicit test-signing flag, release output is unsigned so a publishing owner can supply their own signing configuration. Native source/dependency archives and compiled libraries are ignored by Git. The build script pins and checksums the FreeRDP archive; the upstream CMake superbuild checksums its dependencies. ARM64 native libraries use 16 KiB ELF segment alignment.

Optional emulator ABI:

```sh
./scripts/build-rdp.sh x86_64
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PtermoraAbis=arm64-v8a,x86_64
```

The core module generates guarded portable adaptations of the existing desktop terminal and data models at build time. It includes neither Swing/AWT nor desktop plugin JARs. Platform adapters are in `scripts/prepare-core.py`; the FreeRDP Java bridge adapter is in `scripts/prepare-rdp.py`.

## Validation

`./gradlew :core:test` covers ANSI/alternate buffers and resizing, desktop sync compatibility and authenticated encryption, all three key types, XML safety and path checks, the AWS published signing example, COS signatures against the official SDK, HTTPS WebDAV and all four object storage clients, real SSH password/key/jump-host connections, SFTP/chmod/archive paths, and local/remote/SOCKS forwarding.

Device tests are intended only for disposable emulators. They exercise Keystore ciphertext, FreeRDP JNI loading and connection argument parsing, Ed25519/Chinese ANSI handling, actual SSH/SFTP over the emulator's host network, and the terminal screen.

```sh
./gradlew :core:writeFixtureClasspath :app:assembleDebug :app:assembleDebugAndroidTest -PtermoraAbis=arm64-v8a,x86_64
java -cp "$(cat core/build/fixture-classpath.txt)" app.termora.android.core.ProtocolFixture /tmp/termora-device-fixture.json
# In another terminal, with a disposable emulator running:
python3 scripts/run-device-tests.py --serial emulator-5580
```

The fixture listens only on loopback. Stop its process when finished.

## Runtime boundaries

- Verified locally on an Android 9 x86_64 emulator. Physical ARM64 phone behavior, a complete connection to a Windows RDP server, production cloud accounts, FTP/SMB servers and NVIDIA hardware require their corresponding environments and are not claimed as verified.
- VNC supports RFB 3.3/3.7/3.8, no-auth or classic VNC password authentication, Raw/CopyRect and desktop resize. Servers requiring proprietary authentication or VeNCrypt are not supported; use an SSH jump host for protected transport.
- Object storage currently uses ordinary bucket operations and single-request streaming uploads. Resumable/multipart uploads and provider-specific advanced APIs are not implemented. Configure Android bucket/endpoint fields when importing desktop profiles that only stored account credentials.
- Text editing is UTF-8 and limited to 1 MiB. Archive operations require `tar`, `zip` or `unzip` on the SSH server. Directory deletion currently follows each provider's ordinary empty-directory/marker semantics.
- Macro recording is limited to 1 MiB. Private-key import supports PKCS#8 PEM (including encrypted PKCS#8), RSA PEM, and unencrypted OpenSSH keys supported by the parser. An encrypted OpenSSH key can be converted to supported PEM before importing.
- Sync authenticates every selected encrypted file before applying changes. Legacy plaintext/CBC third-party sync files are deliberately rejected; migrate them using the desktop sync plugin first. Desktop-only keymap/highlight files are preserved without loading their UI or plugins.
- Foreground sessions remain subject to Android/OEM background restrictions. SOCKS forwarding does not install a system-wide VPN or configure other phone apps automatically.

The application source follows the desktop project's AGPL-3.0 option. FreeRDP native components use Apache-2.0 and its adapted Java bridge uses MPL-2.0. License texts and dependency notices are bundled in `app/src/main/assets/licenses`.
