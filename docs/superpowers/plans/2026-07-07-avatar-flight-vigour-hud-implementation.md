# Avatar Flight Vigour HUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mathematically tested Vigour charges and a compact avatar-flight HUD for transformed player flight.

**Architecture:** Keep movement, resource math, and presentation separate. Add pure speed/resource services that are deterministic under unit tests, persist only the minimal resource state on `AvatarFlightComponent`, let `AvatarFlightMovementSystem` gate controller intents through the resource service, and render the HUD through the same `CustomUIHud` pattern already used by command target HUDs.

**Tech Stack:** Java, Hytale ECS `CommandBuffer`, Tamework `TwAvatarFlightConfig`, Hytale `CustomUIHud`, UI `.ui` assets, JUnit 5.

---

## Mathematical Balance Contract

Defaults are tuned around these equations:

- Cruise speed: `Movement.MaxForwardSpeed = 14.0`.
- Boosted horizontal cap: `Movement.MaxForwardSpeed + Boost.ForwardImpulse = 21.0`.
- Fast-recharge threshold: `21.0 * Vigour.FastFlightRechargeSpeedRatio(0.75) = 15.75`.
- Grounded recharge rate: `1 / 4s = 0.25 charges/s`.
- Fast-flight recharge rate: `1 / 8s = 0.125 charges/s`.
- Flap spend pressure at cooldown: `1 / 0.75s = 1.333 charges/s`, net `1.208 charges/s` while fast-recharging.
- Boost spend pressure at cooldown: `1 / 1.0s = 1.0 charges/s`, net `0.875 charges/s` while fast-recharging.
- Post-spend delay: `0.75s`, so one fast-flight charge after spending requires `8.75s` of continuous qualifying speed.

The important interpretation is that fast-flight recharge uses the boosted horizontal cap, not normal cruise speed. With defaults, ordinary cruise at `14.0` is only `66.7%` of boosted cap and does not recharge. A dive or boost must push horizontal speed above `15.75` before airborne recharge starts.

To make the balance actually converge toward landing, unpowered forward glide also needs a tested passive sink. Add `Movement.GlideSinkSpeed = 1.0` and `Movement.GlideSinkAcceleration = 2.0`, then assert a neutral forward glide loses meaningful altitude over a deterministic simulation.

## File Structure

- Modify `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
  - Add a nested `VigourSettings` codec section.
  - Add `Movement.GlideSinkSpeed` and `Movement.GlideSinkAcceleration`.
  - Add inheritance-aware fallback for both sections.
- Modify `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
  - Add default `Vigour` values and glide sink fields.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java`
  - Persist current Vigour, last Vigour update, and last spend timestamp.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSpeedMetrics.java`
  - Own horizontal speed, boosted cap, speed ratio, and fast-threshold math.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightVigourService.java`
  - Own charge clamping, recharge, spend checks, and HUD-facing recharge mode.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
  - Accept resource authorization flags in input.
  - Apply glide sink and allow pitch-down to reach boosted cap.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
  - Recharge Vigour, authorize flap/boost intents, spend only when the controller actually applies an ability, and include Vigour diagnostics.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java`
  - Immutable HUD state for speed ratio, charge fill, and visibility.
- Create `src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightHud.java`
  - `CustomUIHud` wrapper for the avatar-flight overlay.
- Create `src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java`
  - Bind view model values into the UI selectors.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java`
  - Show, refresh, dim, and remove the HUD while avatar flight is active.
- Create `src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui`
  - Compact center HUD above the hotbar.
- Modify `src/main/java/com/alechilles/alecstamework/Tamework.java`
  - Register the avatar-flight HUD system after movement/state systems.
- Modify `CHANGELOG.md`
  - Add the player-facing Vigour and HUD entry.
- Create `docs/Avatar-Flight.md`
  - Document transformed avatar-flight controls, Vigour, HUD behavior, and config fields.
- Modify `docs/agents/generated-index.md`
  - Rebuild after creating the new docs page.
- Add focused tests under `src/test/java/com/alechilles/alecstamework/avatarflight`, `src/test/java/com/alechilles/alecstamework/config/assets`, and `src/test/java/com/alechilles/alecstamework/ui`.

---

### Task 1: Config Surface and Balance Constants

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
- Modify: `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
- Test: `src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java`

- [ ] **Step 1: Add failing config tests**

Add these test methods to `TwAvatarFlightConfigTest`:

```java
@Test
void defaultConfigExposesVigourAndGlideBalanceValues() {
    TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

    assertTrue(config.getVigour().isEnabled());
    assertEquals(6.0, config.getVigour().getMaxCharges(), 0.00001);
    assertEquals(1.0, config.getVigour().getUpwardFlapCost(), 0.00001);
    assertEquals(1.0, config.getVigour().getForwardBoostCost(), 0.00001);
    assertEquals(4.0, config.getVigour().getGroundedRechargeSecondsPerCharge(), 0.00001);
    assertEquals(8.0, config.getVigour().getFastFlightRechargeSecondsPerCharge(), 0.00001);
    assertEquals(0.75, config.getVigour().getFastFlightRechargeSpeedRatio(), 0.00001);
    assertEquals(0.75, config.getVigour().getRechargeDelayAfterSpendSeconds(), 0.00001);
    assertTrue(config.getVigour().isHudEnabled());
    assertEquals(100L, config.getVigour().getHudResendIntervalMs());
    assertEquals(1.0, config.getMovement().getGlideSinkSpeed(), 0.00001);
    assertEquals(2.0, config.getMovement().getGlideSinkAcceleration(), 0.00001);
}

@Test
void explicitVigourSectionInheritsMissingNestedKeys() throws Exception {
    TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
    TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
    setNestedField(parent, "vigour", "maxCharges", 8.0);
    setNestedField(parent, "vigour", "fastFlightRechargeSecondsPerCharge", 10.0);
    setNestedField(parent, "vigour", "hudEnabled", false);
    setNestedField(child, "vigour", "maxCharges", 4.0);
    setNestedField(child, "vigour", "fastFlightRechargeSecondsPerCharge", 3.0);
    setNestedField(child, "vigour", "hudEnabled", true);

    child.inheritMissingTopLevelFrom(
            parent,
            Set.of("Vigour"),
            Map.of("Vigour", Set.of("MaxCharges"))
    );

    assertEquals(4.0, child.getVigour().getMaxCharges(), 0.00001);
    assertEquals(10.0, child.getVigour().getFastFlightRechargeSecondsPerCharge(), 0.00001);
    assertFalse(child.getVigour().isHudEnabled());
}

@Test
void explicitMovementSectionInheritsMissingGlideSinkKeys() throws Exception {
    TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
    TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
    setNestedField(parent, "movement", "glideSinkSpeed", 1.4);
    setNestedField(parent, "movement", "glideSinkAcceleration", 3.0);
    setNestedField(child, "movement", "glideSinkSpeed", 0.2);
    setNestedField(child, "movement", "glideSinkAcceleration", 0.3);

    child.inheritMissingTopLevelFrom(
            parent,
            Set.of("Movement"),
            Map.of("Movement", Set.of("GlideSinkSpeed"))
    );

    assertEquals(0.2, child.getMovement().getGlideSinkSpeed(), 0.00001);
    assertEquals(3.0, child.getMovement().getGlideSinkAcceleration(), 0.00001);
}
```

