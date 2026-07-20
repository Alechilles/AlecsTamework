# Avatar Flight Post-1981e83 Tuning And Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the post-`1981e83c` avatar-flight tuning delta: curve-based dive/climb momentum, directional Q boost, boosted-speed decay, and charged ground launch.

**Architecture:** Keep item and packet handlers as intent capture only. Put reusable math in focused avatar-flight helpers, keep `AvatarFlightController` as the pure motion coordinator, and let `AvatarFlightMovementSystem` remain the authority for Vigour authorization/spending and component persistence. Add config through inherited Tamework config sections without breaking existing asset files.

**Tech Stack:** Java, Hytale ECS/components, Tamework `TwAvatarFlightConfig`, JUnit 5, Maven wrapper, JSON assets under `src/main/resources/Server/Tamework/AvatarFlight`.

---

## Baseline

Start from commit `1981e83c` or a later branch that only adds docs after it. The code baseline already has:

- one-shot Reins/Q boost queueing;
- Vigour spending and recharge;
- neutral glide speed, natural glide cap, boosted cap;
- Reins flap, Reins airbrake, Q boost, crouch descent;
- HUD overlay fix.

Keep existing untracked `outputs/` ignored unless the user explicitly asks to clean it.

## File Structure

Create:

- `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightCurveSettings.java`
  Owns curve tuning codec, defaults, inheritance copy, and getters for dive/climb/boosted-speed decay.
- `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightLaunchSettings.java`
  Owns launch tuning codec, defaults, cost selection, normalized charge calculation, and getters.
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMath.java`
  Pure helper for dive load, climb load, pitch power, climb speed eligibility, and boosted-excess decay.
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurve.java`
  Pure helper for launch charge normalization, impulse calculation, and launch cost.
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMathTest.java`
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurveTest.java`

Modify:

- `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponent.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightPacketInputCapture.java`
- `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInteractionControlService.java`
- `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
- `src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java`
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java`
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponentTest.java`
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`
- `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystemTest.java`
- `docs/Avatar-Flight.md`

Because `TwAvatarFlightConfig.java` is over 1000 lines, do not add more nested setting classes to it. Add new setting sections as top-level classes and keep the config class changes limited to codec wiring, fields, inheritance delegation, and getters.

---

### Task 1: Add Config Surfaces For Curves And Launch

**Files:**

- Create: `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightCurveSettings.java`
- Create: `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightLaunchSettings.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
- Modify: `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
- Test: `src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java`

- [ ] **Step 1: Add failing config tests**

Append these test methods to `TwAvatarFlightConfigTest`:

```java
@Test
void defaultConfigExposesCurveAndLaunchValues() {
    TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

    assertEquals(1.6, config.getCurve().getDiveLoadRampSeconds(), 0.00001);
    assertEquals(0.6, config.getCurve().getDiveLoadDecaySeconds(), 0.00001);
    assertEquals(1.55, config.getCurve().getDivePitchExponent(), 0.00001);
    assertEquals(1.1, config.getCurve().getClimbLoadRampSeconds(), 0.00001);
    assertEquals(0.6, config.getCurve().getClimbLoadDecaySeconds(), 0.00001);
    assertEquals(1.35, config.getCurve().getClimbPitchExponent(), 0.00001);
    assertEquals(0.5, config.getCurve().getClimbSpeedEligibilityExponent(), 0.00001);
    assertEquals(2.0, config.getCurve().getBoostedSpeedDecay(), 0.00001);

    assertTrue(config.getLaunch().isEnabled());
    assertEquals("JumpHold", config.getLaunch().getPreferredInput());
    assertEquals("ReinsPrimaryHold", config.getLaunch().getFallbackInput());
    assertEquals(500L, config.getLaunch().getMinChargeMs());
    assertEquals(3000L, config.getLaunch().getMaxChargeMs());
    assertEquals(0.65, config.getLaunch().getChargeExponent(), 0.00001);
    assertEquals(6.0, config.getLaunch().getMinUpImpulse(), 0.00001);
    assertEquals(18.0, config.getLaunch().getMaxUpImpulse(), 0.00001);
    assertEquals(6.0, config.getLaunch().getMinForwardImpulse(), 0.00001);
    assertEquals(11.0, config.getLaunch().getMaxForwardImpulse(), 0.00001);
    assertEquals(1.0, config.getLaunch().getPartialChargeCost(), 0.00001);
    assertEquals(2.0, config.getLaunch().getFullChargeCost(), 0.00001);
    assertEquals(0.6, config.getLaunch().getFullChargeCostThreshold(), 0.00001);
}

@Test
void explicitCurveAndLaunchSectionsInheritMissingNestedKeys() throws Exception {
    TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
    TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
    setNestedField(parent, "curve", "diveLoadRampSeconds", 2.4);
    setNestedField(parent, "curve", "climbPitchExponent", 1.8);
    setNestedField(child, "curve", "diveLoadRampSeconds", 0.9);
    setNestedField(child, "curve", "climbPitchExponent", 1.1);
    setNestedField(parent, "launch", "maxUpImpulse", 22.0);
    setNestedField(parent, "launch", "partialChargeCost", 0.5);
    setNestedField(child, "launch", "maxUpImpulse", 12.0);
    setNestedField(child, "launch", "partialChargeCost", 3.0);

    child.inheritMissingTopLevelFrom(
            parent,
            Set.of("Curve", "Launch"),
            Map.of(
                    "Curve", Set.of("DiveLoadRampSeconds"),
                    "Launch", Set.of("MaxUpImpulse")
            )
    );

    assertEquals(0.9, child.getCurve().getDiveLoadRampSeconds(), 0.00001);
    assertEquals(1.8, child.getCurve().getClimbPitchExponent(), 0.00001);
    assertEquals(12.0, child.getLaunch().getMaxUpImpulse(), 0.00001);
    assertEquals(0.5, child.getLaunch().getPartialChargeCost(), 0.00001);
}
```

- [ ] **Step 2: Run failing config tests**

Run:

```powershell
.\mvnw -Dtest=TwAvatarFlightConfigTest test
```

Expected: compilation fails because `getCurve()`, `getLaunch()`, `curve`, and `launch` do not exist.

- [ ] **Step 3: Create `AvatarFlightCurveSettings`**

Create `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightCurveSettings.java`:

