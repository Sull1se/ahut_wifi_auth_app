# Release signing on Windows

The release keystore and its password file are local-only files under `signing/`. Git ignores that directory and common keystore extensions. The project scripts never put a password in an argument, Gradle property, file in plaintext, console output, or chat. A short-lived environment variable is used only for the `keytool`/Gradle child process. The scripts remove conflicting process variables for child tools, then restore the caller's original environment exactly, including whether each variable was absent or present with an empty value.

## Create the release key once

Open a local PowerShell terminal in the repository and run:

```powershell
.\scripts\Initialize-ReleaseSigning.ps1
```

Enter a new password twice at the hidden prompt. It must be at least six characters for `keytool`; choose a strong password and keep it private. The script creates `signing/ahut-release.p12` as a PKCS12 keystore with the `ahut-release` alias, RSA-3072 key, and a 10,000-day certificate. It verifies that the alias can be read before writing `signing/release-password.dpapi`.

The password file is protected by Windows DPAPI for the current Windows user. It is useful for later unattended local builds under the same account, but it is not a cross-machine backup and cannot be decrypted by another Windows account. Keep a secure backup of the keystore and remember the password independently. Losing either means this signing identity cannot be recovered. Never replace an existing keystore as part of initialization. If the keystore exists but its DPAPI file must be recreated, use an explicit recovery flow that asks for the existing password; initialization intentionally refuses to overwrite either file.

## Verify or build

From the same repository, run one of:

```powershell
# Run JVM unit tests, release lint, and release compilation without requiring a signing key or packaging an APK.
.\scripts\Build-Release.ps1 -VerifyOnly

# Run JVM unit tests, release lint, and produce the signed Release APK.
.\scripts\Build-Release.ps1
```

To omit checks already completed during the current review, add `-SkipTests`, `-SkipLint`, or both. VerifyOnly does not read signing material and clears any ambient `AHUT_RELEASE_*` signing variables for the child Gradle process. AGP 9 exposes the project's JVM unit tests as `testDebugUnitTest`; this task compiles/runs tests and does not build a Debug APK. Both modes also compile the Release Kotlin variant through either `compileReleaseKotlin` or `assembleRelease`. The default build uses `testDebugUnitTest`, `lintRelease`, and `assembleRelease`, so the delivered APK remains the signed Release artifact. A nonzero Gradle exit code is reported as failure and stops the script. Gradle runs with configuration cache disabled and without a persistent daemon. Its user home reuses `.gradle/user-home`; Android user home and Java/Windows temporary files stay under `.gradle/android-user-home` and `.gradle/tmp`. The script requires the existing `local.properties` SDK path and explicitly disables Gradle's missing-SDK-package auto-download; it does not install or update Android SDK packages.

## Accepted Release (2026-10-02)

The local signing key was initialized and verified. The v1.3.2 formal Release build completed successfully before this public source snapshot was prepared with 80 tasks. It ran 17 JVM tests with 0 failures and 0 errors; `lintRelease` reported 0 errors and 93 warnings. Kotlin compilation emitted an `allNetworks` deprecation warning; Android SDK tooling also warned that it understands SDK XML through v3 while the installed SDK contains v4 metadata. Neither warning prevented the build.

The delivered artifact is `apk_output/ahut-auth-v1.3.2-release.apk` (225,287 bytes), versionName `1.3.2`, versionCode `7`, minSdk 24 and targetSdk 36. R8 shrinking and resource optimization ran, release signing verification and packaging succeeded, and the APK was verified as a v2-signed, non-debuggable Release package. Its RSA-3072 certificate subject is `CN=AHUT Wi-Fi Auth Release`; certificate SHA-256 fingerprint: `7f3f375b050e4c1728c779e8c123035bc503e769fcb7c0738bdd141671199f17`. APK SHA-256: `6F1B5DC7EB5393971398EF7DBE7CF4BBECCB1BF8C872F0B479FA951A06A7CB72`. This records the verified v2 signature; no v1 or v3 signature is claimed. No physical-device or campus-network interaction has been verified yet; pending checks are listed in [ui_adaptation.md](ui_adaptation.md).

## Failure and scope behavior

Initialization fails if the keystore or DPAPI file already exists, the password entries differ or are too short, `keytool` fails, or the resulting alias cannot be read. A partially created keystore is removed only when this invocation created it. Build fails before invoking Gradle if the expected keystore, DPAPI file, wrapper, or local SDK configuration is missing. DPAPI decryption is tied to the same Windows user that initialized the key. A failed verification or build never counts as a successful APK delivery.

The Gradle build always uses the configured release signing config. The `verifyReleaseSigning` gate requires all four process environment variables—`AHUT_RELEASE_STORE_FILE`, `AHUT_RELEASE_KEY_ALIAS`, `AHUT_RELEASE_STORE_PASSWORD`, and `AHUT_RELEASE_KEY_PASSWORD`—before Release packaging; it never falls back to the debug signing key. Unit tests, lint, and Kotlin compilation can run without signing credentials. The initialization script creates the key only when explicitly run; the build script does not generate or replace signing material. For future signed builds on this Windows account, retain the existing local key and DPAPI file, then run `.\scripts\Build-Release.ps1` from the repository root.

References: [Oracle JDK 17 keytool](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html), [PowerShell ConvertFrom-SecureString](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/convertfrom-securestring?view=powershell-7.6), [Gradle 9.1 command-line options](https://docs.gradle.org/9.1.0/userguide/command_line_interface.html).
