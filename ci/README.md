# Signing

The release APK must be signed with a **stable** key. In-app self-update
(spec 19) replaces an existing install, and Android refuses that if the
signing certificate differs — silently, with no error the user can see.

## How this repo signs

`.github/workflows/build.yml` prefers repository secrets, and falls back to the
keystore checked in here:

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

If `KEYSTORE_BASE64` is unset, the build signs with `ci/taskmind-release.jks`
(passwords in the repo's `gradle.properties`). That is deliberate: it means a
fresh clone produces an installable, self-updatable APK with no setup at all,
which matters when there is no development machine to generate a key on.

**The trade-off:** the fallback key is public, so anyone who can read this repo
can build an APK that Android will accept as an update to this install. For a
personal sideloaded build that is usually fine. It is not fine for anything you
publish or share.

## Switching to your own key

You need a machine with a JDK once, or a GitHub Actions run:

```bash
keytool -genkeypair -v \
  -keystore taskmind-release.jks -storetype JKS \
  -alias taskmind -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<store-pass>' -keypass '<key-pass>' \
  -dname "CN=Your Name, O=Personal, C=IN"

base64 -w0 taskmind-release.jks    # paste into the KEYSTORE_BASE64 secret
```

Add the four secrets under Settings → Secrets and variables → Actions, then
delete `ci/taskmind-release.jks` from the repo.

**Do this before your first install if you are going to do it at all.** Once an
APK is installed, switching keys means uninstalling first, which loses the
database.
