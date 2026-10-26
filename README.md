# apphound

A private app store for your own Android apps. APKs live in a private S3 bucket. A small Lambda
API lists them with short-lived download links, and the apphound Android app installs and updates
them over the air. Once the store has installed an app, Android 12+ lets it update that app
without asking for confirmation.

```
scripts/publish.py ──▶ S3: apps/<pkg>/<versionCode>.apk + manifest.json
                              │
apphound app ──GET /apps──▶ Lambda (Function URL, bearer token) ──▶ presigned URLs (15 min)
     └─ download ▶ SHA-256 check ▶ PackageInstaller (no prompt when the store owns the app)
```

## Layout

| Path | What |
|---|---|
| `template.yaml`, `samconfig.toml` | SAM stack `apphound` in eu-central-1: bucket, Lambda, Function URL |
| `src/apphound_api/handler.py` | API: `GET /apps`, `GET /apps/{packageName}` |
| `scripts/deploy.sh` | Deploys and writes the local `.env` (bucket, API URL, token) |
| `scripts/publish.py` | Uploads an APK and updates the manifest |
| `infra/iam/` | One-time setup of the scoped `apphound-deployer` IAM user |
| `android/` | The store app (Kotlin, Compose) |

## Setup (one time)

```bash
infra/iam/setup.sh      # with admin creds: creates apphound-deployer + the `apphound` AWS profile
scripts/deploy.sh       # deploys with the scoped profile, creates the API token, writes .env
```

The deployer can only touch `apphound-*` resources in eu-central-1. Any role it creates carries the
`apphound-boundary` permissions boundary.

Secrets stay local and are git-ignored:
- `.env` holds the API URL and token. The Android build reads it into `BuildConfig`.
- `android/keystore.properties` points to the release key, which lives outside the repo. **Back
  up that key and its password.** Android refuses an update signed with a different key.

## Publishing an app

```bash
uv run scripts/publish.py path/to/app-release.apk --changelog "What changed"
```

The script reads the package, version and label from the APK. It refuses to publish when the
`versionCode` isn't higher than the published one (`--force` overrides that) or when the signing
certificate has changed. Adaptive (XML) launcher icons can't be extracted; pass `--icon icon.png`
for those.

## Building the store app

```bash
cd android && ./gradlew assembleRelease -PversionCode=3
uv run scripts/publish.py android/app/build/outputs/apk/release/app-release.apk
```

The store lists itself, so it updates through itself too. The first install has to be done by
hand (adb, or download the APK). After that, everything goes through the store.

Building needs a JDK. Android Studio's bundled one works:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

## How installs and updates work

- **Detecting updates:** the store compares each app's installed `versionCode` with the API's.
- **Downloading:** the store fetches a fresh presigned URL, downloads the APK, and checks its
  SHA-256 against the manifest.
- **Installing:** it uses a `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED`
  (Android 12+) and `setRequestUpdateOwnership` (Android 14+).
- **When there's no prompt:** for updates to apps the store installed itself (it is the
  "installer of record"). For apps installed some other way, Android shows one confirmation
  dialog, after which the store owns them.

## Roadmap

- [x] Backend: S3 + Lambda API + publish script
- [x] Store app: list, install, update, update all
- [ ] Background update checks (WorkManager) with notifications
- [ ] Silent auto-install in the background, with a per-app toggle
- [ ] Push trigger (FCM) when a new version is published
- [ ] CI publishing from GitHub Actions (OIDC, no stored keys)
