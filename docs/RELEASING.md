# Releasing

Releases are built by GitHub Actions (`.github/workflows/release.yml`) when a
version tag is pushed. The workflow signs the APK with the project's release key
(kept only in the encrypted secrets of the repository's `release` environment) and attaches
`FoldDesk-vX.Y.Z.apk` plus its SHA-256 checksum to a new GitHub Release.

## Publishing a release

```bash
git checkout main && git pull
git tag v0.2.0            # semantic version; a suffix like v0.2.0-beta.1 makes a pre-release
git push origin v0.2.0
```

The version name comes from the tag; the version code is derived from it
(`1.2.3` → `10203`), so every newer tag installs as an update.
Release notes are generated from the commits/PRs since the previous tag; edit
them on GitHub afterwards if needed.

## The signing key (one-time setup, maintainers only)

Android only installs an update if it's signed with the **same key** as the
installed app. Losing the key means users must uninstall to get new versions;
leaking it lets others publish "updates" as FoldDesk. Keep it out of the repo
and backed up somewhere safe (e.g. a password manager).

1. Create the key (keep it outside the repository):

   ```bash
   keytool -genkeypair -v -keystore ~/folddesk-release.jks -alias folddesk \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=FoldDesk"
   ```

2. Add these secrets to the **`release` environment** (GitHub → Settings →
   Environments → release → Environment secrets), or with the GitHub CLI:

   ```bash
   gh secret set FOLDDESK_KEYSTORE_BASE64 --env release < <(base64 -w0 ~/folddesk-release.jks)
   gh secret set FOLDDESK_KEYSTORE_PASSWORD --env release
   gh secret set FOLDDESK_KEY_ALIAS --env release --body folddesk
   gh secret set FOLDDESK_KEY_PASSWORD --env release
   ```

   The environment can also restrict which tags may deploy (Settings →
   Environments → release → Deployment branches and tags → `v*`).

3. Optional, to build signed releases locally: create `keystore.properties` in
   the project root (git-ignored):

   ```properties
   storeFile=/home/you/folddesk-release.jks
   storePassword=…
   keyAlias=folddesk
   keyPassword=…
   ```

   Without it, local release builds are signed with the debug key.

## Forks

Forks build fine without any secrets (CI uses the debug build). To publish your
own releases, create your own key and secrets as above; your APKs won't install
over the official ones, since they're signed differently.