```java
package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Configurable curve tuning for avatar-flight dive, climb, and boosted-speed decay. */
public final class AvatarFlightCurveSettings {
    public static final BuilderCodec<AvatarFlightCurveSettings> CODEC = BuilderCodec.builder(
            AvatarFlightCurveSettings.class,
            AvatarFlightCurveSettings::new
    )
            .<Double>append(new KeyedCodec<>("DiveLoadRampSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.diveLoadRampSeconds = positiveOrDefault(value, 1.6),
                    settings -> settings.diveLoadRampSeconds)
            .documentation("Seconds for sustained pitch-down load to reach full strength. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("DiveLoadDecaySeconds", Codec.DOUBLE),
                    (settings, value) -> settings.diveLoadDecaySeconds = positiveOrDefault(value, 0.6),
                    settings -> settings.diveLoadDecaySeconds)
            .documentation("Seconds for dive load to decay when pitch-down stops. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("DivePitchExponent", Codec.DOUBLE),
                    (settings, value) -> settings.divePitchExponent = positiveOrDefault(value, 1.55),
                    settings -> settings.divePitchExponent)
            .documentation("Exponent applied to normalized downward pitch for dive speed gain. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoadRampSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.climbLoadRampSeconds = positiveOrDefault(value, 1.1),
                    settings -> settings.climbLoadRampSeconds)
            .documentation("Seconds for sustained pitch-up load to reach full strength. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoadDecaySeconds", Codec.DOUBLE),
                    (settings, value) -> settings.climbLoadDecaySeconds = positiveOrDefault(value, 0.6),
                    settings -> settings.climbLoadDecaySeconds)
            .documentation("Seconds for climb load to decay when pitch-up stops. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbPitchExponent", Codec.DOUBLE),
                    (settings, value) -> settings.climbPitchExponent = positiveOrDefault(value, 1.35),
                    settings -> settings.climbPitchExponent)
            .documentation("Exponent applied to normalized upward pitch for climb lift and drag. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbSpeedEligibilityExponent", Codec.DOUBLE),
                    (settings, value) -> settings.climbSpeedEligibilityExponent = positiveOrDefault(value, 0.5),
                    settings -> settings.climbSpeedEligibilityExponent)
            .documentation("Exponent applied to normalized speed above neutral when calculating climb eligibility. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("BoostedSpeedDecay", Codec.DOUBLE),
                    (settings, value) -> settings.boostedSpeedDecay = nonNegativeOrDefault(value, 2.0),
                    settings -> settings.boostedSpeedDecay)
            .documentation("Horizontal speed per second removed from boosted excess after boost is no longer active. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    double diveLoadRampSeconds = 1.6;
    double diveLoadDecaySeconds = 0.6;
    double divePitchExponent = 1.55;
    double climbLoadRampSeconds = 1.1;
    double climbLoadDecaySeconds = 0.6;
    double climbPitchExponent = 1.35;
    double climbSpeedEligibilityExponent = 0.5;
    double boostedSpeedDecay = 2.0;

    public void inheritMissingFrom(@Nonnull AvatarFlightCurveSettings parent, @Nullable Set<String> keys) {
        if (keys == null) {
            return;
        }
        if (!keys.contains("DiveLoadRampSeconds")) diveLoadRampSeconds = parent.diveLoadRampSeconds;
        if (!keys.contains("DiveLoadDecaySeconds")) diveLoadDecaySeconds = parent.diveLoadDecaySeconds;
        if (!keys.contains("DivePitchExponent")) divePitchExponent = parent.divePitchExponent;
        if (!keys.contains("ClimbLoadRampSeconds")) climbLoadRampSeconds = parent.climbLoadRampSeconds;
        if (!keys.contains("ClimbLoadDecaySeconds")) climbLoadDecaySeconds = parent.climbLoadDecaySeconds;
        if (!keys.contains("ClimbPitchExponent")) climbPitchExponent = parent.climbPitchExponent;
        if (!keys.contains("ClimbSpeedEligibilityExponent")) {
            climbSpeedEligibilityExponent = parent.climbSpeedEligibilityExponent;
        }
        if (!keys.contains("BoostedSpeedDecay")) boostedSpeedDecay = parent.boostedSpeedDecay;
    }

    public double getDiveLoadRampSeconds() { return Math.max(0.001, diveLoadRampSeconds); }
    public double getDiveLoadDecaySeconds() { return Math.max(0.001, diveLoadDecaySeconds); }
    public double getDivePitchExponent() { return Math.max(0.001, divePitchExponent); }
    public double getClimbLoadRampSeconds() { return Math.max(0.001, climbLoadRampSeconds); }
    public double getClimbLoadDecaySeconds() { return Math.max(0.001, climbLoadDecaySeconds); }
    public double getClimbPitchExponent() { return Math.max(0.001, climbPitchExponent); }
    public double getClimbSpeedEligibilityExponent() { return Math.max(0.001, climbSpeedEligibilityExponent); }
    public double getBoostedSpeedDecay() { return Math.max(0.0, boostedSpeedDecay); }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
```

- [ ] **Step 4: Create `AvatarFlightLaunchSettings`**

Create `src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightLaunchSettings.java`:

```java
package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Configurable charged-launch tuning for avatar flight. */
public final class AvatarFlightLaunchSettings {
    public static final String INPUT_JUMP_HOLD = "JumpHold";
    public static final String INPUT_REINS_PRIMARY_HOLD = "ReinsPrimaryHold";

    public static final BuilderCodec<AvatarFlightLaunchSettings> CODEC = BuilderCodec.builder(
            AvatarFlightLaunchSettings.class,
            AvatarFlightLaunchSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether charged launch is available for this avatar-flight profile. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PreferredInput", Codec.STRING),
                    (settings, value) -> settings.preferredInput = inputOrDefault(value, INPUT_JUMP_HOLD),
                    settings -> settings.preferredInput)
            .documentation("Primary charged-launch input path. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FallbackInput", Codec.STRING),
                    (settings, value) -> settings.fallbackInput = inputOrDefault(value, INPUT_REINS_PRIMARY_HOLD),
                    settings -> settings.fallbackInput)
            .documentation("Fallback charged-launch input path if preferred input is not viable. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinChargeMs", Codec.DOUBLE),
                    (settings, value) -> settings.minChargeMs = positiveOrDefault(value, 500.0),
                    settings -> settings.minChargeMs)
            .documentation("Minimum hold duration before release launches instead of normal tap behavior. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxChargeMs", Codec.DOUBLE),
                    (settings, value) -> settings.maxChargeMs = positiveOrDefault(value, 3000.0),
                    settings -> settings.maxChargeMs)
            .documentation("Hold duration that reaches full launch charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ChargeExponent", Codec.DOUBLE),
                    (settings, value) -> settings.chargeExponent = positiveOrDefault(value, 0.65),
                    settings -> settings.chargeExponent)
            .documentation("Exponent applied to normalized hold duration. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinUpImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.minUpImpulse = nonNegativeOrDefault(value, 6.0),
                    settings -> settings.minUpImpulse)
            .documentation("Vertical impulse at minimum charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxUpImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.maxUpImpulse = nonNegativeOrDefault(value, 18.0),
                    settings -> settings.maxUpImpulse)
            .documentation("Vertical impulse at full charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinForwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.minForwardImpulse = nonNegativeOrDefault(value, 6.0),
                    settings -> settings.minForwardImpulse)
            .documentation("Forward impulse at minimum charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxForwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.maxForwardImpulse = nonNegativeOrDefault(value, 11.0),
                    settings -> settings.maxForwardImpulse)
            .documentation("Forward impulse at full charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("PartialChargeCost", Codec.DOUBLE),
                    (settings, value) -> settings.partialChargeCost = nonNegativeOrDefault(value, 1.0),
                    settings -> settings.partialChargeCost)
            .documentation("Vigour cost below the full-charge cost threshold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FullChargeCost", Codec.DOUBLE),
                    (settings, value) -> settings.fullChargeCost = nonNegativeOrDefault(value, 2.0),
                    settings -> settings.fullChargeCost)
            .documentation("Vigour cost at or above the full-charge cost threshold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FullChargeCostThreshold", Codec.DOUBLE),
                    (settings, value) -> settings.fullChargeCostThreshold = clamp01(value, 0.6),
                    settings -> settings.fullChargeCostThreshold)
            .documentation("Normalized charge threshold for FullChargeCost. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    boolean enabled = true;
    String preferredInput = INPUT_JUMP_HOLD;
    String fallbackInput = INPUT_REINS_PRIMARY_HOLD;
    double minChargeMs = 500.0;
    double maxChargeMs = 3000.0;
    double chargeExponent = 0.65;
    double minUpImpulse = 6.0;
    double maxUpImpulse = 18.0;
    double minForwardImpulse = 6.0;
    double maxForwardImpulse = 11.0;
    double partialChargeCost = 1.0;
    double fullChargeCost = 2.0;
    double fullChargeCostThreshold = 0.6;

    public void inheritMissingFrom(@Nonnull AvatarFlightLaunchSettings parent, @Nullable Set<String> keys) {
        if (keys == null) {
            return;
        }
        if (!keys.contains("Enabled")) enabled = parent.enabled;
        if (!keys.contains("PreferredInput")) preferredInput = parent.preferredInput;
        if (!keys.contains("FallbackInput")) fallbackInput = parent.fallbackInput;
        if (!keys.contains("MinChargeMs")) minChargeMs = parent.minChargeMs;
        if (!keys.contains("MaxChargeMs")) maxChargeMs = parent.maxChargeMs;
        if (!keys.contains("ChargeExponent")) chargeExponent = parent.chargeExponent;
        if (!keys.contains("MinUpImpulse")) minUpImpulse = parent.minUpImpulse;
        if (!keys.contains("MaxUpImpulse")) maxUpImpulse = parent.maxUpImpulse;
        if (!keys.contains("MinForwardImpulse")) minForwardImpulse = parent.minForwardImpulse;
        if (!keys.contains("MaxForwardImpulse")) maxForwardImpulse = parent.maxForwardImpulse;
        if (!keys.contains("PartialChargeCost")) partialChargeCost = parent.partialChargeCost;
        if (!keys.contains("FullChargeCost")) fullChargeCost = parent.fullChargeCost;
        if (!keys.contains("FullChargeCostThreshold")) fullChargeCostThreshold = parent.fullChargeCostThreshold;
    }

    public boolean isEnabled() { return enabled; }
    public String getPreferredInput() { return inputOrDefault(preferredInput, INPUT_JUMP_HOLD); }
    public String getFallbackInput() { return inputOrDefault(fallbackInput, INPUT_REINS_PRIMARY_HOLD); }
    public long getMinChargeMs() { return Math.round(Math.max(0.0, minChargeMs)); }
    public long getMaxChargeMs() { return Math.max(getMinChargeMs() + 1L, Math.round(Math.max(1.0, maxChargeMs))); }
    public double getChargeExponent() { return Math.max(0.001, chargeExponent); }
    public double getMinUpImpulse() { return Math.max(0.0, minUpImpulse); }
    public double getMaxUpImpulse() { return Math.max(getMinUpImpulse(), maxUpImpulse); }
    public double getMinForwardImpulse() { return Math.max(0.0, minForwardImpulse); }
    public double getMaxForwardImpulse() { return Math.max(getMinForwardImpulse(), maxForwardImpulse); }
    public double getPartialChargeCost() { return Math.max(0.0, partialChargeCost); }
    public double getFullChargeCost() { return Math.max(0.0, fullChargeCost); }
    public double getFullChargeCostThreshold() { return Math.max(0.0, Math.min(1.0, fullChargeCostThreshold)); }

    private static String inputOrDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static double clamp01(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, resolved));
    }
}
```

