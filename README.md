# Calculator Vault

A privacy utility for Android that hides a secure vault behind a fully functional
calculator. To anyone glancing at the phone it is an ordinary four‑function
calculator; entering a secret code and pressing `=` opens a PIN gate that unlocks
the vault.

> **Status:** foundational scaffold (APP-143). The architecture skeleton, build,
> and CI are in place. Final screens, colors, and the vault data model are
> intentionally deferred until the approved wireframe (APP-142) lands.

## Tech stack

- **Language:** Kotlin 2.1.0
- **UI:** Jetpack Compose (Material 3), single-Activity + Navigation Compose
- **Build:** Gradle 8.11.1 (wrapper), AGP 8.7.3, version catalog (`gradle/libs.versions.toml`)
- **SDK:** min 24, target/compile 35
- **Secure storage:** AndroidX Security `EncryptedSharedPreferences` (vault seam)
- **Static analysis:** ktlint · Android Lint
- **Tests:** JUnit4 · Truth · Turbine · coroutines-test

## Architecture (skeleton)

```
calculator (disguise)  --secret code-->  pin (gate)  --auth-->  vault home
```

| Package        | Responsibility                                                            |
| -------------- | ------------------------------------------------------------------------- |
| `calculator`   | The disguise. `CalculatorEngine` (pure eval), `SecretCodeDetector` (the   |
|                | single seam to the vault), `CalculatorViewModel`, `CalculatorScreen`.     |
| `pin`          | PIN gate placeholder (`PinEntryScreen`).                                   |
| `vault`        | `VaultRepository` boundary + `EncryptedVaultRepository` reference impl;    |
|                | `VaultHomeScreen`. **Schema deliberately not locked.**                    |
| `navigation`   | `VaultNavHost` + `VaultDestinations` route table.                         |
| `ui/theme`     | Single-accent theme hook (`CalculatorVaultTheme`).                        |

The calculator ↔ vault boundary lives entirely in `SecretCodeDetector` and the
navigation host, so it is small and auditable. The vault storage contract is an
`interface` so the encryption scheme can change without touching the UI.

## Build & run

Requires JDK 17 and the Android SDK (compileSdk 35). From the repo root:

```bash
# Lint + static analysis
./gradlew ktlintCheck lintDebug

# Unit tests (calculator engine, secret detector, view model)
./gradlew testDebugUnitTest

# Assemble a debug APK
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug
```

CI (`.github/workflows/ci.yml`) runs ktlint, Android Lint, unit tests, and a debug
assemble on every pull request.

## What's intentionally out of scope here

Final screens, layouts, colors, thumbnails, and empty states — those come from the
approved wireframe deck (APP-142). The secret-code convention, PIN throttling,
biometric fallback, and the vault item/media model are decided in later milestones.
