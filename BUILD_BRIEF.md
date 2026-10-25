# Aura Launcher Build Brief

## Build Order

1. **Orb avatar + debug screen**
2. **Shell<->Hermes contract** (HTTP over Tailscale, JSON action list, confirm dialog for call/SMS/settings)
3. **Wake word** (Porcupine behind a WakeWordEngine interface, foreground service)
4. **Launcher mode** (HOME intent filter, "Switch launcher" escape button, test with "Just once")

## Rules

- Each step ends with a CI-built APK and raw CI log tail.
- No step starts until I approve the previous one.
- GitHub Actions does all building (no local Gradle/Android SDK).
- Raw output only, no summaries.
- One step per turn.# Build trigger