- [ ] **Step 5: Wire new settings into `TwAvatarFlightConfig`**

Modify `TwAvatarFlightConfig`:

```java
// Add fields beside existing sections.
private AvatarFlightCurveSettings curve = new AvatarFlightCurveSettings();
private AvatarFlightLaunchSettings launch = new AvatarFlightLaunchSettings();

// Add CODEC sections after Boost and before Vigour.
.<AvatarFlightCurveSettings>append(new KeyedCodec<>("Curve", AvatarFlightCurveSettings.CODEC),
        (asset, value) -> asset.curve = value == null ? new AvatarFlightCurveSettings() : value,
        asset -> asset.curve)
.documentation("Avatar flight dive/climb curve tuning. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
.add()
.<AvatarFlightLaunchSettings>append(new KeyedCodec<>("Launch", AvatarFlightLaunchSettings.CODEC),
        (asset, value) -> asset.launch = value == null ? new AvatarFlightLaunchSettings() : value,
        asset -> asset.launch)
.documentation("Charged ground launch tuning. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
.add()

// Add inheritance calls.
inheritOrCopyCurve(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Curve"), explicitTopLevelKeys);
inheritOrCopyLaunch(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Launch"), explicitTopLevelKeys);

// Add helper methods.
private void inheritOrCopyCurve(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
    if (!top.contains("Curve")) curve = parent.curve;
    else if (keys != null && curve != null && parent.curve != null) {
        curve.inheritMissingFrom(parent.curve, keys);
    }
}

private void inheritOrCopyLaunch(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
    if (!top.contains("Launch")) launch = parent.launch;
    else if (keys != null && launch != null && parent.launch != null) {
        launch.inheritMissingFrom(parent.launch, keys);
    }
}

// Add getters.
public AvatarFlightCurveSettings getCurve() { return curve == null ? new AvatarFlightCurveSettings() : curve; }
public AvatarFlightLaunchSettings getLaunch() { return launch == null ? new AvatarFlightLaunchSettings() : launch; }
```

Place the inheritance calls in the same order as the codec sections.

- [ ] **Step 6: Update the default avatar flight asset**

Modify `Tamework_Avatar_Flight_Default.json` by adding:

```json
  "Curve": {
    "DiveLoadRampSeconds": 1.6,
    "DiveLoadDecaySeconds": 0.6,
    "DivePitchExponent": 1.55,
    "ClimbLoadRampSeconds": 1.1,
    "ClimbLoadDecaySeconds": 0.6,
    "ClimbPitchExponent": 1.35,
    "ClimbSpeedEligibilityExponent": 0.5,
    "BoostedSpeedDecay": 2.0
  },
  "Launch": {
    "Enabled": true,
    "PreferredInput": "JumpHold",
    "FallbackInput": "ReinsPrimaryHold",
    "MinChargeMs": 500.0,
    "MaxChargeMs": 3000.0,
    "ChargeExponent": 0.65,
    "MinUpImpulse": 6.0,
    "MaxUpImpulse": 18.0,
    "MinForwardImpulse": 6.0,
    "MaxForwardImpulse": 11.0,
    "PartialChargeCost": 1.0,
    "FullChargeCost": 2.0,
    "FullChargeCostThreshold": 0.6
  },
```

Put `Curve` after `Movement` and `Launch` after `Boost` to match the config code.

- [ ] **Step 7: Run config tests**

Run:

```powershell
.\mvnw -Dtest=TwAvatarFlightConfigTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit config surfaces**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java `
        src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightCurveSettings.java `
        src/main/java/com/alechilles/alecstamework/config/assets/AvatarFlightLaunchSettings.java `
        src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json `
        src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java
git commit -m "Feat: add avatar flight curve and launch config"
```

---

### Task 2: Add Persistent Controller And Launch Input State

**Files:**

- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponent.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponentTest.java`

- [ ] **Step 1: Add failing component state tests**

Create `AvatarFlightComponentTest` if it does not exist:

```java
package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightComponentTest {

    @Test
    void maneuverLoadsAreFiniteAndClone() {
        AvatarFlightComponent component = new AvatarFlightComponent("Test", 1000L);
        component.setDiveLoad(0.75);
        component.setClimbLoad(Double.NaN);
        component.setNextLaunchAtMs(1500L);

        AvatarFlightComponent clone = component.clone();

        assertEquals(0.75, clone.getDiveLoad(), 0.00001);
        assertEquals(0.0, clone.getClimbLoad(), 0.00001);
        assertEquals(1500L, clone.getNextLaunchAtMs());
    }
}
```

Append to `AvatarFlightInputComponentTest`:

```java
@Test
void launchReleaseIsQueuedOnceWithHoldDuration() {
    AvatarFlightInputComponent input = new AvatarFlightInputComponent();

    input.beginLaunchCharge(1_000L);
    input.queueLaunchRelease(2_000L);

    assertEquals(1_000L, input.getLaunchHoldMs());
    assertTrue(input.consumeLaunchRelease(2_100L, 1_000L));
    assertFalse(input.consumeLaunchRelease(2_100L, 1_000L));
}

@Test
void staleLaunchReleaseIsConsumedWithoutApplying() {
    AvatarFlightInputComponent input = new AvatarFlightInputComponent();

    input.beginLaunchCharge(1_000L);
    input.queueLaunchRelease(2_000L);

    assertFalse(input.consumeLaunchRelease(4_000L, 1_000L));
    assertFalse(input.consumeLaunchRelease(4_000L, 1_000L));
}
```

- [ ] **Step 2: Run failing state tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightComponentTest,AvatarFlightInputComponentTest test
```

Expected: compilation fails because launch and maneuver state methods do not exist.

- [ ] **Step 3: Add fields to `AvatarFlightComponent`**

Add codec entries before `.build()`:

```java
.<Double>append(new KeyedCodec<>("DiveLoad", Codec.DOUBLE),
        AvatarFlightComponent::setDiveLoad,
        AvatarFlightComponent::getDiveLoad)
.add()
.<Double>append(new KeyedCodec<>("ClimbLoad", Codec.DOUBLE),
        AvatarFlightComponent::setClimbLoad,
        AvatarFlightComponent::getClimbLoad)
.add()
.<Long>append(new KeyedCodec<>("NextLaunchAtMs", Codec.LONG),
        AvatarFlightComponent::setNextLaunchAtMs,
        AvatarFlightComponent::getNextLaunchAtMs)
.add()
```

Add fields:

```java
private double diveLoad;
private double climbLoad;
private long nextLaunchAtMs;
```

Add accessors:

```java
public double getDiveLoad() {
    return clamp01(diveLoad);
}

public void setDiveLoad(@Nullable Double diveLoad) {
    this.diveLoad = clamp01(finiteOrZero(diveLoad));
}

public double getClimbLoad() {
    return clamp01(climbLoad);
}

public void setClimbLoad(@Nullable Double climbLoad) {
    this.climbLoad = clamp01(finiteOrZero(climbLoad));
}

public long getNextLaunchAtMs() {
    return nextLaunchAtMs;
}

public void setNextLaunchAtMs(@Nullable Long nextLaunchAtMs) {
    this.nextLaunchAtMs = nextLaunchAtMs == null ? 0L : nextLaunchAtMs;
}

private static double clamp01(double value) {
    return Math.max(0.0, Math.min(1.0, value));
}
```

Update `clone()`:

```java
clone.diveLoad = getDiveLoad();
clone.climbLoad = getClimbLoad();
clone.nextLaunchAtMs = nextLaunchAtMs;
```

- [ ] **Step 4: Add launch state to `AvatarFlightInputComponent`**

Add codec entries:

```java
.<Long>append(new KeyedCodec<>("LaunchChargeStartedAtMs", Codec.LONG),
        AvatarFlightInputComponent::setLaunchChargeStartedAtMs,
        AvatarFlightInputComponent::getLaunchChargeStartedAtMs)
.add()
.<Long>append(new KeyedCodec<>("LaunchReleasedAtMs", Codec.LONG),
        AvatarFlightInputComponent::setLaunchReleasedAtMs,
        AvatarFlightInputComponent::getLaunchReleasedAtMs)
.add()
.<Long>append(new KeyedCodec<>("LaunchHoldMs", Codec.LONG),
        AvatarFlightInputComponent::setLaunchHoldMs,
        AvatarFlightInputComponent::getLaunchHoldMs)
.add()
```

Add fields and methods:

```java
private long launchChargeStartedAtMs;
private long launchReleasedAtMs;
private long launchHoldMs;

public long getLaunchChargeStartedAtMs() { return launchChargeStartedAtMs; }
public void setLaunchChargeStartedAtMs(@Nullable Long value) { launchChargeStartedAtMs = value == null ? 0L : value; }
public long getLaunchReleasedAtMs() { return launchReleasedAtMs; }
public void setLaunchReleasedAtMs(@Nullable Long value) { launchReleasedAtMs = value == null ? 0L : value; }
public long getLaunchHoldMs() { return launchHoldMs; }
public void setLaunchHoldMs(@Nullable Long value) { launchHoldMs = value == null ? 0L : Math.max(0L, value); }

public void beginLaunchCharge(long nowMs) {
    if (launchChargeStartedAtMs == 0L) {
        launchChargeStartedAtMs = nowMs;
    }
}

public boolean isLaunchCharging() {
    return launchChargeStartedAtMs != 0L;
}

public void cancelLaunchCharge() {
    launchChargeStartedAtMs = 0L;
}

public void queueLaunchRelease(long nowMs) {
    if (launchChargeStartedAtMs == 0L) {
        return;
    }
    launchHoldMs = Math.max(0L, nowMs - launchChargeStartedAtMs);
    launchReleasedAtMs = nowMs;
    launchChargeStartedAtMs = 0L;
}

public boolean consumeLaunchRelease(long nowMs, long maxAgeMs) {
    boolean applies = queuedIntentApplies(launchReleasedAtMs, nowMs, maxAgeMs);
    launchReleasedAtMs = 0L;
    return applies;
}
```

Update `clone()` to copy the three launch fields.

- [ ] **Step 5: Run state tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightComponentTest,AvatarFlightInputComponentTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit state scaffolding**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponent.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponentTest.java
git commit -m "Feat: add avatar flight launch and maneuver state"
```

---

### Task 3: Add Pure Maneuver And Launch Math Helpers

**Files:**

- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMath.java`
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurve.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMathTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurveTest.java`

- [ ] **Step 1: Add failing maneuver math tests**

Create `AvatarFlightManeuverMathTest`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightManeuverMathTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void diveLoadRampsAndDecays() {
        double load = 0.0;
        load = AvatarFlightManeuverMath.updateLoad(load, true, 0.4, 1.6, 0.6);
        assertEquals(0.25, load, 0.00001);

        load = AvatarFlightManeuverMath.updateLoad(load, false, 0.3, 1.6, 0.6);
        assertEquals(0.0, load, 0.00001);
    }

    @Test
    void pitchPowerMakesShortShallowDiveWeak() {
        double shallow = AvatarFlightManeuverMath.pitchPower(Math.toRadians(-45.0), true,
                CONFIG.getCurve().getDivePitchExponent());
        double steep = AvatarFlightManeuverMath.pitchPower(Math.toRadians(-70.0), true,
                CONFIG.getCurve().getDivePitchExponent());

        assertTrue(shallow < steep);
        assertTrue(shallow < 0.55);
        assertEquals(1.0, steep, 0.00001);
    }

    @Test
    void climbEligibilityUsesSpeedAboveNeutral() {
        assertEquals(0.0, AvatarFlightManeuverMath.climbEligibility(6.0, CONFIG), 0.00001);
        assertTrue(AvatarFlightManeuverMath.climbEligibility(12.13, CONFIG) > 0.7);
        assertEquals(1.0, AvatarFlightManeuverMath.climbEligibility(15.0, CONFIG), 0.00001);
    }

    @Test
    void boostedExcessDecaysTowardNaturalCap() {
        double decayed = AvatarFlightManeuverMath.decayBoostedExcess(18.0, CONFIG, 0.5);

        assertEquals(17.0, decayed, 0.00001);
        assertEquals(15.0, AvatarFlightManeuverMath.decayBoostedExcess(15.0, CONFIG, 0.5), 0.00001);
        assertEquals(14.0, AvatarFlightManeuverMath.decayBoostedExcess(14.0, CONFIG, 0.5), 0.00001);
    }
}
```

- [ ] **Step 2: Add failing launch curve tests**

Create `AvatarFlightLaunchCurveTest`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightLaunchCurveTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void chargeIsZeroBelowThresholdAndOneAtMax() {
        assertEquals(0.0, AvatarFlightLaunchCurve.charge(CONFIG.getLaunch(), 499L), 0.00001);
        assertEquals(0.0, AvatarFlightLaunchCurve.charge(CONFIG.getLaunch(), 500L), 0.00001);
        assertEquals(1.0, AvatarFlightLaunchCurve.charge(CONFIG.getLaunch(), 3000L), 0.00001);
    }

    @Test
    void launchImpulseMatchesStoryboardSamples() {
        AvatarFlightLaunchCurve.Impulse oneSecond = AvatarFlightLaunchCurve.impulse(CONFIG.getLaunch(), 1000L);
        AvatarFlightLaunchCurve.Impulse twoSeconds = AvatarFlightLaunchCurve.impulse(CONFIG.getLaunch(), 2000L);
        AvatarFlightLaunchCurve.Impulse threeSeconds = AvatarFlightLaunchCurve.impulse(CONFIG.getLaunch(), 3000L);

        assertEquals(10.2, oneSecond.up(), 0.15);
        assertEquals(7.8, oneSecond.forward(), 0.15);
        assertEquals(14.6, twoSeconds.up(), 0.15);
        assertEquals(9.6, twoSeconds.forward(), 0.15);
        assertEquals(18.0, threeSeconds.up(), 0.00001);
        assertEquals(11.0, threeSeconds.forward(), 0.00001);
    }

    @Test
    void launchCostUsesFullCostThreshold() {
        assertEquals(0.0, AvatarFlightLaunchCurve.cost(CONFIG.getLaunch(), 499L), 0.00001);
        assertEquals(1.0, AvatarFlightLaunchCurve.cost(CONFIG.getLaunch(), 1000L), 0.00001);
        assertEquals(2.0, AvatarFlightLaunchCurve.cost(CONFIG.getLaunch(), 2200L), 0.00001);
    }
}
```

- [ ] **Step 3: Run failing helper tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightManeuverMathTest,AvatarFlightLaunchCurveTest test
```

Expected: compilation fails because the helper classes do not exist.

- [ ] **Step 4: Implement `AvatarFlightManeuverMath`**

Create `AvatarFlightManeuverMath.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nonnull;

/** Pure math for avatar-flight maneuver load and speed caps. */
public final class AvatarFlightManeuverMath {
    private static final double MAX_PITCH_RADIANS = Math.toRadians(70.0);

    private AvatarFlightManeuverMath() {
    }

    public static double updateLoad(double current, boolean active, double dt, double rampSeconds, double decaySeconds) {
        double rate = active ? 1.0 / Math.max(0.001, rampSeconds) : -1.0 / Math.max(0.001, decaySeconds);
        return clamp01(current + rate * Math.max(0.0, dt));
    }

    public static double pitchPower(double pitchRadians, boolean down, double exponent) {
        double signed = down ? -pitchRadians : pitchRadians;
        if (signed <= 0.0) {
            return 0.0;
        }
        double normalized = clamp01(signed / MAX_PITCH_RADIANS);
        return Math.pow(normalized, Math.max(0.001, exponent));
    }

    public static double climbEligibility(double horizontalSpeed, @Nonnull TwAvatarFlightConfig config) {
        double neutral = config.getMovement().getNeutralGlideSpeed();
        double range = Math.max(0.001, config.getMovement().getMaxGlideSpeed() - neutral);
        double normalized = clamp01((horizontalSpeed - neutral) / range);
        return Math.pow(normalized, config.getCurve().getClimbSpeedEligibilityExponent());
    }

    public static double decayBoostedExcess(double horizontalSpeed,
                                            @Nonnull TwAvatarFlightConfig config,
                                            double dt) {
        double naturalCap = config.getMovement().getMaxGlideSpeed();
        if (horizontalSpeed <= naturalCap) {
            return Math.max(0.0, horizontalSpeed);
        }
        return Math.max(naturalCap,
                horizontalSpeed - config.getCurve().getBoostedSpeedDecay() * Math.max(0.0, dt));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
```

