# Avatar Flight Launch Charge HUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a temporary launch-charge bar above the avatar-flight pitch/speed HUD while the transformed player is grounded and holding the charged launch input.

**Architecture:** Extend the existing `AvatarFlightHudSystem` -> `AvatarFlightHudViewModel` -> `AvatarFlightHudBinder` -> `TameworkAvatarFlightHud.ui` path. `AvatarFlightInputComponent` remains the source of launch charge timing; no new HUD, image asset, or movement behavior is introduced.

**Tech Stack:** Java ECS systems/components, Hytale custom UI assets, JUnit 5 architecture/value tests, Maven wrapper, Tamework docs/changelog.

---

## File Structure

- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java`
  - Owns immutable launch-charge render fields and ratio clamping.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java`
  - Reads `AvatarFlightInputComponent` and computes launch bar visibility/progress.
- Modify `src/main/java/com/alechilles/alecstamework/Tamework.java`
  - Passes `avatarFlightInputComponentType` into `AvatarFlightHudSystem`.
- Modify `src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java`
  - Binds dynamic anchors for launch fill and threshold marker.
- Modify `src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui`
  - Adds the transparent launch-charge row above pitch/speed/Vigour.
- Modify tests:
  - `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModelTest.java`
  - `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystemArchitectureTest.java`
  - `src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java`
- Modify docs:
  - `docs/Avatar-Flight.md`
  - `CHANGELOG.md`
  - `docs/agents/generated-index.md` if the generated index reports stale.

## Task 1: Add Launch Charge Fields To The HUD View Model

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModelTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java`

- [ ] **Step 1: Write failing view-model tests**

Add these test methods to `AvatarFlightHudViewModelTest`:

```java
@Test
void hiddenModelClearsLaunchChargeValues() {
    AvatarFlightHudViewModel model = AvatarFlightHudViewModel.hidden();

    assertFalse(model.launchChargeVisible());
    assertEquals(0.0, model.launchChargeRatio(), EPSILON);
    assertEquals(0.0, model.launchMinChargeRatio(), EPSILON);
}

@Test
void visibleModelClampsLaunchChargeValues() {
    AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
            0.5,
            0.75,
            Math.toRadians(3.0),
            2.25,
            6.0,
            false,
            "FAST_FLIGHT",
            true,
            1.25,
            -0.5
    );

    assertTrue(model.launchChargeVisible());
    assertEquals(1.0, model.launchChargeRatio(), EPSILON);
    assertEquals(0.0, model.launchMinChargeRatio(), EPSILON);
}