- [ ] **Step 2: Run focused config tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=TwAvatarFlightConfigTest test
```

Expected: FAIL because `getVigour`, `glideSinkSpeed`, and `glideSinkAcceleration` do not exist.

- [ ] **Step 3: Add `VIGOUR_CODEC` and movement sink codec fields**

In `TwAvatarFlightConfig`, add this codec section after `BOOST_CODEC`:

```java
private static final BuilderCodec<VigourSettings> VIGOUR_CODEC = BuilderCodec.builder(
        VigourSettings.class,
        VigourSettings::new
)
        .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                (settings, value) -> settings.enabled = value == null || value,
                settings -> settings.enabled)
        .documentation("Whether avatar flight movement abilities spend and recharge Vigour. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("MaxCharges", Codec.DOUBLE),
                (settings, value) -> settings.maxCharges = positiveOrDefault(value, 6.0),
                settings -> settings.maxCharges)
        .documentation("Maximum Vigour charges available to this flight form. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("UpwardFlapCost", Codec.DOUBLE),
                (settings, value) -> settings.upwardFlapCost = nonNegativeOrDefault(value, 1.0),
                settings -> settings.upwardFlapCost)
        .documentation("Vigour cost for a successful upward flap. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("ForwardBoostCost", Codec.DOUBLE),
                (settings, value) -> settings.forwardBoostCost = nonNegativeOrDefault(value, 1.0),
                settings -> settings.forwardBoostCost)
        .documentation("Vigour cost for a successful forward boost. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("GroundedRechargeSecondsPerCharge", Codec.DOUBLE),
                (settings, value) -> settings.groundedRechargeSecondsPerCharge = positiveOrDefault(value, 4.0),
                settings -> settings.groundedRechargeSecondsPerCharge)
        .documentation("Seconds required to recover one Vigour charge while grounded. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("FastFlightRechargeSecondsPerCharge", Codec.DOUBLE),
                (settings, value) -> settings.fastFlightRechargeSecondsPerCharge = positiveOrDefault(value, 8.0),
                settings -> settings.fastFlightRechargeSecondsPerCharge)
        .documentation("Seconds required to recover one Vigour charge while airborne above the fast-flight threshold. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("FastFlightRechargeSpeedRatio", Codec.DOUBLE),
                (settings, value) -> settings.fastFlightRechargeSpeedRatio = clamp01(value, 0.75),
                settings -> settings.fastFlightRechargeSpeedRatio)
        .documentation("Horizontal speed ratio of boosted max speed required for airborne recharge. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("RechargeDelayAfterSpendSeconds", Codec.DOUBLE),
                (settings, value) -> settings.rechargeDelayAfterSpendSeconds = nonNegativeOrDefault(value, 0.75),
                settings -> settings.rechargeDelayAfterSpendSeconds)
        .documentation("Seconds after a spend before Vigour can recharge again. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Boolean>append(new KeyedCodec<>("HudEnabled", Codec.BOOLEAN),
                (settings, value) -> settings.hudEnabled = value == null || value,
                settings -> settings.hudEnabled)
        .documentation("Whether this avatar-flight config shows the Vigour HUD. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("HudResendIntervalMs", Codec.DOUBLE),
                (settings, value) -> settings.hudResendIntervalMs = positiveOrDefault(value, 100.0),
                settings -> settings.hudResendIntervalMs)
        .documentation("Minimum milliseconds between unchanged Vigour HUD refresh packets. Inheritance: missing nested key inherits parent value.")
        .add()
        .build();
```

In `MOVEMENT_CODEC`, add:

```java
.<Double>append(new KeyedCodec<>("GlideSinkSpeed", Codec.DOUBLE),
        (settings, value) -> settings.glideSinkSpeed = nonNegativeOrDefault(value, 1.0),
        settings -> settings.glideSinkSpeed)
.documentation("Target downward speed for unpowered forward glide. Inheritance: missing nested key inherits parent value.")
.add()
.<Double>append(new KeyedCodec<>("GlideSinkAcceleration", Codec.DOUBLE),
        (settings, value) -> settings.glideSinkAcceleration = nonNegativeOrDefault(value, 2.0),
        settings -> settings.glideSinkAcceleration)
.documentation("Rate at which unpowered forward glide approaches GlideSinkSpeed. Inheritance: missing nested key inherits parent value.")
.add()
```

- [ ] **Step 4: Add fields, getters, and inheritance**

Add a top-level field:

```java
private VigourSettings vigour = new VigourSettings();
```

Append the top-level codec key between `Boost` and `Animation`:

```java
.<VigourSettings>append(new KeyedCodec<>("Vigour", VIGOUR_CODEC),
        (asset, value) -> asset.vigour = value == null ? new VigourSettings() : value,
        asset -> asset.vigour)