- [ ] **Step 5: Implement `AvatarFlightLaunchCurve`**

Create `AvatarFlightLaunchCurve.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightLaunchSettings;
import javax.annotation.Nonnull;

/** Pure charged-launch calculations for avatar flight. */
public final class AvatarFlightLaunchCurve {
    private AvatarFlightLaunchCurve() {
    }

    public record Impulse(double up, double forward, double charge) {
    }

    public static double charge(@Nonnull AvatarFlightLaunchSettings settings, long holdMs) {
        long min = settings.getMinChargeMs();
        long max = settings.getMaxChargeMs();
        if (holdMs < min) {
            return 0.0;
        }
        double linear = (Math.min(holdMs, max) - min) / (double) Math.max(1L, max - min);
        return Math.pow(Math.max(0.0, Math.min(1.0, linear)), settings.getChargeExponent());
    }

    @Nonnull
    public static Impulse impulse(@Nonnull AvatarFlightLaunchSettings settings, long holdMs) {
        double charge = charge(settings, holdMs);
        double up = settings.getMinUpImpulse()
                + (settings.getMaxUpImpulse() - settings.getMinUpImpulse()) * charge;
        double forward = settings.getMinForwardImpulse()
                + (settings.getMaxForwardImpulse() - settings.getMinForwardImpulse()) * charge;
        if (holdMs < settings.getMinChargeMs()) {
            return new Impulse(0.0, 0.0, 0.0);
        }
        return new Impulse(up, forward, charge);
    }

    public static double cost(@Nonnull AvatarFlightLaunchSettings settings, long holdMs) {
        if (holdMs < settings.getMinChargeMs()) {
            return 0.0;
        }
        double charge = charge(settings, holdMs);
        return charge >= settings.getFullChargeCostThreshold()
                ? settings.getFullChargeCost()
                : settings.getPartialChargeCost();
    }
}
```

- [ ] **Step 6: Run helper tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightManeuverMathTest,AvatarFlightLaunchCurveTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit helper math**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMath.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurve.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightManeuverMathTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightLaunchCurveTest.java
git commit -m "Feat: add avatar flight maneuver math"
```

---

### Task 4: Integrate Curve-Based Dive And Climb Into Controller

**Files:**

- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`

- [ ] **Step 1: Add failing controller tests for curve behavior**

Append to `AvatarFlightControllerTest`:

```java
@Test
void shortDiveDoesNotImmediatelyReachLargeSpeedGain() {
    AvatarFlightController.State state = new AvatarFlightController.State(
            0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
    );
    AvatarFlightController.Output output = null;
    double altitudeChange = 0.0;
    for (int tick = 0; tick < 8; tick++) {
        output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                CONFIG,
                0.1,
                1000L + tick * 100L
        );
        altitudeChange += output.velocityY() * 0.1;
        state = stateFrom(output);
    }

    double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
    assertTrue(horizontalSpeed < 7.0, "0.75s dive should not provide a large speed payoff");
    assertTrue(altitudeChange > -3.0, "short dive should not spend a large altitude chunk immediately");
}

@Test
void sustainedSteepDiveBuildsSpeedWithoutCrossingNaturalCap() {
    AvatarFlightController.State state = new AvatarFlightController.State(
            0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
    );
    AvatarFlightController.Output output = null;
    for (int tick = 0; tick < 30; tick++) {
        output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                CONFIG,
                0.1,
                1000L + tick * 100L
        );
        state = stateFrom(output);
    }

    double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
    assertTrue(horizontalSpeed > 11.0, "3s steep dive should build meaningful speed");
    assertTrue(horizontalSpeed <= CONFIG.getMovement().getMaxGlideSpeed() + 0.00001);
    assertTrue(horizontalSpeed < AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG));
}

@Test
void sustainedDiveThenModeratePullUpRecoversAboutSeventyPercentAltitude() {
    AvatarFlightController.State state = new AvatarFlightController.State(
            0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
    );
    double diveAltitude = 0.0;
    for (int tick = 0; tick < 30; tick++) {
        AvatarFlightController.Output output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                CONFIG,
                0.1,
                1000L + tick * 100L
        );
        diveAltitude += output.velocityY() * 0.1;
        state = stateFrom(output);
    }

    double climbAltitude = 0.0;
    AvatarFlightController.Output output = null;
    for (int tick = 0; tick < 50; tick++) {
        output = AvatarFlightController.update(
                state,
                input(1.0, false, false, false, false, Math.toRadians(45.0)),
                CONFIG,
                0.1,
                5000L + tick * 100L
        );
        climbAltitude += output.velocityY() * 0.1;
        state = stateFrom(output);
    }

    double recovery = climbAltitude / Math.abs(diveAltitude);
    double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
    assertTrue(recovery > 0.60 && recovery < 0.85,
            "clean unboosted maneuver should recover around 70% of altitude, not all of it");
    assertTrue(horizontalSpeed < CONFIG.getMovement().getNeutralGlideSpeed() + 1.0,
            "the climb should spend most stored speed by the end");
}
```

Update the existing `stateFrom` helper to include the new output fields:

```java
private static AvatarFlightController.State stateFrom(AvatarFlightController.Output output) {
    return new AvatarFlightController.State(
            output.velocityX(),
            output.velocityY(),
            output.velocityZ(),
            output.nextJumpAtMs(),
            output.nextBoostAtMs(),
            output.diveLoad(),
            output.climbLoad(),
            output.nextLaunchAtMs()
    );
}
```

- [ ] **Step 2: Run failing controller tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightControllerTest test
```

Expected: compilation fails because `State` and `Output` do not include curve state.

- [ ] **Step 3: Extend controller state/output records**

In `AvatarFlightController`, change the records:

```java
public record State(double velocityX,
                    double velocityY,
                    double velocityZ,
                    long nextJumpAtMs,
                    long nextBoostAtMs,
                    double diveLoad,
                    double climbLoad,
                    long nextLaunchAtMs) {
    @Nonnull
    public static State from(@Nonnull AvatarFlightComponent component) {
        return new State(
                component.getVelocityX(),
                component.getVelocityY(),
                component.getVelocityZ(),
                component.getNextJumpAtMs(),
                component.getNextBoostAtMs(),
                component.getDiveLoad(),
                component.getClimbLoad(),
                component.getNextLaunchAtMs()
        );
    }
}

public record Output(@Nonnull AvatarFlightMode mode,
                     double velocityX,
                     double velocityY,
                     double velocityZ,
                     long nextJumpAtMs,
                     long nextBoostAtMs,
                     long nextLaunchAtMs,
                     double diveLoad,
                     double climbLoad,
                     boolean applyVelocity,
                     boolean jumpApplied,
                     boolean boostApplied,
                     boolean launchApplied,
                     boolean horizontalIdle,
                     boolean fastFlight,
                     double visualPitchRadians,
                     double visualRollRadians) {
}
```

Update all existing test constructor calls by adding `0.0, 0.0, 0L` to `State` and `0L, 0.0, 0.0, false` to `Output` helper constructors.

- [ ] **Step 4: Replace pitch trade load logic**

In `AvatarFlightController.update`, compute loads after `effectivePitchRadians`:

```java
double diveLoad = AvatarFlightManeuverMath.updateLoad(
        state.diveLoad(),
        effectivePitchRadians < 0.0,
        dt,
        config.getCurve().getDiveLoadRampSeconds(),
        config.getCurve().getDiveLoadDecaySeconds()
);
double climbLoad = AvatarFlightManeuverMath.updateLoad(
        state.climbLoad(),
        effectivePitchRadians > 0.0,
        dt,
        config.getCurve().getClimbLoadRampSeconds(),
        config.getCurve().getClimbLoadDecaySeconds()
);
```

Change `applyPitch` signature to accept `diveLoad`, `climbLoad`, and full `config`:

```java
PitchAdjustment pitch = applyPitch(effectivePitchRadians, targetForwardSpeed, vertical,
        config, glideHorizontalCap, diveLoad, climbLoad, dt);