@Test
void launchChargeCanBeHiddenWhileHudRemainsVisible() {
    AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
            0.5,
            0.75,
            0.0,
            2.25,
            6.0,
            false,
            "FAST_FLIGHT",
            false,
            0.6,
            0.2
    );

    assertTrue(model.visible());
    assertFalse(model.launchChargeVisible());
    assertEquals(0.6, model.launchChargeRatio(), EPSILON);
    assertEquals(0.2, model.launchMinChargeRatio(), EPSILON);
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudViewModelTest test
```

Expected: compilation fails because `launchChargeVisible`, `launchChargeRatio`, `launchMinChargeRatio`, and the new `visible overload` overload do not exist.

- [ ] **Step 3: Extend the view-model record**

Change the record declaration in `AvatarFlightHudViewModel.java` to include launch fields after `rechargeMode`:

```java
public record AvatarFlightHudViewModel(boolean visible,
                                       double speedRatio,
                                       double targetSpeedRatio,
                                       double pitchDegrees,
                                       double vigourCharges,
                                       double maxVigourCharges,
                                       boolean dimmed,
                                       @Nonnull String rechargeMode,
                                       boolean launchChargeVisible,
                                       double launchChargeRatio,
                                       double launchMinChargeRatio) {
```

Update the canonical constructor so hidden models clear launch data and visible models clamp ratios:

```java
public AvatarFlightHudViewModel {
    if (!visible) {
        speedRatio = 0.0;
        targetSpeedRatio = 0.0;
        pitchDegrees = 0.0;
        vigourCharges = 0.0;
        maxVigourCharges = 0.0;
        dimmed = false;
        rechargeMode = RECHARGE_MODE_NONE;
        launchChargeVisible = false;
        launchChargeRatio = 0.0;
        launchMinChargeRatio = 0.0;
    } else {
        speedRatio = clamp01(speedRatio);
        targetSpeedRatio = clamp01(targetSpeedRatio);
        pitchDegrees = finiteOrZero(pitchDegrees);
        maxVigourCharges = clamp(finiteOrZero(maxVigourCharges), 0.0, MAX_DISPLAY_PIPS);
        vigourCharges = clamp(finiteOrZero(vigourCharges), 0.0, maxVigourCharges);
        rechargeMode = normalizeRechargeMode(rechargeMode);
        launchChargeRatio = clamp01(launchChargeRatio);
        launchMinChargeRatio = clamp01(launchMinChargeRatio);
    }
}
```

Update `hidden()`:

```java
@Nonnull
public static AvatarFlightHudViewModel hidden() {
    return new AvatarFlightHudViewModel(false, 0.0, 0.0, 0.0, 0.0, 0.0, false,
            RECHARGE_MODE_NONE, false, 0.0, 0.0);
}
```

Keep the existing two `visible overload` overloads by forwarding launch values as hidden/zero:

```java
@Nonnull
public static AvatarFlightHudViewModel visible(double speedRatio,
                                               double charges,
                                               double maxCharges,
                                               boolean groundedAtFull,
                                               @Nullable String rechargeMode) {
    return visible(speedRatio, speedRatio, 0.0, charges, maxCharges, groundedAtFull, rechargeMode);
}

@Nonnull
public static AvatarFlightHudViewModel visible(double speedRatio,
                                               double targetSpeedRatio,
                                               double pitchRadians,
                                               double charges,
                                               double maxCharges,
                                               boolean groundedAtFull,
                                               @Nullable String rechargeMode) {
    return visible(speedRatio, targetSpeedRatio, pitchRadians, charges, maxCharges,
            groundedAtFull, rechargeMode, false, 0.0, 0.0);
}
```

Add the new overload:

```java
@Nonnull
public static AvatarFlightHudViewModel visible(double speedRatio,
                                               double targetSpeedRatio,
                                               double pitchRadians,
                                               double charges,
                                               double maxCharges,
                                               boolean groundedAtFull,
                                               @Nullable String rechargeMode,
                                               boolean launchChargeVisible,
                                               double launchChargeRatio,
                                               double launchMinChargeRatio) {
    double displayMax = clamp(finiteOrZero(maxCharges), 0.0, MAX_DISPLAY_PIPS);
    double displayCharges = clamp(finiteOrZero(charges), 0.0, displayMax);
    boolean dimmed = groundedAtFull && displayMax > 0.0 && displayCharges >= displayMax - FULL_EPSILON;
    return new AvatarFlightHudViewModel(
            true,
            speedRatio,
            targetSpeedRatio,
            Math.toDegrees(finiteOrZero(pitchRadians)),
            displayCharges,
            displayMax,
            dimmed,
            normalizeRechargeMode(rechargeMode),
            launchChargeVisible,
            launchChargeRatio,
            launchMinChargeRatio
    );
}
```

- [ ] **Step 4: Run the focused view-model test**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudViewModelTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit Task 1**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModelTest.java
git commit -m "Feat: add avatar flight launch charge HUD model"
```

## Task 2: Compute Launch Charge HUD State In AvatarFlightHudSystem

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystemArchitectureTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`

- [ ] **Step 1: Write failing architecture tests**

Update `hudSystemBuildsModelFromFlightAndUsesCurrentStorePlayer()` in `AvatarFlightHudSystemArchitectureTest` by replacing:

```java
Assertions.assertTrue(source.contains("Query.and(flightType, playerType)"));
```

with:

```java
Assertions.assertTrue(source.contains("Query.and(flightType, inputType, playerType)"));
Assertions.assertTrue(source.contains("archetypeChunk.getComponent(index, inputType)"));
Assertions.assertTrue(source.contains("input.isLaunchCharging()"));
Assertions.assertTrue(source.contains("input.isOnGround()"));
Assertions.assertTrue(source.contains("input.getLaunchChargeStartedAtMs()"));
Assertions.assertTrue(source.contains("config.getLaunch().getMinChargeMs()"));
Assertions.assertTrue(source.contains("config.getLaunch().getMaxChargeMs()"));
Assertions.assertTrue(source.contains("launchChargeVisible"));
Assertions.assertTrue(source.contains("launchChargeRatio"));
Assertions.assertTrue(source.contains("launchMinChargeRatio"));
```

Update `tameworkRegistersHudSystemAfterMovementAndBeforeVisualCleanup()` by adding this assertion after `playerTypeIndex`:

```java
int inputTypeIndex = source.indexOf("avatarFlightInputComponentType", hudIndex);
Assertions.assertTrue(inputTypeIndex > hudIndex);
```

- [ ] **Step 2: Run the HUD system architecture test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudSystemArchitectureTest test
```

Expected: FAIL because the system query does not include `inputType` yet.

- [ ] **Step 3: Update the HUD system constructor and query**

In `AvatarFlightHudSystem.java`, add a field:

```java
private final ComponentType<EntityStore, AvatarFlightInputComponent> inputType;
```

Change the constructor to:

```java
public AvatarFlightHudSystem(@Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
                             @Nonnull ComponentType<EntityStore, AvatarFlightInputComponent> inputType,
                             @Nonnull ComponentType<EntityStore, Player> playerType) {
    this.flightType = flightType;
    this.inputType = inputType;
    this.playerType = playerType;
    this.query = Query.and(flightType, inputType, playerType);
}
```

In the `tick` method, read input and guard it:

```java
AvatarFlightInputComponent input = archetypeChunk.getComponent(index, inputType);
Player player = archetypeChunk.getComponent(index, playerType);
if (ref == null || flight == null || input == null || player == null) {
    return;
}
```

Change the model build call to pass `input` and `System.currentTimeMillis()`:

```java
long now = System.currentTimeMillis();
AvatarFlightHudViewModel model = buildModel(flight, input, config, now);
showOrRefresh(playerUuid, player, flight.getEnabledAtMs(), model, config.getVigour().getHudResendIntervalMs(), now);
```

Change the `buildModel` signature:

```java
private static AvatarFlightHudViewModel buildModel(@Nonnull AvatarFlightComponent flight,
                                                   @Nonnull AvatarFlightInputComponent input,
                                                   @Nonnull TwAvatarFlightConfig config,
                                                   long nowMs) {
```

At the end of `buildModel`, compute launch values:

```java
long maxChargeMs = config.getLaunch().getMaxChargeMs();
boolean launchChargeVisible = config.getLaunch().isEnabled()
        && maxChargeMs > 0L
        && input.isLaunchCharging()
        && input.isOnGround();
double launchChargeRatio = launchChargeVisible
        ? ratio(nowMs - input.getLaunchChargeStartedAtMs(), maxChargeMs)
        : 0.0;
double launchMinChargeRatio = launchChargeVisible
        ? ratio(config.getLaunch().getMinChargeMs(), maxChargeMs)
        : 0.0;
```

Pass those values into the new view-model overload:

```java
return AvatarFlightHudViewModel.visible(
        speedRatio,
        flight.getHudTargetSpeedRatio(),
        flight.getHudPitchRadians(),
        flight.getVigourCharges(),
        maxCharges,
        groundedAtFull,
        flight.getVigourRechargeMode(),
        launchChargeVisible,
        launchChargeRatio,
        launchMinChargeRatio
);
```

Add this helper near `fullVigour`:

```java
private static double ratio(long value, long max) {
    if (max <= 0L) {
        return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, (double) value / (double) max));
}
```

Change `showOrRefresh` to accept `now` instead of calling `System.currentTimeMillis()` internally:

```java
private void showOrRefresh(@Nonnull UUID playerUuid,
                           @Nonnull Player player,
                           long enabledAtMs,
                           @Nonnull AvatarFlightHudViewModel model,
                           long resendIntervalMs,
                           long now) {
```

- [ ] **Step 4: Update Tamework system registration**

In `Tamework.java`, change:

```java
new AvatarFlightHudSystem(
        avatarFlightComponentType,
        Player.getComponentType()
)
```

to:

```java
new AvatarFlightHudSystem(
        avatarFlightComponentType,
        avatarFlightInputComponentType,
        Player.getComponentType()
)
```

- [ ] **Step 5: Run the focused HUD system test**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudSystemArchitectureTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit Task 2**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystemArchitectureTest.java
git commit -m "Feat: feed launch charge state into avatar flight HUD"
```

## Task 3: Bind Launch Charge Bar And Marker Anchors

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java`

- [ ] **Step 1: Write failing binder tests**

In `binderUsesDynamicAnchorsAndSixPipSelectors()`, add these assertions:

```java
Assertions.assertTrue(source.contains("LAUNCH_TRACK_WIDTH"));
Assertions.assertTrue(source.contains("LAUNCH_FILL_MAX_WIDTH"));
Assertions.assertTrue(source.contains("LAUNCH_MIN_MARKER_WIDTH"));
Assertions.assertTrue(source.contains("#LaunchChargeGroup.Visible"));
Assertions.assertTrue(source.contains("#LaunchChargeFill.Anchor"));
Assertions.assertTrue(source.contains("#LaunchMinChargeMarker.Visible"));
Assertions.assertTrue(source.contains("#LaunchMinChargeMarker.Anchor"));
Assertions.assertTrue(source.contains("model.launchChargeVisible()"));
Assertions.assertTrue(source.contains("model.launchChargeRatio()"));
Assertions.assertTrue(source.contains("launchMarkerAnchor(model.launchMinChargeRatio())"));
```

- [ ] **Step 2: Run the binder test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudBinderTest test
```

Expected: FAIL because launch selectors and constants do not exist.

- [ ] **Step 3: Add binder constants**

In `AvatarFlightHudBinder.java`, add these constants near the existing speed constants:

```java
private static final int LAUNCH_TRACK_WIDTH = 154;
private static final int LAUNCH_FILL_MAX_WIDTH = 152;
private static final int LAUNCH_FILL_HEIGHT = 8;
private static final int LAUNCH_MIN_MARKER_WIDTH = 2;
private static final int LAUNCH_MIN_MARKER_HEIGHT = 12;
```

- [ ] **Step 4: Bind launch group, fill, and marker**

In the `bind` method, after setting `#Root.Visible`, add:

```java
commandBuilder.set("#LaunchChargeGroup.Visible", model.visible() && model.launchChargeVisible());
commandBuilder.setObject("#LaunchChargeFill.Anchor",
        fillAnchor(LAUNCH_FILL_MAX_WIDTH, LAUNCH_FILL_HEIGHT, model.launchChargeRatio()));
commandBuilder.set("#LaunchMinChargeMarker.Visible", model.visible() && model.launchChargeVisible());
commandBuilder.setObject("#LaunchMinChargeMarker.Anchor", launchMarkerAnchor(model.launchMinChargeRatio()));
```

Add this helper beside `targetMarkerAnchor`:

```java
@Nonnull
private static Anchor launchMarkerAnchor(double ratio) {
    int center = 1 + (int) Math.round(LAUNCH_FILL_MAX_WIDTH * clamp01(ratio));
    int left = Math.max(0, Math.min(LAUNCH_TRACK_WIDTH - LAUNCH_MIN_MARKER_WIDTH,
            center - LAUNCH_MIN_MARKER_WIDTH / 2));
    Anchor anchor = new Anchor();
    anchor.setTop(Value.of(-2));
    anchor.setLeft(Value.of(left));
    anchor.setWidth(Value.of(LAUNCH_MIN_MARKER_WIDTH));
    anchor.setHeight(Value.of(LAUNCH_MIN_MARKER_HEIGHT));
    return anchor;
}
```

- [ ] **Step 5: Run the binder test**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudBinderTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit Task 3**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java
git commit -m "Feat: bind avatar launch charge HUD bar"
```

## Task 4: Add The Launch Charge Row To The Custom UI Asset

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java`
- Modify: `src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui`

- [ ] **Step 1: Write failing UI asset assertions**

In `uiAssetContainsExpectedCompactHudSelectors()`, add:

```java
Assertions.assertTrue(ui.contains("Group #LaunchChargeGroup"));
Assertions.assertTrue(ui.contains("Group #LaunchChargeTrack"));
Assertions.assertTrue(ui.contains("Group #LaunchChargeFill"));
Assertions.assertTrue(ui.contains("Group #LaunchMinChargeMarker"));
Assertions.assertTrue(ui.contains("Background: #ff9a4b;"));
Assertions.assertTrue(ui.contains("Background: #ffd765;"));
Assertions.assertTrue(ui.contains("Anchor: (Bottom: 178, Width: 178, Height: 70)"));
Assertions.assertTrue(ui.contains("Anchor: (Top: 0, Left: 12, Width: 154, Height: 10)"));
Assertions.assertTrue(ui.contains("Anchor: (Top: 18, Left: 0, Width: 178, Height: 12)"));
Assertions.assertTrue(ui.contains("Anchor: (Top: 34, Left: 12, Width: 154, Height: 8)"));
Assertions.assertTrue(ui.contains("Anchor: (Top: 50, Left: 16, Width: 145, Height: 10)"));
Assertions.assertFalse(ui.contains("Image:"),
        "launch charge HUD must use color groups, not image assets that can render as placeholders");
```

Update the existing fill count assertion from:

```java
Assertions.assertEquals(6, countOccurrences(ui, "Group #Fill"));
```

to:

```java
Assertions.assertEquals(6, countOccurrences(ui, "Group #Fill"));
Assertions.assertEquals(1, countOccurrences(ui, "Group #LaunchChargeFill"));
```

- [ ] **Step 2: Run the UI asset test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudBinderTest test
```

Expected: FAIL because the launch UI groups do not exist.

- [ ] **Step 3: Update root and row anchors**

In `TameworkAvatarFlightHud.ui`, change the root anchor to:

```text
Anchor: (Bottom: 178, Width: 178, Height: 70);
```

Insert this group before `Label #PitchLabel`:

```text
Group #LaunchChargeGroup {
    Anchor: (Top: 0, Left: 0, Width: 178, Height: 12);
    Visible: false;

    Group #LaunchChargeTrack {
        Anchor: (Top: 0, Left: 12, Width: 154, Height: 10);
        Background: #203044(0.92);

        Group #LaunchChargeFill {
            Anchor: (Top: 1, Left: 1, Width: 0, Height: 8);
            Background: #ff9a4b;
        }

        Group #LaunchMinChargeMarker {
            Anchor: (Top: -2, Left: 0, Width: 2, Height: 12);
            Background: #ffd765;
            Visible: false;
        }
    }
}
```

Move existing rows down by changing only these anchor lines:

```text
Label #PitchLabel {
    Anchor: (Top: 18, Left: 0, Width: 178, Height: 12);
    Text: "0°";
    Style: (FontSize: 11, RenderBold: true, TextColor: #f2f6fb, HorizontalAlignment: Center, VerticalAlignment: Center);
    Visible: false;
}

Group #SpeedTrack {
    Anchor: (Top: 34, Left: 12, Width: 154, Height: 8);
}

Group #PipRow {
    Anchor: (Top: 50, Left: 16, Width: 145, Height: 10);
}
```

Keep the existing `#SpeedFill`, `#TargetSpeedMarker`, and six `#VigourPip` child groups unchanged.

- [ ] **Step 4: Run the UI asset test**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightHudBinderTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit Task 4**

Run:

```powershell
git add src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java
git commit -m "Feat: add avatar launch charge HUD row"
```

## Task 5: Docs, Full Verification, And Runtime Install

**Files:**
- Modify: `docs/Avatar-Flight.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/agents/generated-index.md` only if `check-agent-docs.ps1` reports it stale.

- [ ] **Step 1: Update player-facing docs**

In `docs/Avatar-Flight.md`, update the HUD section with this paragraph:

```markdown
While grounded and holding the charged launch input, the compact avatar-flight HUD shows an amber launch-charge bar above the pitch readout. The bar fills from 0% at hold start to 100% at `Launch.MaxChargeMs`, and a small marker shows the minimum valid release threshold from `Launch.MinChargeMs`. The launch bar hides as soon as the charge is released, cancelled, or the player is airborne.
```

- [ ] **Step 2: Update changelog**

Add this Unreleased entry to `CHANGELOG.md`:

```markdown
- Added a temporary avatar-flight launch charge HUD bar above the pitch/speed display while holding grounded crouch launch, including a marker for the minimum valid release threshold.
```

- [ ] **Step 3: Run focused tests**

Run:

```powershell
.\mvnw "-Dtest=AvatarFlightHudViewModelTest,AvatarFlightHudSystemArchitectureTest,AvatarFlightHudBinderTest" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run full test suite**

Run:

```powershell
.\mvnw test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Run thread-safety grep**

Run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no matches in runtime tick paths introduced by this change. Existing non-tick matches can remain if already accepted by current guard tests.

- [ ] **Step 6: Run agent docs guard**

Run:

```powershell
.\scripts\tools\check-agent-docs.ps1
```

Expected: `Agent docs checks passed.`

If the generated index is stale, run:

```powershell
.\scripts\tools\build-agent-index.ps1
.\scripts\tools\check-agent-docs.ps1
```

Expected: `Generated agent index is current.` and `Agent docs checks passed.`

- [ ] **Step 7: Package and install for runtime testing**

Run:

```powershell
.\mvnw package -DskipTests -Pinstall-plugin
```

Expected: `BUILD SUCCESS`, with one jar copied to `..\..\install\release\package\game\latest\Server\mods` and one jar copied to `..\..\UserData\Mods`.

- [ ] **Step 8: Commit Task 5**

Run:

```powershell
git add docs/Avatar-Flight.md CHANGELOG.md docs/agents/generated-index.md
git commit -m "Docs: document avatar launch charge HUD"
```

If `docs/agents/generated-index.md` did not change, omit it from `git add`.

## Runtime Test Checklist

- [ ] Start the game after the new jar is installed.
- [ ] Run `/tw debugdragonflight on HyDragonNordicDrake`.
- [ ] Stay grounded and hold crouch.
- [ ] Confirm the amber launch bar appears above the pitch indicator immediately.
- [ ] Confirm the bar fills from empty to full over about 3 seconds.
- [ ] Confirm the minimum threshold marker appears at about 17% of the bar.
- [ ] Release before the marker and confirm the bar hides without launch.
- [ ] Hold past the marker and release; confirm launch applies.
- [ ] Land and repeat; confirm the bar works on second and later launches.
- [ ] Confirm airborne crouch descent does not show the launch bar.
- [ ] Confirm normal airborne HUD still shows pitch, speed, target speed marker, and Vigour pips.