.documentation("Avatar-flight charge resource settings. Omitted section inherits; explicit nested keys override and missing nested keys inherit.")
.add()
```

In `inheritMissingTopLevelFrom`, call:

```java
inheritOrCopyVigour(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Vigour"), explicitTopLevelKeys);
```

Add:

```java
private void inheritOrCopyVigour(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
    if (!top.contains("Vigour")) vigour = parent.vigour;
    else if (keys != null && vigour != null && parent.vigour != null) {
        if (!keys.contains("Enabled")) vigour.enabled = parent.vigour.enabled;
        if (!keys.contains("MaxCharges")) vigour.maxCharges = parent.vigour.maxCharges;
        if (!keys.contains("UpwardFlapCost")) vigour.upwardFlapCost = parent.vigour.upwardFlapCost;
        if (!keys.contains("ForwardBoostCost")) vigour.forwardBoostCost = parent.vigour.forwardBoostCost;
        if (!keys.contains("GroundedRechargeSecondsPerCharge")) {
            vigour.groundedRechargeSecondsPerCharge = parent.vigour.groundedRechargeSecondsPerCharge;
        }
        if (!keys.contains("FastFlightRechargeSecondsPerCharge")) {
            vigour.fastFlightRechargeSecondsPerCharge = parent.vigour.fastFlightRechargeSecondsPerCharge;
        }
        if (!keys.contains("FastFlightRechargeSpeedRatio")) {
            vigour.fastFlightRechargeSpeedRatio = parent.vigour.fastFlightRechargeSpeedRatio;
        }
        if (!keys.contains("RechargeDelayAfterSpendSeconds")) {
            vigour.rechargeDelayAfterSpendSeconds = parent.vigour.rechargeDelayAfterSpendSeconds;
        }
        if (!keys.contains("HudEnabled")) vigour.hudEnabled = parent.vigour.hudEnabled;
        if (!keys.contains("HudResendIntervalMs")) vigour.hudResendIntervalMs = parent.vigour.hudResendIntervalMs;
    }
}
```

Update `inheritOrCopyMovement` with:

```java
if (!keys.contains("GlideSinkSpeed")) movement.glideSinkSpeed = parent.movement.glideSinkSpeed;
if (!keys.contains("GlideSinkAcceleration")) movement.glideSinkAcceleration = parent.movement.glideSinkAcceleration;
```

Add getter:

```java
public VigourSettings getVigour() { return vigour == null ? new VigourSettings() : vigour; }
```

Add fields/getters to `MovementSettings`:

```java
private double glideSinkSpeed = 1.0;
private double glideSinkAcceleration = 2.0;

public double getGlideSinkSpeed() { return glideSinkSpeed; }
public double getGlideSinkAcceleration() { return glideSinkAcceleration; }
```

Add nested settings class:

```java
public static final class VigourSettings {
    private boolean enabled = true;
    private double maxCharges = 6.0;
    private double upwardFlapCost = 1.0;
    private double forwardBoostCost = 1.0;
    private double groundedRechargeSecondsPerCharge = 4.0;
    private double fastFlightRechargeSecondsPerCharge = 8.0;
    private double fastFlightRechargeSpeedRatio = 0.75;
    private double rechargeDelayAfterSpendSeconds = 0.75;
    private boolean hudEnabled = true;
    private double hudResendIntervalMs = 100.0;

    public boolean isEnabled() { return enabled; }
    public double getMaxCharges() { return Math.max(0.0, maxCharges); }
    public double getUpwardFlapCost() { return Math.max(0.0, upwardFlapCost); }
    public double getForwardBoostCost() { return Math.max(0.0, forwardBoostCost); }
    public double getGroundedRechargeSecondsPerCharge() { return Math.max(0.001, groundedRechargeSecondsPerCharge); }
    public double getFastFlightRechargeSecondsPerCharge() { return Math.max(0.001, fastFlightRechargeSecondsPerCharge); }
    public double getFastFlightRechargeSpeedRatio() { return Math.max(0.0, Math.min(1.0, fastFlightRechargeSpeedRatio)); }
    public double getRechargeDelayAfterSpendSeconds() { return Math.max(0.0, rechargeDelayAfterSpendSeconds); }
    public boolean isHudEnabled() { return hudEnabled; }
    public long getHudResendIntervalMs() { return Math.round(Math.max(1.0, hudResendIntervalMs)); }
}
```

- [ ] **Step 5: Update default JSON**

Add movement sink fields:

```json
"GlideSinkSpeed": 1.0,
"GlideSinkAcceleration": 2.0
```

Add the `Vigour` section after `Boost`:

```json
"Vigour": {
  "Enabled": true,
  "MaxCharges": 6.0,
  "UpwardFlapCost": 1.0,
  "ForwardBoostCost": 1.0,
  "GroundedRechargeSecondsPerCharge": 4.0,
  "FastFlightRechargeSecondsPerCharge": 8.0,
  "FastFlightRechargeSpeedRatio": 0.75,
  "RechargeDelayAfterSpendSeconds": 0.75,
  "HudEnabled": true,
  "HudResendIntervalMs": 100.0
}
```

- [ ] **Step 6: Run focused config tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TwAvatarFlightConfigTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java
git commit -m "Feat: add avatar flight vigour config"
```

---

### Task 2: Deterministic Speed and Vigour Math

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSpeedMetrics.java`
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightVigourService.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSpeedMetricsTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightVigourServiceTest.java`

- [ ] **Step 1: Add failing speed-metric tests**

Create `AvatarFlightSpeedMetricsTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightSpeedMetricsTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void boostedHorizontalCapDefinesHudAndFastRechargeMath() {
        assertEquals(14.0, CONFIG.getMovement().getMaxForwardSpeed(), 0.00001);
        assertEquals(7.0, CONFIG.getBoost().getForwardImpulse(), 0.00001);
        assertEquals(21.0, AvatarFlightSpeedMetrics.boostedHorizontalCap(CONFIG), 0.00001);
        assertEquals(15.75, AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG), 0.00001);
        assertEquals(2.0 / 3.0, AvatarFlightSpeedMetrics.speedRatio(14.0, CONFIG), 0.00001);
        assertFalse(AvatarFlightSpeedMetrics.isFastFlightRechargeSpeed(14.0, CONFIG));
        assertTrue(AvatarFlightSpeedMetrics.isFastFlightRechargeSpeed(16.0, CONFIG));
    }

    @Test
    void horizontalSpeedIgnoresVerticalFallSpeed() {
        assertEquals(5.0, AvatarFlightSpeedMetrics.horizontalSpeed(3.0, -100.0, 4.0), 0.00001);
    }
}
```

- [ ] **Step 2: Add failing Vigour service tests**

Create `AvatarFlightVigourServiceTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightVigourServiceTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void groundedRechargeAddsOneChargeEveryFourSeconds() {
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(2.0, 0L, 0L);

        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                state,
                CONFIG,
                true,
                0.0,
                4_000L
        );

        assertEquals(3.0, result.charges(), 0.00001);
        assertEquals(AvatarFlightVigourService.RechargeMode.GROUNDED, result.rechargeMode());
    }

    @Test
    void fastFlightRechargeAddsOneChargeEveryEightSeconds() {
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(2.0, 0L, 0L);

        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                state,
                CONFIG,
                false,
                16.0,
                8_000L
        );

        assertEquals(3.0, result.charges(), 0.00001);
        assertEquals(AvatarFlightVigourService.RechargeMode.FAST_FLIGHT, result.rechargeMode());
    }

    @Test
    void ordinaryCruiseDoesNotRechargeInAir() {
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(2.0, 0L, 0L);

        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                state,
                CONFIG,
                false,
                14.0,
                8_000L
        );

        assertEquals(2.0, result.charges(), 0.00001);
        assertEquals(AvatarFlightVigourService.RechargeMode.NONE, result.rechargeMode());
    }

    @Test
    void spendDelayBlocksImmediateRefund() {
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(5.0, 1_000L, 1_000L);

        AvatarFlightVigourService.Result delayed = AvatarFlightVigourService.recharge(
                state,
                CONFIG,
                false,
                16.0,
                1_700L
        );
        AvatarFlightVigourService.Result recovered = AvatarFlightVigourService.recharge(
                state,
                CONFIG,
                false,
                16.0,
                9_750L
        );

        assertEquals(5.0, delayed.charges(), 0.00001);
        assertEquals(AvatarFlightVigourService.RechargeMode.DELAYED, delayed.rechargeMode());
        assertEquals(6.0, recovered.charges(), 0.00001);
    }

    @Test
    void spendGatesAbilitiesAtZeroCharges() {
        assertFalse(AvatarFlightVigourService.canSpend(0.0, CONFIG.getVigour().getUpwardFlapCost()));
        assertTrue(AvatarFlightVigourService.canSpend(1.0, CONFIG.getVigour().getUpwardFlapCost()));
        assertEquals(0.0, AvatarFlightVigourService.spend(1.0, CONFIG.getVigour().getUpwardFlapCost()), 0.00001);
    }
}
```

- [ ] **Step 3: Run focused math tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightSpeedMetricsTest,AvatarFlightVigourServiceTest test
```

Expected: FAIL because both classes do not exist.

- [ ] **Step 4: Implement `AvatarFlightSpeedMetrics`**

Create:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nonnull;

/** Pure speed calculations shared by avatar-flight balance and HUD rendering. */
public final class AvatarFlightSpeedMetrics {
    private AvatarFlightSpeedMetrics() {
    }

    public static double horizontalSpeed(double x, double y, double z) {
        return Math.hypot(finite(x), finite(z));
    }

    public static double boostedHorizontalCap(@Nonnull TwAvatarFlightConfig config) {
        return Math.max(
                config.getMovement().getMaxForwardSpeed(),
                config.getMovement().getMaxForwardSpeed() + config.getBoost().getForwardImpulse()
        );
    }

    public static double speedRatio(double horizontalSpeed, @Nonnull TwAvatarFlightConfig config) {
        double cap = boostedHorizontalCap(config);
        if (cap <= 0.0) {
            return 0.0;
        }
        return clamp01(horizontalSpeed / cap);
    }

    public static double fastRechargeThreshold(@Nonnull TwAvatarFlightConfig config) {
        return boostedHorizontalCap(config) * config.getVigour().getFastFlightRechargeSpeedRatio();
    }

    public static boolean isFastFlightRechargeSpeed(double horizontalSpeed, @Nonnull TwAvatarFlightConfig config) {
        return horizontalSpeed >= fastRechargeThreshold(config);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
```

- [ ] **Step 5: Implement `AvatarFlightVigourService`**

Create:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nonnull;

/** Pure resource math for avatar-flight Vigour charges. */
public final class AvatarFlightVigourService {
    private AvatarFlightVigourService() {
    }

    public static Result recharge(@Nonnull State state,
                                  @Nonnull TwAvatarFlightConfig config,
                                  boolean grounded,
                                  double horizontalSpeed,
                                  long nowMs) {
        TwAvatarFlightConfig.VigourSettings vigour = config.getVigour();
        double max = vigour.getMaxCharges();
        double charges = clamp(state.charges(), 0.0, max);
        if (!vigour.isEnabled() || max <= 0.0 || charges >= max) {
            return new Result(charges, RechargeMode.NONE);
        }
        long lastUpdateAtMs = state.lastUpdateAtMs();
        long elapsedMs = Math.max(0L, nowMs - lastUpdateAtMs);
        if (elapsedMs == 0L) {
            return new Result(charges, RechargeMode.NONE);
        }
        long delayMs = Math.round(vigour.getRechargeDelayAfterSpendSeconds() * 1000.0);
        if (state.lastSpendAtMs() != 0L && nowMs - state.lastSpendAtMs() < delayMs) {
            return new Result(charges, RechargeMode.DELAYED);
        }
        RechargeMode mode;
        double secondsPerCharge;
        if (grounded) {
            mode = RechargeMode.GROUNDED;
            secondsPerCharge = vigour.getGroundedRechargeSecondsPerCharge();
        } else if (AvatarFlightSpeedMetrics.isFastFlightRechargeSpeed(horizontalSpeed, config)) {
            mode = RechargeMode.FAST_FLIGHT;
            secondsPerCharge = vigour.getFastFlightRechargeSecondsPerCharge();
        } else {
            return new Result(charges, RechargeMode.NONE);
        }
        double recovered = (elapsedMs / 1000.0) / secondsPerCharge;
        return new Result(clamp(charges + recovered, 0.0, max), mode);
    }

    public static boolean canSpend(double charges, double cost) {
        return cost <= 0.0 || charges + 0.000001 >= cost;
    }

    public static double spend(double charges, double cost) {
        return Math.max(0.0, charges - Math.max(0.0, cost));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, Double.isFinite(value) ? value : min));
    }

    public enum RechargeMode {
        NONE,
        DELAYED,
        GROUNDED,
        FAST_FLIGHT
    }

    public record State(double charges, long lastUpdateAtMs, long lastSpendAtMs) {
    }

    public record Result(double charges, @Nonnull RechargeMode rechargeMode) {
    }
}
```

- [ ] **Step 6: Run focused math tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightSpeedMetricsTest,AvatarFlightVigourServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSpeedMetrics.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightVigourService.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSpeedMetricsTest.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightVigourServiceTest.java
git commit -m "Feat: add avatar flight vigour math"
```