```

Implement the pitch-down branch:

```java
double amount = AvatarFlightManeuverMath.pitchPower(
        pitchRadians,
        true,
        config.getCurve().getDivePitchExponent()
);
double load = Math.max(0.0, diveLoad);
double gain = movement.getPitchDownSpeedGain() * amount * load * dt;
double sink = movement.getPitchDownDiveScale() * amount * (0.35 + 0.65 * load) * dt;
return new PitchAdjustment(
        Math.min(glideHorizontalCap, forwardSpeed + gain),
        verticalSpeed - sink
);
```

Implement the pitch-up branch using `AvatarFlightManeuverMath.climbEligibility` and `climbLoad`. Keep the existing immediate carve feel by using at least a small effective load after the first tick:

```java
double amount = AvatarFlightManeuverMath.pitchPower(
        pitchRadians,
        false,
        config.getCurve().getClimbPitchExponent()
);
double load = Math.max(0.20, climbLoad);
double eligibility = AvatarFlightManeuverMath.climbEligibility(forwardSpeed, config);
double physicalPitch = Math.min(MAX_PHYSICAL_PITCH_UP_RADIANS, pitchRadians);
double glideSpeed = Math.max(0.0, forwardSpeed);
double drag = movement.getPitchUpSpeedCost() * amount * load * dt;
double effectiveSpeed = Math.max(0.0, glideSpeed - drag);
double targetForwardSpeed = Math.min(movement.getMaxForwardSpeed(),
        Math.max(0.0, effectiveSpeed * Math.cos(physicalPitch)));
double climbVerticalSpeed = Math.min(movement.getMaxFallSpeed(),
        effectiveSpeed * Math.sin(physicalPitch) * eligibility);
double sinkVerticalSpeed = -glideSinkSpeed(movement, forwardSpeed);
double targetVerticalSpeed = sinkVerticalSpeed
        + (climbVerticalSpeed - sinkVerticalSpeed) * Math.min(1.0, eligibility * load);
double turnDelta = movement.getPitchUpLiftScale() * Math.max(1.0, glideSpeed) * amount * dt;
return new PitchAdjustment(
        Math.max(0.0, approach(forwardSpeed, targetForwardSpeed, turnDelta)),
        approach(verticalSpeed, targetVerticalSpeed, turnDelta)
);
```

Return `diveLoad` and `climbLoad` in every `Output`.

- [ ] **Step 5: Persist loads in movement system**

After controller update in `AvatarFlightMovementSystem.tick`, add:

```java
flight.setDiveLoad(output.diveLoad());
flight.setClimbLoad(output.climbLoad());
flight.setNextLaunchAtMs(output.nextLaunchAtMs());
```

Update debug logging format to include:

```java
+ " loads=%.2f/%.2f"
```

and pass `output.diveLoad(), output.climbLoad()`.

- [ ] **Step 6: Run controller tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightControllerTest,AvatarFlightComponentTest test
```

Expected: `BUILD SUCCESS`. If storyboard tests are close but fail by small margins, tune only constants in config defaults or the load equations, not test intent.

- [ ] **Step 7: Commit curve integration**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponent.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightComponentTest.java
git commit -m "Feat: curve avatar flight dive and climb"
```

---

### Task 5: Make Q Boost Directional And Decay Boosted Excess

**Files:**

- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- Modify: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java`

- [ ] **Step 1: Add failing config checks for directional boost**

Add to `defaultConfigExposesCurveAndLaunchValues`:

```java
assertTrue(config.getBoost().isDirectional());
assertEquals(0.45, config.getBoost().getUpwardPitchLiftMultiplier(), 0.00001);
assertEquals(3.0, config.getBoost().getUpwardPitchLiftCap(), 0.00001);
```

Add to `explicitBoostSectionInheritsMissingDuration`:

```java
setNestedField(parent, "boost", "directional", false);
setNestedField(parent, "boost", "upwardPitchLiftMultiplier", 0.25);
setNestedField(parent, "boost", "upwardPitchLiftCap", 1.5);
setNestedField(child, "boost", "directional", true);
setNestedField(child, "boost", "upwardPitchLiftMultiplier", 0.5);
setNestedField(child, "boost", "upwardPitchLiftCap", 2.5);

assertFalse(child.getBoost().isDirectional());
assertEquals(0.25, child.getBoost().getUpwardPitchLiftMultiplier(), 0.00001);
assertEquals(1.5, child.getBoost().getUpwardPitchLiftCap(), 0.00001);
```

- [ ] **Step 2: Add failing boost controller tests**

Append to `AvatarFlightControllerTest`:

```java
@Test
void qBoostPointedUpAddsCappedLift() {
    AvatarFlightController.Output output = update(
            new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L, 0.0, 0.0, 0L),
            input(1.0, false, false, true, false, Math.toRadians(60.0))
    );

    assertTrue(output.boostApplied());
    assertTrue(output.velocityY() > 0.0);
    assertTrue(output.velocityY() <= 3.0 + 0.00001,
            "upward boost lift must be capped so flap remains the stronger vertical tool");
}

@Test
void qBoostPointedDownAddsDownwardThrust() {
    AvatarFlightController.Output output = update(
            new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L, 0.0, 0.0, 0L),
            input(1.0, false, false, true, false, Math.toRadians(-60.0))
    );

    assertTrue(output.boostApplied());
    assertTrue(output.velocityY() < -5.0,
            "downward boost should use full directional thrust because it spends altitude");
}

@Test
void boostedExcessDecaysWhenBoostWindowEnds() {
    double boostedSpeed = AvatarFlightSpeedMetrics.boostedHorizontalCap(CONFIG);
    AvatarFlightController.Output output = AvatarFlightController.update(
            new AvatarFlightController.State(0.0, 0.0, -boostedSpeed, 0L, 0L, 0.0, 0.0, 0L),
            input(1.0, false, false, false, false, 0.0),
            CONFIG,
            0.5,
            10_000L
    );

    double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
    assertTrue(horizontalSpeed < boostedSpeed);
    assertTrue(horizontalSpeed >= CONFIG.getMovement().getMaxGlideSpeed());
}
```

- [ ] **Step 3: Run failing boost tests**

Run:

```powershell
.\mvnw -Dtest=TwAvatarFlightConfigTest,AvatarFlightControllerTest test
```

Expected: compilation fails because boost directional getters do not exist.

- [ ] **Step 4: Add boost config fields**

In `BoostSettings`, add fields:

```java
private boolean directional = true;
private double upwardPitchLiftMultiplier = 0.45;
private double upwardPitchLiftCap = 3.0;
```

Add codec entries after `DurationSeconds`:

```java
.<Boolean>append(new KeyedCodec<>("Directional", Codec.BOOLEAN),
        (settings, value) -> settings.directional = value == null || value,
        settings -> settings.directional)
.documentation("Whether boost impulse follows look pitch instead of applying only forward speed. Inheritance: missing nested key inherits parent value.")
.add()
.<Double>append(new KeyedCodec<>("UpwardPitchLiftMultiplier", Codec.DOUBLE),
        (settings, value) -> settings.upwardPitchLiftMultiplier = nonNegativeOrDefault(value, 0.45),
        settings -> settings.upwardPitchLiftMultiplier)
.documentation("Multiplier applied to upward directional boost lift before cap. Inheritance: missing nested key inherits parent value.")
.add()
.<Double>append(new KeyedCodec<>("UpwardPitchLiftCap", Codec.DOUBLE),
        (settings, value) -> settings.upwardPitchLiftCap = nonNegativeOrDefault(value, 3.0),
        settings -> settings.upwardPitchLiftCap)
.documentation("Maximum upward vertical impulse from directional boost. Inheritance: missing nested key inherits parent value.")
.add()
```

Update `inheritOrCopyBoost`:

```java
if (!keys.contains("Directional")) boost.directional = parent.boost.directional;
if (!keys.contains("UpwardPitchLiftMultiplier")) {
    boost.upwardPitchLiftMultiplier = parent.boost.upwardPitchLiftMultiplier;
}
if (!keys.contains("UpwardPitchLiftCap")) boost.upwardPitchLiftCap = parent.boost.upwardPitchLiftCap;
```

Add getters:

```java
public boolean isDirectional() { return directional; }
public double getUpwardPitchLiftMultiplier() { return Math.max(0.0, upwardPitchLiftMultiplier); }
public double getUpwardPitchLiftCap() { return Math.max(0.0, upwardPitchLiftCap); }
```

Add fields to default JSON:

```json
    "Directional": true,
    "UpwardPitchLiftMultiplier": 0.45,
    "UpwardPitchLiftCap": 3.0
```

- [ ] **Step 5: Apply directional boost in controller**

Replace boost application block with:

