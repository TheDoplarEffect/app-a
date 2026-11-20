# app-a

Android app (package `com.example.appa`) built and shipped from the TrueNAS devbox.

**MediaSeer** — a Jellyseerr-style media browser in Kotlin: three tabs
(📺 Series / 🎥 Movies / 🎧 Audiobooks), global search (titles, genres, authors,
magnet names), a detail screen per title with a magnet list (copy to clipboard)
and a request flow with live status monitoring (pending → processing → available).

🧪 **Placeholder mode:** Sonarr / Radarr / TMDB are not connected yet, so all
titles, posters (drawn on canvas), magnets and statuses are generated
deterministically in-app (`Catalog.kt`). Requests persist in SharedPreferences.

Ship loop: push to master -> .github/workflows/release.yml signs assembleRelease with the
KEYSTORE_* Actions secrets and attaches the APK to a private GitHub Release -> Obtainium
on the phone updates from that release (same signing key every time).

Local: container app-a on devbox (ssh -p 2222 dev@192.168.178.250), SDK at /opt/android-sdk,
AVD ci, emulator headless, adb on PATH via /opt/android-sdk/platform-tools.

## Layout

```
app/src/main/java/com/example/appa/
├── MainActivity.java      # tiny Java entry shim -> MainScreenKt.showMainScreen()
├── Catalog.kt             # PURE Kotlin: models, placeholder catalog, magnets, RequestStoreCore, StatusRules
├── AppState.kt            # singletons (catalog + SharedPreferences-backed store)
├── Ui.kt                  # colors/helpers, Context.dp/pillBg extensions
├── PosterView.kt          # canvas-drawn placeholder poster
├── MainScreen.kt          # 3 tabs + search + poster grid
├── DetailActivity.kt      # metadata + request button + magnet list with copy
└── RequestsActivity.kt    # live request list, status pills, remove
app/src/test/java/...      # JVM unit tests for the pure core (./gradlew test)
```

No XML layouts / no R class / no androidx deps — the UI is fully programmatic,
which keeps the build dependency-light.

## Design system (v4.0, "crystal glass")
Atomic structure — swapping the design system later only means swapping tokens:

- `Tokens.kt` (pure Kotlin, JVM-tested): ALL design values — colors, type scale,
  spacing, radii, motion durations, elevation — in `DesignTokens` + `AppTheme.current`.
  Two themes ship: `CrystalGlassDark` (default) and `PorcelainLight`.
  WCAG contrast is unit-tested (AAA for primary text).
- `Density.kt` / `Insets.kt` / `Motion.kt` / `Glass.kt` / `AuroraBackground.kt`:
  platform glue — dp/sp, edge-to-edge + display-cutout safe-area padding,
  animation helpers (stagger, pop, crossfade, activity transitions),
  translucent glass surfaces, animated aurora backdrop.
- `Components.kt`: the atoms — AppTopBar, BottomNavBar (animated pill),
  PrimaryButton/SecondaryButton/DangerButton, CircleIconButton (badge),
  SearchField (debounced), GlassCard, PosterCard, Chip/StatusPill,
  Skeleton, EmptyState, MagnetRow, FormField/SwitchRow.
  Each reads `AppTheme.current`; none hard-codes a value.
- Screens (`MainScreen`, `DetailActivity`, `RequestsActivity`, `SettingsActivity`)
  are organisms composed only from atoms.