---

### Task 3: Persist Vigour State and Gate Ability Intents

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java`

- [ ] **Step 1: Add failing component state tests**

Create `AvatarFlightComponentTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightComponentTest {
    @Test
    void clonePreservesVigourState() {
        AvatarFlightComponent component = new AvatarFlightComponent("Test", 100L);
        component.setVigourCharges(3.5);
        component.setLastVigourUpdateAtMs(200L);
        component.setLastVigourSpendAtMs(150L);
        component.setVigourRechargeMode("FAST_FLIGHT");

        AvatarFlightComponent clone = component.clone();

        assertEquals(3.5, clone.getVigourCharges(), 0.00001);
        assertEquals(200L, clone.getLastVigourUpdateAtMs());
        assertEquals(150L, clone.getLastVigourSpendAtMs());
        assertEquals("FAST_FLIGHT", clone.getVigourRechargeMode());
    }
}
```

- [ ] **Step 2: Add failing controller gating tests**

Add to `AvatarFlightControllerTest`:

```java
@Test
void flapIntentDoesNotApplyWhenResourceGateBlocksIt() {
    AvatarFlightController.Input denied = new AvatarFlightController.Input(
            0.0, 0.0, 0.0, true, false, false, false, false, 0.0, 0.0, false, true
    );

    AvatarFlightController.Output output = AvatarFlightController.update(
            new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
            denied,
            CONFIG,
            0.1,
            1000L
    );

    assertFalse(output.jumpApplied());
    assertEquals(0.0, output.velocityY(), 0.00001);
}

@Test
void boostIntentDoesNotApplyWhenResourceGateBlocksIt() {
    AvatarFlightController.Input denied = new AvatarFlightController.Input(
            1.0, 0.0, 0.0, false, false, true, false, false, 0.0, 0.0, true, false
    );

    AvatarFlightController.Output output = AvatarFlightController.update(
            new AvatarFlightController.State(0.0, 0.0, -CONFIG.getMovement().getMaxForwardSpeed(), 0L, 0L),
            denied,
            CONFIG,
            0.1,
            1000L
    );

    assertFalse(output.boostApplied());
    assertTrue(Math.hypot(output.velocityX(), output.velocityZ()) <= CONFIG.getMovement().getMaxForwardSpeed());
}
```

Update the test helper constructors in the same file to pass `true, true` for the new authorization flags.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightComponentTest,AvatarFlightControllerTest test
```

Expected: FAIL because the component fields and input gates do not exist.

- [ ] **Step 4: Add persisted resource fields to `AvatarFlightComponent`**

Add codec fields:

```java
.<Double>append(new KeyedCodec<>("VigourCharges", Codec.DOUBLE),
        AvatarFlightComponent::setVigourCharges,
        AvatarFlightComponent::getVigourCharges)
.add()
.<Long>append(new KeyedCodec<>("LastVigourUpdateAtMs", Codec.LONG),
        AvatarFlightComponent::setLastVigourUpdateAtMs,
        AvatarFlightComponent::getLastVigourUpdateAtMs)
.add()
.<Long>append(new KeyedCodec<>("LastVigourSpendAtMs", Codec.LONG),
        AvatarFlightComponent::setLastVigourSpendAtMs,
        AvatarFlightComponent::getLastVigourSpendAtMs)
.add()
.<String>append(new KeyedCodec<>("VigourRechargeMode", Codec.STRING),
        AvatarFlightComponent::setVigourRechargeMode,
        AvatarFlightComponent::getVigourRechargeMode)
.add()
```

Add fields and accessors:

```java
private double vigourCharges;
private long lastVigourUpdateAtMs;
private long lastVigourSpendAtMs;
private String vigourRechargeMode = "";

public double getVigourCharges() { return vigourCharges; }
public void setVigourCharges(@Nullable Double value) { vigourCharges = finiteOrZero(value); }
public long getLastVigourUpdateAtMs() { return lastVigourUpdateAtMs; }
public void setLastVigourUpdateAtMs(@Nullable Long value) { lastVigourUpdateAtMs = value == null ? 0L : value; }
public long getLastVigourSpendAtMs() { return lastVigourSpendAtMs; }
public void setLastVigourSpendAtMs(@Nullable Long value) { lastVigourSpendAtMs = value == null ? 0L : value; }
@Nonnull
public String getVigourRechargeMode() { return vigourRechargeMode == null ? "" : vigourRechargeMode; }
public void setVigourRechargeMode(@Nullable String value) { vigourRechargeMode = value == null ? "" : value.trim(); }
```

Copy these fields in `clone()`.

- [ ] **Step 5: Initialize Vigour on activation**

In `AvatarFlightActivator.enable`, replace the direct constructor call with:

```java
AvatarFlightComponent component = new AvatarFlightComponent(config.getId(), System.currentTimeMillis());
component.setVigourCharges(config.getVigour().getMaxCharges());
component.setLastVigourUpdateAtMs(component.getEnabledAtMs());
store.putComponent(ref, flightType, component);
```

- [ ] **Step 6: Add authorization flags to controller input**

Extend the `AvatarFlightController.Input` record:

```java
public record Input(double forwardAxis,
                    double strafeAxis,
                    double verticalAxis,
                    boolean jump,
                    boolean crouch,
                    boolean sprint,
                    boolean airbrake,
                    boolean onGround,
                    double yawRadians,
                    double pitchRadians,
                    boolean flapAllowed,
                    boolean boostAllowed) {
}
```

Change ability checks:

```java
if (jumpIntent && input.flapAllowed() && (nextJumpAtMs == 0L || nowMs >= nextJumpAtMs)) {
    ...
}
if (!explicitAirbrakeIntent && input.sprint() && input.boostAllowed()
        && (nextBoostAtMs == 0L || nowMs >= nextBoostAtMs)) {
    ...
}
```

- [ ] **Step 7: Gate and spend Vigour in movement system**

In `AvatarFlightMovementSystem.tick`, after `controllerInput` is built and before `AvatarFlightController.update`, add:

```java
long now = System.currentTimeMillis();
double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
        flight.getVelocityX(),
        flight.getVelocityY(),
        flight.getVelocityZ()
);
AvatarFlightVigourService.Result vigourResult = AvatarFlightVigourService.recharge(
        new AvatarFlightVigourService.State(
                initialVigourCharges(flight, config),
                flight.getLastVigourUpdateAtMs(),
                flight.getLastVigourSpendAtMs()
        ),
        config,
        controllerInput.onGround(),
        horizontalSpeed,
        now
);
flight.setVigourCharges(vigourResult.charges());
flight.setLastVigourUpdateAtMs(now);
flight.setVigourRechargeMode(vigourResult.rechargeMode().name());
controllerInput = authorizeAbilities(controllerInput, flight, config);
```

Add helpers:

```java
private static double initialVigourCharges(@Nonnull AvatarFlightComponent flight,
                                           @Nonnull TwAvatarFlightConfig config) {
    if (!config.getVigour().isEnabled()) {
        return config.getVigour().getMaxCharges();
    }
    if (flight.getLastVigourUpdateAtMs() == 0L && flight.getVigourCharges() <= 0.0) {
        return config.getVigour().getMaxCharges();
    }
    return flight.getVigourCharges();
}

@Nonnull
private static AvatarFlightController.Input authorizeAbilities(
        @Nonnull AvatarFlightController.Input input,
        @Nonnull AvatarFlightComponent flight,
        @Nonnull TwAvatarFlightConfig config) {
    if (!config.getVigour().isEnabled()) {
        return new AvatarFlightController.Input(
                input.forwardAxis(), input.strafeAxis(), input.verticalAxis(),
                input.jump(), input.crouch(), input.sprint(), input.airbrake(), input.onGround(),
                input.yawRadians(), input.pitchRadians(), true, true
        );
    }
    boolean flapAllowed = AvatarFlightVigourService.canSpend(
            flight.getVigourCharges(),
            config.getVigour().getUpwardFlapCost()
    );
    boolean boostAllowed = AvatarFlightVigourService.canSpend(
            flight.getVigourCharges(),
            config.getVigour().getForwardBoostCost()
    );
    return new AvatarFlightController.Input(
            input.forwardAxis(), input.strafeAxis(), input.verticalAxis(),
            input.jump(), input.crouch(), input.sprint(), input.airbrake(), input.onGround(),
            input.yawRadians(), input.pitchRadians(), flapAllowed, boostAllowed
    );
}
```

After controller output:

```java
if (config.getVigour().isEnabled()) {
    boolean spent = false;
    if (output.jumpApplied()) {
        flight.setVigourCharges(AvatarFlightVigourService.spend(
                flight.getVigourCharges(),
                config.getVigour().getUpwardFlapCost()
        ));
        spent = true;
    }
    if (output.boostApplied()) {
        flight.setVigourCharges(AvatarFlightVigourService.spend(
                flight.getVigourCharges(),
                config.getVigour().getForwardBoostCost()
        ));
        spent = true;
    }
    if (spent) {
        flight.setLastVigourSpendAtMs(now);
        flight.setVigourRechargeMode(AvatarFlightVigourService.RechargeMode.DELAYED.name());
    }
}
```

In `toControllerInput`, pass `true, true` for the initial authorization flags.

- [ ] **Step 8: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightComponentTest,AvatarFlightControllerTest,AvatarFlightVigourServiceTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java
git commit -m "Feat: gate avatar flight abilities with vigour"
```

---

### Task 4: Glide Sink and Fast-Speed Balance

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`

- [ ] **Step 1: Add failing balance simulation tests**

Add to `AvatarFlightControllerTest`:

```java
@Test
void unpoweredForwardGlideLosesAltitudeOverTime() {
    AvatarFlightController.State state = new AvatarFlightController.State(0.0, 0.0, -14.0, 0L, 0L);
    double altitudeChange = 0.0;

    for (int tick = 0; tick < 200; tick++) {
        AvatarFlightController.Output output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, 0.0),
                CONFIG,
                0.1,
                1000L + (tick * 100L)
        );
        altitudeChange += output.velocityY() * 0.1;
        state = new AvatarFlightController.State(
                output.velocityX(),
                output.velocityY(),
                output.velocityZ(),
                output.nextJumpAtMs(),
                output.nextBoostAtMs()
        );
    }

    assertTrue(altitudeChange < -12.0,
            "neutral forward glide should sink enough that running out of Vigour eventually matters");
}

@Test
void pitchDownCanReachFastRechargeSpeedBySpendingAltitude() {
    AvatarFlightController.State state = new AvatarFlightController.State(0.0, 0.0, -14.0, 0L, 0L);
    AvatarFlightController.Output output = null;

    for (int tick = 0; tick < 20; tick++) {
        output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, Math.toRadians(-55.0)),
                CONFIG,
                0.1,
                1000L + (tick * 100L)
        );
        state = new AvatarFlightController.State(
                output.velocityX(),
                output.velocityY(),
                output.velocityZ(),
                output.nextJumpAtMs(),
                output.nextBoostAtMs()
        );
    }

    double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
    assertTrue(horizontalSpeed >= AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG),
            "dive speed should qualify for speed-only fast recharge without requiring a boost spend");
    assertTrue(output.velocityY() < 0.0,
            "qualifying through a dive must cost altitude");
}
```