```java
if (!explicitAirbrakeIntent
        && input.sprint()
        && input.boostAllowed()
        && (nextBoostAtMs == 0L || nowMs >= nextBoostAtMs)) {
    double boost = config.getBoost().getForwardImpulse();
    if (config.getBoost().isDirectional()) {
        double absPitch = Math.abs(effectivePitchRadians);
        double horizontalImpulse = boost * Math.cos(absPitch);
        targetForwardSpeed = Math.min(
                boostedHorizontalCap,
                Math.max(Math.max(targetForwardSpeed, currentForwardSpeed), 0.0) + horizontalImpulse
        );
        if (effectivePitchRadians < 0.0) {
            vertical -= boost * Math.sin(absPitch);
        } else if (effectivePitchRadians > 0.0) {
            vertical += Math.min(
                    boost * Math.sin(absPitch) * config.getBoost().getUpwardPitchLiftMultiplier(),
                    config.getBoost().getUpwardPitchLiftCap()
            );
        }
    } else {
        targetForwardSpeed = Math.min(
                boostedHorizontalCap,
                Math.max(Math.max(targetForwardSpeed, currentForwardSpeed), 0.0) + boost
        );
    }
    nextBoostAtMs = nowMs + boostCooldownMs;
    boostActive = true;
    boostApplied = true;
    mode = AvatarFlightMode.FORWARD_FLIGHT;
}
```

Before resolving horizontal velocity limit, apply boosted excess decay when not boost active:

```java
if (!boostActive && targetForwardSpeed > glideHorizontalCap) {
    targetForwardSpeed = AvatarFlightManeuverMath.decayBoostedExcess(targetForwardSpeed, config, dt);
}
```

- [ ] **Step 6: Run boost tests**

Run:

```powershell
.\mvnw -Dtest=TwAvatarFlightConfigTest,AvatarFlightControllerTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit directional boost**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java `
        src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json `
        src/test/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfigTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java
git commit -m "Feat: make avatar boost directional"
```

---

### Task 6: Add Launch Backend And Vigour Authorization

**Files:**

- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- Modify: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystemTest.java`

- [ ] **Step 1: Add failing launch controller tests**

Append to `AvatarFlightControllerTest`:

```java
@Test
void chargedLaunchAppliesConfiguredImpulseFromGround() {
    AvatarFlightController.Output output = AvatarFlightController.update(
            new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L),
            new AvatarFlightController.Input(0.0, 0.0, 0.0, false, false, false,
                    false, true, 0.0, 0.0, true, true, true, 3000L),
            CONFIG,
            0.1,
            1000L
    );

    assertTrue(output.launchApplied());
    assertEquals(AvatarFlightMode.LAUNCHING, output.mode());
    assertEquals(18.0, output.velocityY(), 0.00001);
    assertEquals(-11.0, output.velocityZ(), 0.00001);
    assertTrue(output.applyVelocity());
}

@Test
void launchBelowChargeThresholdDoesNotApply() {
    AvatarFlightController.Output output = AvatarFlightController.update(
            new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L),
            new AvatarFlightController.Input(0.0, 0.0, 0.0, false, false, false,
                    false, true, 0.0, 0.0, true, true, true, 499L),
            CONFIG,
            0.1,
            1000L
    );

    assertFalse(output.launchApplied());
    assertEquals(AvatarFlightMode.GROUNDED, output.mode());
}
```

- [ ] **Step 2: Add failing movement-system authorization tests**

Append to `AvatarFlightMovementSystemTest`:

```java
@Test
void launchIsBlockedWhenVigourCannotPayCost() throws Exception {
    TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
    AvatarFlightComponent flight = new AvatarFlightComponent("Test", 1000L);
    flight.setVigourCharges(0.0);
    AvatarFlightController.Input input = new AvatarFlightController.Input(
            0.0, 0.0, 0.0, false, false, false, false,
            true, 0.0, 0.0, true, true, true, 3000L
    );

    AvatarFlightController.Input authorized = authorizeVigour(input, flight, config, 2000L);

    assertFalse(authorized.launchAllowed());
}
```

- [ ] **Step 3: Run failing launch backend tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightControllerTest,AvatarFlightMovementSystemTest test
```

Expected: compilation fails because launch fields are not on `Input`, `Output`, and `AvatarFlightMode.LAUNCHING` does not exist.

- [ ] **Step 4: Add launch mode and controller input fields**

Add `LAUNCHING` to `AvatarFlightMode`.

Change `AvatarFlightController.Input`:

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
                    boolean boostAllowed,
                    boolean launchAllowed,
                    long launchHoldMs) {
}
```

Update all existing input helpers to pass `true, 0L` for launch authorization and hold duration.

Extend `AvatarFlightController.Output` with a launch cost field:

```java
public record Output(@Nonnull AvatarFlightMode mode,
                     double velocityX,
                     double velocityY,
                     double velocityZ,
                     long nextJumpAtMs,
                     long nextBoostAtMs,
                     long nextLaunchAtMs,
                     double diveLoad,
                     double climbLoad,
                     boolean applyVelocity,
                     boolean jumpApplied,
                     boolean boostApplied,
                     boolean launchApplied,
                     double launchCost,
                     boolean horizontalIdle,
                     boolean fastFlight,
                     double visualPitchRadians,
                     double visualRollRadians) {
}
```

Update all existing `Output` constructor calls to pass `0.0` for `launchCost` unless the launch branch applies.

- [ ] **Step 5: Apply launch in controller before grounded return**

Near the top of `update`, before `if (input.onGround() && !jumpIntent)`, add:

```java
boolean launchApplied = false;
long nextLaunchAtMs = state.nextLaunchAtMs();
if (config.getLaunch().isEnabled()
        && input.onGround()
        && input.launchAllowed()
        && input.launchHoldMs() >= config.getLaunch().getMinChargeMs()
        && (nextLaunchAtMs == 0L || nowMs >= nextLaunchAtMs)) {
    AvatarFlightLaunchCurve.Impulse impulse = AvatarFlightLaunchCurve.impulse(config.getLaunch(), input.launchHoldMs());
    double launchCost = AvatarFlightLaunchCurve.cost(config.getLaunch(), input.launchHoldMs());
    targetForwardSpeed = impulse.forward();
    vertical = impulse.up();
    mode = AvatarFlightMode.LAUNCHING;
    launchApplied = true;
    nextLaunchAtMs = nowMs + Math.round(config.getJump().getCooldownSeconds() * 1000.0);
    double x = forwardX * targetForwardSpeed;
    double z = forwardZ * targetForwardSpeed;
    return new Output(mode, x, vertical, z, nextJumpAtMs, nextBoostAtMs, nextLaunchAtMs,
            0.0, 0.0, true, false, false, true, launchCost, false, false, input.pitchRadians(), 0.0);
}
```

Update every other `Output` constructor to include `nextLaunchAtMs`, `diveLoad`, `climbLoad`, `launchApplied`, and `launchCost`.

- [ ] **Step 6: Authorize and spend launch Vigour**

In `AvatarFlightMovementSystem.authorizeVigour`, compute:

```java
double launchCost = AvatarFlightLaunchCurve.cost(config.getLaunch(), input.launchHoldMs());
boolean launchAllowed = AvatarFlightVigourService.canSpend(state, config, launchCost);
```

Update `withVigourAuthorization` to pass launch authorization:

```java
return new AvatarFlightController.Input(
        input.forwardAxis(),
        input.strafeAxis(),
        input.verticalAxis(),
        input.jump(),
        input.crouch(),
        input.sprint(),
        input.airbrake(),
        input.onGround(),
        input.yawRadians(),
        input.pitchRadians(),
        flapAllowed,
        boostAllowed,
        launchAllowed,
        input.launchHoldMs()
);
```

In `spendAppliedVigour`, add:

```java
if (output.launchApplied()) {
    state = AvatarFlightVigourService.spend(
            state,
            config,
            output.launchCost(),
            now
    );
    spent = true;
}
```

- [ ] **Step 7: Run launch backend tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightControllerTest,AvatarFlightMovementSystemTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit launch backend**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightController.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMode.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightControllerTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystemTest.java
git commit -m "Feat: add avatar flight launch backend"
```

---

### Task 7: Capture Jump-Hold Launch Input And Add Fallback Hook

**Files:**

- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightPacketInputCapture.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInteractionControlService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/interactions/TameworkFlightFlapInteraction.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponentTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightPacketInputCaptureArchitectureTest.java`

- [ ] **Step 1: Add launch capture tests for input component**

Append to `AvatarFlightInputComponentTest`:

```java
@Test
void repeatedLaunchBeginKeepsOriginalStartTime() {
    AvatarFlightInputComponent input = new AvatarFlightInputComponent();

    input.beginLaunchCharge(1_000L);
    input.beginLaunchCharge(1_500L);
    input.queueLaunchRelease(2_000L);

    assertEquals(1_000L, input.getLaunchHoldMs());
}

