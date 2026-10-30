# Stubs and Unimplemented Features

## P0 - Critical (blocks daily-driver use)

### 1. HermesClient - Hardcoded localhost URL
**File:** `app/src/main/java/com/aetheria/aura/net/HermesClient.kt:13`
**Issue:** `const val AURA_HOST = "http://localhost:8766/command"` - won't work on device (needs Tailscale IP or configurable endpoint)
**Status:** STUBBED

### 2. ActionExecutor - Doesn't actually launch apps
**File:** `app/src/main/java/com/aetheria/aura/actions/ActionExecutor.kt:34-36`
**Issue:** Comment says "In a real implementation, we'd start the intent" - just returns "confirm" without launching
**Status:** STUBBED

### 3. WakeWordService - Empty implementation
**File:** `app/src/main/java/com/aetheria/aura/wakeWord/WakeWordService.kt`
**Issue:** Service exists but has no wake word detection logic (Porcupine or similar)
**Status:** STUBBED

### 4. BootReceiver - TODO not implemented
**File:** `app/src/main/java/com/aetheria/aura/BootReceiver.kt:16`
**Issue:** `// TODO: Start WakeWordService here in step 3`
**Status:** STUBBED

### 5. No unit tests exist
**File:** (none)
**Issue:** No `*Test*.kt` files found in project
**Status:** MISSING

### 6. Manifest: android:usesCleartextTraffic="true"
**File:** `app/src/main/AndroidManifest.xml:31`
**Issue:** Allows HTTP traffic; HermesClient uses HTTP. Should use HTTPS or configure network security config.
**Status:** SECURITY ISSUE

### 7. ActionExecutor - Package iteration on IO thread but loads labels synchronously
**File:** `app/src/main/java/com/aetheria/aura/actions/ActionExecutor.kt:25-30`
**Issue:** `pm.getInstalledPackages(0)` + `loadLabel(pm)` for each package - could be slow on devices with many apps
**Status:** PERFORMANCE RISK

---

## P1 - High (should fix before release)

### 8. No network security config for cleartext traffic exception
**Issue:** If HTTP is needed for local Hermes bridge, should use `network_security_config.xml` with domain-specific exception instead of global `usesCleartextTraffic`

### 9. ProGuard rules are empty/default
**File:** `app/proguard-rules.pro`
**Issue:** No rules for reflection, serialization, or keeping Compose/runtime classes

### 10. No lint baseline or configuration
**Issue:** `./gradlew lint` not configured; no `lint.xml` or baseline

### 11. No detekt/ktlint configuration
**Issue:** Code style not enforced

---

## P2 - Medium (nice to have)

### 12. AuraHomeScreen - Clock updates every 15 seconds via LaunchedEffect
**File:** `app/src/main/java/com/aetheria/aura/avatar/AuraHomeScreen.kt:48`
**Issue:** `delay(15_000)` - could use time tick broadcast receiver instead

### 13. OrbRenderer / AvatarState / AvatarRenderer - Unused parallel implementation
**Files:** `OrbRenderer.kt`, `AvatarState.kt`, `AvatarRenderer.kt`
**Issue:** Two separate avatar systems exist (AuraFigure + OrbRenderer). Which one is used? AuraHomeScreen uses AuraFigure.

### 14. AvatarDebugScreen - Debug screen not integrated
**File:** `app/src/main/java/com/aetheria/aura/avatar/debug/AvatarDebugScreen.kt`
**Issue:** Exists but no way to access it from the main UI

### 15. No version catalog (libs.versions.toml)
**Issue:** Versions hardcoded in build.gradle.kts and gradle.properties

### 16. No Gradle version catalog for dependencies
**Issue:** Dependencies declared inline in build.gradle.kts

---

## Design Intent (NOT stubs - per user requirements)

- Single search/command field ✓ (AuraHomeScreen)
- Central AI orb (AuraFigure) ✓ - idle/listening/thinking/acting states implemented
- Termux + Shizuku access - NOT IMPLEMENTED (no AccessibilityService, no Shizuku bridge)
- NVIDIA NIM fallback / local model primary - NOT IMPLEMENTED (HermesClient only talks to localhost)
- HOME/DEFAULT launcher intent filters ✓ (in manifest)
- Video clips for orb states - NOT IMPLEMENTED (using procedural Canvas animation instead)