- [ ] **Step 2: Run focused controller tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightControllerTest test
```

Expected: FAIL because neutral forward glide does not sink enough and pitch-down is capped at cruise speed.

- [ ] **Step 3: Apply passive sink during forward glide**

After jump/boost handling and before the vertical-mode block, compute:

```java
double boostedHorizontalCap = AvatarFlightSpeedMetrics.boostedHorizontalCap(config);
```

In the vertical block, update the pitch branch:

```java
} else if (targetForwardSpeed > MIN_FORWARD_FOR_PITCH_TRADE) {
    PitchAdjustment pitch = applyPitch(
            input.pitchRadians(),
            targetForwardSpeed,
            vertical,
            movement,
            boostedHorizontalCap,
            dt
    );
    targetForwardSpeed = pitch.forwardSpeed();
    vertical = pitch.verticalSpeed();
    if (Math.abs(input.pitchRadians()) < Math.toRadians(2.0) && !jumpApplied) {
        vertical = approach(
                vertical,
                -movement.getGlideSinkSpeed(),
                movement.getGlideSinkAcceleration() * dt
        );
    }
```

Change the `applyPitch` signature:

```java
private static PitchAdjustment applyPitch(double pitchRadians,
                                          double forwardSpeed,
                                          double verticalSpeed,
                                          @Nonnull TwAvatarFlightConfig.MovementSettings movement,
                                          double boostedHorizontalCap,
                                          double dt)
```

In the pitch-down branch, replace the cap:

```java
Math.min(boostedHorizontalCap, forwardSpeed + movement.getPitchDownSpeedGain() * amount * dt)
```

In the pitch-up branch, keep the target forward speed cap at `movement.getMaxForwardSpeed()` so climbing bleeds back toward cruise.

- [ ] **Step 4: Run focused controller tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightControllerTest,AvatarFlightSpeedMetricsTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java
git commit -m "Fix: make avatar flight glide balance converge"
```

---

### Task 5: Compact Vigour HUD

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java`
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java`
- Create: `src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightHud.java`
- Create: `src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java`
- Create: `src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModelTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystemArchitectureTest.java`

- [ ] **Step 1: Add failing view-model tests**

Create `AvatarFlightHudViewModelTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightHudViewModelTest {
    @Test
    void resolvesPipFillFromPartialCharges() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.5,
                3.25,
                6,
                false,
                AvatarFlightVigourService.RechargeMode.FAST_FLIGHT.name()
        );

        assertEquals(1.0, model.pipFill(0), 0.00001);
        assertEquals(1.0, model.pipFill(2), 0.00001);
        assertEquals(0.25, model.pipFill(3), 0.00001);
        assertEquals(0.0, model.pipFill(4), 0.00001);
    }

    @Test
    void dimmedGroundedFullStateStaysVisibleButSubdued() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.0,
                6.0,
                6,
                true,
                AvatarFlightVigourService.RechargeMode.NONE.name()
        );

        assertTrue(model.visible());
        assertTrue(model.dimmed());
    }

    @Test
    void hiddenWhenHudDisabled() {
        assertFalse(AvatarFlightHudViewModel.hidden().visible());
    }
}
```

- [ ] **Step 2: Add failing HUD architecture tests**

Create `AvatarFlightHudSystemArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightHudSystemArchitectureTest {
    @Test
    void hudSystemUsesCustomHudAndRegistersInPlugin() throws Exception {
        String system = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightHudSystem.java"
        ), StandardCharsets.UTF_8);
        String plugin = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
        ), StandardCharsets.UTF_8);

        assertTrue(system.contains("TameworkAvatarFlightHud"));
        assertTrue(system.contains("player.getHudManager().addCustomHud"));
        assertTrue(system.contains("player.getHudManager().removeCustomHud"));
        assertTrue(system.contains("AvatarFlightSpeedMetrics.speedRatio"));
        assertTrue(plugin.contains("new AvatarFlightHudSystem("));
    }
}
```

Create `AvatarFlightHudBinderTest.java`:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightHudBinderTest {
    @Test
    void hudUiContainsExpectedSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src", "main", "resources", "Common", "UI", "Custom", "TameworkAvatarFlightHud.ui"
        ), StandardCharsets.UTF_8);
        String binder = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "ui", "AvatarFlightHudBinder.java"
        ), StandardCharsets.UTF_8);

        assertTrue(ui.contains("Group #Root"));
        assertTrue(ui.contains("Group #SpeedFill"));
        assertTrue(ui.contains("Group #VigourPip0"));
        assertTrue(ui.contains("Group #VigourPip5"));
        assertTrue(binder.contains("#SpeedFill.Anchor"));
        assertTrue(binder.contains("#VigourPip0 #Fill.Anchor"));
        assertTrue(binder.contains("#Root.Visible"));
    }
}
```

- [ ] **Step 3: Run focused HUD tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightHudViewModelTest,AvatarFlightHudBinderTest,AvatarFlightHudSystemArchitectureTest test
```

Expected: FAIL because the HUD files do not exist.

- [ ] **Step 4: Implement `AvatarFlightHudViewModel`**

Create:

```java
package com.alechilles.alecstamework.avatarflight;

import javax.annotation.Nonnull;

/** Immutable presentation state for the compact avatar-flight Vigour HUD. */
public record AvatarFlightHudViewModel(boolean visible,
                                       double speedRatio,
                                       double vigourCharges,
                                       int maxVigourCharges,
                                       boolean dimmed,
                                       @Nonnull String rechargeMode) {
    @Nonnull
    public static AvatarFlightHudViewModel hidden() {
        return new AvatarFlightHudViewModel(false, 0.0, 0.0, 0, false, "");
    }

    @Nonnull
    public static AvatarFlightHudViewModel visible(double speedRatio,
                                                   double vigourCharges,
                                                   int maxVigourCharges,
                                                   boolean groundedAtFull,
                                                   @Nonnull String rechargeMode) {
        double clampedSpeed = clamp01(speedRatio);
        int max = Math.max(0, maxVigourCharges);
        double charges = Math.max(0.0, Math.min(max, vigourCharges));
        return new AvatarFlightHudViewModel(true, clampedSpeed, charges, max, groundedAtFull, rechargeMode);
    }

    public double pipFill(int index) {
        if (index < 0 || index >= maxVigourCharges) {
            return 0.0;
        }
        return clamp01(vigourCharges - index);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, Double.isFinite(value) ? value : 0.0));
    }
}
```

- [ ] **Step 5: Create the UI asset**

Create `TameworkAvatarFlightHud.ui`:

```text
Group #Root {
    Anchor: (Bottom: 84, Width: 176, Height: 34);
    Background: #081220(0.64);
    Visible: false;

    Group #SpeedTrack {
        Anchor: (Top: 5, Left: 10, Width: 156, Height: 5);
        Background: #223247(0.90);

        Group #SpeedFill {
            Anchor: (Top: 0, Left: 0, Width: 0, Height: 5);
            Background: #7cc6f2;
        }
    }

    Group #PipRow {
        Anchor: (Top: 16, Left: 22, Width: 132, Height: 12);
        LayoutMode: Left;

        Group #VigourPip0 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
        Group { Anchor: (Width: 12); }
        Group #VigourPip1 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
        Group { Anchor: (Width: 12); }
        Group #VigourPip2 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
        Group { Anchor: (Width: 12); }
        Group #VigourPip3 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
        Group { Anchor: (Width: 12); }
        Group #VigourPip4 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
        Group { Anchor: (Width: 12); }
        Group #VigourPip5 { Anchor: (Width: 12, Height: 12); Background: #1c2a3d; Group #Fill { Anchor: (Left: 0, Top: 0, Width: 0, Height: 12); Background: #f6d56b; } }
    }
}
```

This root anchor intentionally omits `Left` and `Right`; base-game HUD assets such as `InGame/Hud/Speedometer.ui`, `InGame/Hud/StatusIcons.ui`, and `InGame/Hud/Oxygen/Oxygen.ui` use `Anchor: (Bottom: ..., Width: ..., Height: ...)` for centered bottom HUD elements.

- [ ] **Step 6: Implement `TameworkAvatarFlightHud` and binder**

Create `TameworkAvatarFlightHud`:

```java
package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.avatarflight.AvatarFlightHudViewModel;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** Compact center HUD for transformed avatar-flight speed and Vigour. */
public final class TameworkAvatarFlightHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:avatar_flight";
    public static final String UI_PATH = "TameworkAvatarFlightHud.ui";

    private AvatarFlightHudViewModel model;

    public TameworkAvatarFlightHud(@Nonnull PlayerRef playerRef, @Nonnull AvatarFlightHudViewModel model) {
        super(playerRef, HUD_KEY);
        this.model = model;
    }

    public void refresh(@Nonnull AvatarFlightHudViewModel updatedModel) {
        model = updatedModel;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        AvatarFlightHudBinder.bind(commandBuilder, updatedModel);
        update(false, commandBuilder);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        AvatarFlightHudBinder.bind(commandBuilder, model);
    }
}
```

Create binder:

```java
package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.avatarflight.AvatarFlightHudViewModel;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Binds speed and Vigour values into the avatar-flight HUD asset. */
final class AvatarFlightHudBinder {
    private static final int SPEED_FILL_MAX_WIDTH = 156;
    private static final int PIP_FILL_MAX_WIDTH = 12;
    private static final int MAX_PIPS = 6;

    private AvatarFlightHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder, @Nonnull AvatarFlightHudViewModel model) {
        commandBuilder.set("#Root.Visible", model.visible());
        commandBuilder.set("#Root.Background", model.dimmed() ? "#081220(0.32)" : "#081220(0.64)");
        commandBuilder.setObject("#SpeedFill.Anchor", fillAnchor(SPEED_FILL_MAX_WIDTH, model.speedRatio(), 5));
        for (int i = 0; i < MAX_PIPS; i++) {
            boolean visible = i < model.maxVigourCharges();
            String selector = "#VigourPip" + i;
            commandBuilder.set(selector + ".Visible", visible);
            commandBuilder.setObject(selector + " #Fill.Anchor", fillAnchor(PIP_FILL_MAX_WIDTH, model.pipFill(i), 12));
        }
    }

    @Nonnull
    private static Anchor fillAnchor(int maxWidth, double ratio, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(0));
        anchor.setLeft(Value.of(0));
        anchor.setWidth(Value.of(Math.max(0, (int) Math.round(maxWidth * Math.max(0.0, Math.min(1.0, ratio))))));
        anchor.setHeight(Value.of(height));
        return anchor;
    }
}
```

- [ ] **Step 7: Implement `AvatarFlightHudSystem`**

Create a system following the `CommandTargetHudService` HUD-manager pattern:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.ui.TameworkAvatarFlightHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Shows and refreshes the compact avatar-flight Vigour HUD for active transformed players. */
public final class AvatarFlightHudSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, Player> playerType;
    private final Query<EntityStore> query;
    private final Map<UUID, HudState> hudByPlayer = new HashMap<>();

    public AvatarFlightHudSystem(@Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
                                 @Nonnull ComponentType<EntityStore, Player> playerType) {
        this.flightType = flightType;
        this.playerType = playerType;
        this.query = Query.and(flightType, playerType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        AvatarFlightComponent flight = chunk.getComponent(index, flightType);
        Player player = chunk.getComponent(index, playerType);
        if (ref == null || flight == null || player == null || player.getUuid() == null) {
            return;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        if (!config.getVigour().isHudEnabled()) {
            hide(player.getUuid(), player);
            return;
        }
        AvatarFlightHudViewModel model = buildModel(flight, config);
        showOrRefresh(player.getUuid(), player, model, config.getVigour().getHudResendIntervalMs());
    }

    @Nonnull
    private static AvatarFlightHudViewModel buildModel(@Nonnull AvatarFlightComponent flight,
                                                       @Nonnull TwAvatarFlightConfig config) {
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                flight.getVelocityX(),
                flight.getVelocityY(),
                flight.getVelocityZ()
        );
        int max = (int) Math.round(config.getVigour().getMaxCharges());
        boolean groundedFull = flight.getMode() == AvatarFlightMode.GROUNDED
                && flight.getVigourCharges() >= config.getVigour().getMaxCharges();
        return AvatarFlightHudViewModel.visible(
                AvatarFlightSpeedMetrics.speedRatio(horizontalSpeed, config),
                flight.getVigourCharges(),
                max,
                groundedFull,
                flight.getVigourRechargeMode()
        );
    }

    private void showOrRefresh(@Nonnull UUID playerUuid,
                               @Nonnull Player player,
                               @Nonnull AvatarFlightHudViewModel model,
                               long resendIntervalMs) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || player.getHudManager() == null) {
            hudByPlayer.remove(playerUuid);
            return;
        }
        long now = System.currentTimeMillis();
        HudState previous = hudByPlayer.get(playerUuid);
        if (previous != null && previous.model().equals(model) && now < previous.nextRefreshAtMs()) {
            return;
        }
        TameworkAvatarFlightHud hud = previous == null ? null : previous.hud();
        if (hud == null) {
            hud = new TameworkAvatarFlightHud(playerRef, model);
            player.getHudManager().addCustomHud(playerRef, hud);
        } else {
            hud.refresh(model);
        }
        hudByPlayer.put(playerUuid, new HudState(hud, model, now + resendIntervalMs));
    }

    private void hide(@Nonnull UUID playerUuid, @Nonnull Player player) {
        HudState previous = hudByPlayer.remove(playerUuid);
        if (previous == null || player.getPlayerRef() == null || player.getHudManager() == null) {
            return;
        }
        player.getHudManager().removeCustomHud(player.getPlayerRef(), TameworkAvatarFlightHud.HUD_KEY);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    private record HudState(@Nonnull TameworkAvatarFlightHud hud,
                            @Nonnull AvatarFlightHudViewModel model,
                            long nextRefreshAtMs) {
    }
}
```

- [ ] **Step 8: Register HUD system**

In `Tamework`, import `AvatarFlightHudSystem` and register it after `AvatarFlightMovementSystem`:

```java
ComponentType<EntityStore, Player> playerComponentType = Player.getComponentType();
if (playerComponentType != null) {
    getEntityStoreRegistry().registerSystem(
            new AvatarFlightHudSystem(avatarFlightComponentType, playerComponentType)
    );
}
```

- [ ] **Step 9: Run focused HUD tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightHudViewModelTest,AvatarFlightHudBinderTest,AvatarFlightHudSystemArchitectureTest test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModel.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystem.java src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightHud.java src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudViewModelTest.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightHudSystemArchitectureTest.java src/test/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinderTest.java
git commit -m "Feat: add avatar flight vigour HUD"
```