@Test
void cancellingLaunchChargePreventsReleaseQueue() {
    AvatarFlightInputComponent input = new AvatarFlightInputComponent();

    input.beginLaunchCharge(1_000L);
    input.cancelLaunchCharge();
    input.queueLaunchRelease(2_000L);

    assertEquals(0L, input.getLaunchHoldMs());
    assertFalse(input.consumeLaunchRelease(2_100L, 1_000L));
}
```

- [ ] **Step 2: Add packet-capture architecture assertion**

In `AvatarFlightPacketInputCaptureArchitectureTest`, assert the source includes launch charge methods:

```java
assertTrue(source.contains("beginLaunchCharge"));
assertTrue(source.contains("queueLaunchRelease"));
assertTrue(source.contains("cancelLaunchCharge"));
```

- [ ] **Step 3: Run failing launch capture tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightInputComponentTest,AvatarFlightPacketInputCaptureArchitectureTest test
```

Expected: `AvatarFlightPacketInputCaptureArchitectureTest` fails until packet capture is wired.

- [ ] **Step 4: Capture jump-hold in packet input**

In `AvatarFlightPacketInputCapture.captureOnWorld`, after movement states are resolved and before `store.putComponent`, add:

```java
boolean jumpHeld = packetStates != null && (packetStates.jumping || packetStates.swimJumping);
boolean grounded = movementStates != null && movementStates.onGround;
if (config.getLaunch().isEnabled()
        && AvatarFlightLaunchSettings.INPUT_JUMP_HOLD.equalsIgnoreCase(config.getLaunch().getPreferredInput())
        && grounded) {
    if (jumpHeld) {
        input.beginLaunchCharge(now);
    } else if (input.isLaunchCharging()) {
        input.queueLaunchRelease(now);
    }
} else if (input.isLaunchCharging()) {
    input.cancelLaunchCharge();
}
```

Import `AvatarFlightLaunchSettings`.

To avoid raw ground jump being interpreted as a flap while charging, set jumping after the launch handling:

```java
boolean suppressGroundLaunchJump = config.getLaunch().isEnabled()
        && grounded
        && (jumpHeld || input.getLaunchReleasedAtMs() != 0L);
input.setJumping(!suppressGroundLaunchJump && jumpHeld);
```

- [ ] **Step 5: Feed launch release to controller**

In `AvatarFlightMovementSystem.toControllerInput`, consume launch release:

```java
boolean launchRelease = input != null && input.consumeLaunchRelease(
        now,
        Math.round(config.getInput().getIntentTimeoutMs())
);
long launchHoldMs = launchRelease && input != null ? input.getLaunchHoldMs() : 0L;
```

Pass `launchRelease` and `launchHoldMs` into `AvatarFlightController.Input`:

```java
launchRelease,
launchHoldMs
```

The `launchAllowed` argument should remain `true` before `authorizeVigour`; `authorizeVigour` will turn it off if charges are insufficient.

- [ ] **Step 6: Add fallback launch queue API**

In `AvatarFlightInteractionControlService`, add:

```java
public static boolean beginLaunchCharge(@Nonnull InteractionContext context, long nowMs) {
    return apply(context, nowMs, input -> input.beginLaunchCharge(nowMs));
}

public static boolean releaseLaunchCharge(@Nonnull InteractionContext context, long nowMs) {
    return apply(context, nowMs, input -> input.queueLaunchRelease(nowMs));
}
```

Do not bind this fallback to the current Reins flap interaction until the jump-hold manual probe fails. Binding fallback requires either an interaction that can observe hold release or packet mouse-button state; keep the backend API ready.

- [ ] **Step 7: Add launch debug logging**

In `AvatarFlightMovementSystem.maybeLogDebug`, include:

```java
+ " launchHold=%d launchApplied=%s"
```

and pass:

```java
input.launchHoldMs(),
output.launchApplied()
```

- [ ] **Step 8: Run capture tests**

Run:

```powershell
.\mvnw -Dtest=AvatarFlightInputComponentTest,AvatarFlightPacketInputCaptureArchitectureTest,AvatarFlightMovementSystemTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit launch input capture**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightPacketInputCapture.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInteractionControlService.java `
        src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightInputComponentTest.java `
        src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightPacketInputCaptureArchitectureTest.java
git commit -m "Feat: capture avatar flight launch input"
```

---

### Task 8: Documentation, Full Validation, And Runtime Probe

**Files:**

- Modify: `docs/Avatar-Flight.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update `docs/Avatar-Flight.md`**

Add a `Charged Launch` section after `Controls`:

```markdown
## Charged Launch

Avatar flight supports a charged ground launch. With the default profile, holding jump while grounded charges launch from `500ms` to `3000ms`; releasing after the threshold spends Vigour and applies upward plus forward launch impulse. Tapping jump below the threshold remains a normal jump and does not intentionally launch avatar flight.

Default sample values:

| Hold | Up Impulse | Forward Impulse | Vigour |
| --- | ---: | ---: | ---: |
| `<500ms` | native | native | 0 |
| `1000ms` | about 10.2 | about 7.8 | 1 |
| `2000ms` | about 14.6 | about 9.6 | 2 |
| `3000ms` | 18.0 | 11.0 | 2 |

If jump-hold proves unreliable in a given Hytale build, the same launch backend can be bound to grounded Flightmaster's Talisman primary hold.
```

Update `Glide Balance` to mention sustained dive/climb load:

```markdown
Pitch-down speed gain and pitch-up climb use sustained maneuver load curves. Brief dip-and-pull-up inputs do not provide much speed, while committed dives can build speed up to the natural glide cap and recover roughly 70% of the altitude spent when followed by a clean climb.
```

- [ ] **Step 2: Update `CHANGELOG.md`**

Add player-facing bullets under the current unreleased section:

```markdown
- Tuned avatar flight to use sustained dive/climb momentum curves so short dip loops no longer sustain infinite flight.
- Changed Flightmaster's Talisman Q boost into directional thrust with capped upward lift.
- Added configurable charged launch tuning for transformed avatar flight.
```

Do not bump version.

- [ ] **Step 3: Run focused tests**

Run:

```powershell
.\mvnw -Dtest=TwAvatarFlightConfigTest,AvatarFlightComponentTest,AvatarFlightInputComponentTest,AvatarFlightManeuverMathTest,AvatarFlightLaunchCurveTest,AvatarFlightControllerTest,AvatarFlightMovementSystemTest,AvatarFlightPacketInputCaptureArchitectureTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run full tests**

Run:

```powershell
.\mvnw test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Run thread-safety grep**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new matches in avatar-flight tick/runtime paths. Existing allowed matches elsewhere must be reviewed without expanding guard-test allowlists casually.

- [ ] **Step 6: Manual runtime probe**

Deploy the mod using the normal local deployment workflow, then test in Hytale:

```text
/tw debugdragonflight
```

Manual expected results:

- Tap jump below `500ms`: normal jump, no charged launch.
- Hold jump for about `1s`: one small launch, one Vigour spent.
- Hold jump for about `3s`: one strong launch, two Vigour spent.
- Holding jump does not repeatedly launch.
- Q boost level, downward, and upward each spend once and feel directional.
- Short dip-and-pull-up loops lose altitude.
- A committed dive then pull-up regains notable but not full altitude.
- HUD remains above the hotbar with no placeholder image.

If jump-hold causes visible native-jump jitter or double-pop, do not tune around it. Switch the default launch input to the Reins primary-hold fallback in a follow-up task using the already-added launch backend.

- [ ] **Step 7: Commit docs and validation updates**

Run:

```powershell
git add docs/Avatar-Flight.md CHANGELOG.md
git commit -m "Docs: document avatar flight tuning and launch"
```

---

## Self-Review Checklist

- Spec coverage:
  - Curve-based dive speed gain: Tasks 1, 3, 4.
  - Curve-based climb exchange: Tasks 1, 3, 4.
  - Directional Q boost: Task 5.
  - Boosted-speed decay: Tasks 3 and 5.
  - Charged ground launch: Tasks 1, 2, 3, 6, 7.
  - Tests/docs/probes: Tasks 1-8.
- Type consistency:
  - New config sections are `getCurve()` and `getLaunch()`.
  - Maneuver state flows from `AvatarFlightComponent` to `AvatarFlightController.State` to `AvatarFlightController.Output` back to the component.
  - Launch hold flows from `AvatarFlightInputComponent` to controller input, then movement system Vigour authorization.
- Scope:
  - No fake rider, armor hiding, E/R abilities, pitch/bank animation, or native mounted controller changes.
- Validation:
  - Every implementation task includes a focused test command and commit command.
  - Full `.\mvnw test` and thread-safety grep are required before handoff.