---

### Task 6: Diagnostics, Docs, and Verification

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- Modify: `CHANGELOG.md`
- Create: `docs/Avatar-Flight.md`
- Modify: `docs/agents/generated-index.md`

- [ ] **Step 1: Extend debug logging**

In `AvatarFlightMovementSystem.maybeLogDebug`, append these fields to the log format:

```java
+ " vigour=%.2f/%d recharge=%s speedRatio=%.2f"
```

Pass:

```java
flight.getVigourCharges(),
(int) Math.round(config.getVigour().getMaxCharges()),
flight.getVigourRechargeMode(),
AvatarFlightSpeedMetrics.speedRatio(
        AvatarFlightSpeedMetrics.horizontalSpeed(output.velocityX(), output.velocityY(), output.velocityZ()),
        config
)
```

Update the method signature to accept `AvatarFlightComponent flight` and `TwAvatarFlightConfig config`.

- [ ] **Step 2: Update changelog**

Add one entry under the current unreleased section:

```markdown
- Added Vigour charges and a compact speed/Vigour HUD for transformed avatar flight, with upward flaps and forward boosts spending charges and recharge limited to grounded recovery or high-speed flight.
```

- [ ] **Step 3: Add avatar-flight docs**

Create `docs/Avatar-Flight.md`:

```markdown
# Avatar Flight

Avatar flight is the transformed-player flight path used by dragon-style mounts. The real player becomes the flight model, while Tamework can attach a visual rider copy for the seated player appearance.

## Controls

- Forward movement starts or resumes glide.
- Mouse look controls heading and pitch.
- Left-click with Flightmaster's Talisman performs an upward flap.
- Right-click with Flightmaster's Talisman applies the airbrake.
- Crouch applies direct downward movement.
- Forward boost uses the configured boost input/action and spends Vigour.

## Vigour

Vigour is a charge resource for movement abilities. Successful upward flaps and forward boosts spend charges. When Vigour reaches zero, movement abilities stop applying until charges recover.

Default balance:

- `MaxCharges`: 6.
- `UpwardFlapCost`: 1.
- `ForwardBoostCost`: 1.
- `GroundedRechargeSecondsPerCharge`: 4.
- `FastFlightRechargeSecondsPerCharge`: 8.
- `FastFlightRechargeSpeedRatio`: 0.75 of boosted horizontal speed cap.
- `RechargeDelayAfterSpendSeconds`: 0.75.

Fast-flight recharge uses boosted max speed, calculated as `Movement.MaxForwardSpeed + Boost.ForwardImpulse`. With defaults, the threshold is `(14 + 7) * 0.75 = 15.75`, so ordinary cruise at `14` speed does not recharge by itself.

## HUD

The compact avatar-flight HUD appears above the hotbar while avatar flight is active. The upper bar shows current horizontal speed relative to boosted max speed. The pips show Vigour charges, including partial recharge progress.

## Config Fields

### Movement

- `GlideSinkSpeed`: target downward speed for unpowered forward glide.
- `GlideSinkAcceleration`: rate at which glide approaches the sink speed.

### Vigour

- `Enabled`: enables charge spending and recharge.
- `MaxCharges`: maximum charges.
- `UpwardFlapCost`: charge cost for a successful flap.
- `ForwardBoostCost`: charge cost for a successful boost.
- `GroundedRechargeSecondsPerCharge`: grounded recharge rate.
- `FastFlightRechargeSecondsPerCharge`: airborne fast-flight recharge rate.
- `FastFlightRechargeSpeedRatio`: required ratio of boosted horizontal speed cap.
- `RechargeDelayAfterSpendSeconds`: delay before recharge resumes after spending.
- `HudEnabled`: shows the compact speed and Vigour HUD.
- `HudResendIntervalMs`: throttles unchanged HUD refreshes.
```

- [ ] **Step 4: Rebuild the agent index**

Run:

```powershell
.\scripts\tools\build-agent-index.ps1
```

Expected: `docs/agents/generated-index.md` updates to include `docs/Avatar-Flight.md`.

- [ ] **Step 5: Run agent docs check**

Run:

```powershell
.\scripts\tools\check-agent-docs.ps1
```

Expected: PASS.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TwAvatarFlightConfigTest,AvatarFlight*Test,AvatarFlightHudBinderTest test
```

Expected: PASS.

- [ ] **Step 7: Run thread-safety checks**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: no new unsafe player access in avatar-flight tick paths and guard tests PASS. The new HUD system may contain a registration-time `Player.getComponentType()` call; that is acceptable only outside tick code, while HUD tick logic must use current-store query/chunk components.

- [ ] **Step 8: Run full test suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 9: Package for local runtime testing**

Run:

```powershell
.\mvnw.cmd package -DskipTests
```

Expected: build succeeds and produces the mod jar under `target`.

- [ ] **Step 10: Runtime smoke test**

Use the repo's normal local deployment flow, then test in-game:

- Enable `/tw debugdragonflight`.
- Confirm the HUD appears above the hotbar.
- Confirm six Vigour pips are full at start.
- Left-click flap six times and confirm pips spend one at a time.
- Confirm flap does not apply at zero charges.
- Confirm forward boost spends one charge.
- Stand grounded for four seconds and confirm one charge returns.
- Fly below fast threshold and confirm no airborne recharge.
- Dive or boost above the fast threshold and confirm one charge returns after eight qualifying seconds, plus the post-spend delay if a charge was just spent.
- Confirm normal W cruise at roughly `14` speed does not recharge by itself.
- Confirm unpowered forward glide sinks enough that zero-Vigour flight eventually needs landing.
- Confirm the HUD dims when grounded and full.

- [ ] **Step 11: Commit docs and diagnostics**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java CHANGELOG.md docs/Avatar-Flight.md docs/agents/generated-index.md
git commit -m "Docs: document avatar flight vigour"
```

---

## Spec Coverage Review

- Configurable Vigour resource: Tasks 1-3.
- Spend on upward flap and forward boost: Task 3.
- Smooth partial charge recovery: Tasks 2, 3, and 5.
- Grounded and fast-flight recharge: Tasks 1-3.
- Mathematically sound tuning: Tasks 2 and 4, with explicit threshold/rate tests.
- Compact center HUD above hotbar: Task 5.
- Debug visibility for tuning: Task 6.
- Glide cannot sustain indefinitely without spending: Task 4.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-07-avatar-flight-vigour-hud-implementation.md`. Two execution options:

1. **Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.
