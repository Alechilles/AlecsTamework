# Needs Fast Consume Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable needs resource mode that can bypass food/water pathing and consume directly from nearby valid resources during heavy needs load.

**Architecture:** Store the mode in `/tw settings` as `needs.resourceMode` with `Accurate`, `AutoFast`, and `AlwaysFast` values. Runtime policy remains small and testable: pressure tracking exposes its current level, `NeedsResourceFastModePolicy` decides whether fast mode is active, `SensorTameworkNeedsResourceTarget` skips reservations/path preflight when active, and a tiny `TameworkNeedsResourceFastMode` sensor lets shared role assets branch directly to consume. Existing resource scans and `CompanionNeedsConsumeService` remain the source of truth for valid water/trough/container consumption.

**Tech Stack:** Java, Hytale NPC role JSON assets, Tamework `/tw settings` persistence/UI, JUnit 5, Maven wrapper `.\mvnw.cmd`.

---

## File Structure

- Create `src/main/java/com/alechilles/alecstamework/settings/NeedsResourceMode.java`
  - Owns stable config values: `Accurate`, `AutoFast`, `AlwaysFast`.
- Modify `src/main/java/com/alechilles/alecstamework/persistence/TameworkSettingsStore.java`
  - Persists `needs.resourceMode`, defaulting to `Accurate`.
- Modify `src/main/java/com/alechilles/alecstamework/settings/ResolvedTameworkSettings.java`
  - Adds `needsResourceMode`.
- Modify `src/main/java/com/alechilles/alecstamework/settings/TameworkSettingsResolver.java`
  - Resolves old settings files to the default mode.
- Modify `src/main/java/com/alechilles/alecstamework/settings/TameworkRuntimeSettings.java`
  - Exposes instance and static needs resource mode helpers.
- Modify `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsValues.java`
  - Carries the mode through the settings form state.
- Modify `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java`
  - Adds dropdown event binding, rendering, parsing, and entries.
- Modify `src/main/resources/Common/UI/Custom/TameworkSettingsPage.ui`
  - Adds a Needs Resource Mode dropdown in the needs section.
- Modify `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPageTextBinder.java`
  - Binds label, tooltip, and no-items text.
- Modify `src/main/resources/Server/Languages/en-US/server.lang`
  - Adds settings labels and dropdown strings.
- Modify `src/main/java/com/alechilles/alecstamework/performance/TameworkRuntimePressureService.java`
  - Exposes current pressure level for policy checks.
- Create `src/main/java/com/alechilles/alecstamework/performance/RuntimePressureLevel.java`
  - Public pressure-level enum.
- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicy.java`
  - Decides whether direct consume mode is active.
- Modify `src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTarget.java`
  - Bypasses target reservations and path preflight when fast mode is active.
- Create `src/main/java/com/alechilles/alecstamework/npc/sensors/BuilderSensorTameworkNeedsResourceFastMode.java`
  - Builder for the asset-facing fast-mode sensor.
- Create `src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastMode.java`
  - Runtime sensor used by shared role JSON.
- Modify `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
  - Registers the new sensor builder.
- Modify `src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json`
  - Adds a fast-mode branch before movement.
- Modify docs and wiki:
  - `docs/Actions-Sensors-Components.md`
  - `docs/Config-Discovery.md`
  - `wiki/Modder-Documentation/Testing-and-Diagnostics/Tamework-Settings-UI-and-Persistence.md`
  - `wiki/Modder-Documentation/Optional-Integrations/Asset-Patches-Guide.md`
  - `CHANGELOG.md`

## Task 1: Persist Needs Resource Mode

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/settings/NeedsResourceMode.java`
- Modify: `src/main/java/com/alechilles/alecstamework/persistence/TameworkSettingsStore.java`
- Modify: `src/main/java/com/alechilles/alecstamework/settings/ResolvedTameworkSettings.java`
- Modify: `src/main/java/com/alechilles/alecstamework/settings/TameworkSettingsResolver.java`
- Modify: `src/main/java/com/alechilles/alecstamework/settings/TameworkRuntimeSettings.java`
- Test: `src/test/java/com/alechilles/alecstamework/persistence/TameworkSettingsStoreTest.java`

- [ ] **Step 1: Write the mode enum tests**

Create `src/test/java/com/alechilles/alecstamework/settings/NeedsResourceModeTest.java`:

```java
package com.alechilles.alecstamework.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeedsResourceModeTest {
    @Test
    void blankAndUnknownValuesResolveToAccurate() {
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue(null));
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue(""));
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue("Direct"));
    }

    @Test
    void configValuesRoundTripCaseInsensitively() {
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue("accurate"));
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue("AutoFast"));
        assertEquals(NeedsResourceMode.ALWAYS_FAST, NeedsResourceMode.fromConfigValue("alwaysfast"));
        assertEquals("AlwaysFast", NeedsResourceMode.ALWAYS_FAST.toConfigValue());
    }
}
```

- [ ] **Step 2: Run the failing enum test**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceModeTest test
```

Expected: compile failure because `NeedsResourceMode` does not exist.

- [ ] **Step 3: Add `NeedsResourceMode`**

Create `src/main/java/com/alechilles/alecstamework/settings/NeedsResourceMode.java`:

```java
package com.alechilles.alecstamework.settings;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime food/water seeking policy owned by /tw settings.
 */
public enum NeedsResourceMode {
    ACCURATE("Accurate"),
    AUTO_FAST("AutoFast"),
    ALWAYS_FAST("AlwaysFast");

    private final String configValue;

    NeedsResourceMode(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    @Nonnull
    public static NeedsResourceMode fromConfigValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return ACCURATE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (NeedsResourceMode mode : values()) {
            if (mode.configValue.toLowerCase(Locale.ROOT).equals(normalized)
                    || mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return mode;
            }
        }
        return ACCURATE;
    }
}
```

- [ ] **Step 4: Add persistence tests**

Add these tests to `src/test/java/com/alechilles/alecstamework/persistence/TameworkSettingsStoreTest.java`:

```java
@Test
void defaultSettingsUseAccurateNeedsResourceMode() {
    assertEquals("Accurate", TameworkSettingsStore.defaultGlobalSettings().needsResourceMode());
}

@Test
void globalSettingsRoundTripNeedsResourceMode(@TempDir Path tempDir) {
    Path settingsFile = tempDir.resolve("tamework-settings.json");
    ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
    ResolvedTameworkSettings requested = new ResolvedTameworkSettings(
            defaults.populationLimitPerPlayerOwnedTotal(),
            defaults.populationPerPlayerLimitScope(),
            defaults.simpleClaimsEnabled(),
            defaults.simpleClaimsLimitPerClaimChunk(),
            defaults.simpleClaimsLimitPerClaimTotal(),
            defaults.simpleClaimsBreedingRequiresClaim(),
            defaults.simpleClaimsProtectTamedFromNonMembers(),
            defaults.blockOwnerDamage(),
            defaults.blockAllPlayerDamageIfOwned(),
            defaults.invulnerableIfOwned(),
            defaults.captureClearsOwner(),
            defaults.spawnSetsOwner(),
            defaults.captureRequiresOwner(),
            defaults.spawnRequiresOwner(),
            defaults.interactionRequiresOwner(),
            defaults.linkingRequiresOwner(),
            defaults.needsEnabled(),
            "AlwaysFast",
            defaults.needsTickPolicyMode(),
            defaults.needsOwnerOfflineGraceHours(),
            defaults.needsOwnerOfflineDecayMultiplier(),
            defaults.needsDamageEnabled(),
            defaults.needsDamageModel(),
            defaults.needsDamageDualNeedRule(),
            defaults.needsStarvationDamagePerMinute(),
            defaults.needsDehydrationDamagePerMinute(),
            defaults.needsDamageLethal(),
            defaults.happinessEnabled(),
            defaults.passiveBreedingEnabled(),
            defaults.breedingRequiresHappiness(),
            defaults.breedingGenderEnabled(),
            defaults.traitsEnabled(),
            defaults.levelingEnabled(),
            defaults.talentsEnabled(),
            defaults.reviveSystemEnabled(),
            defaults.recallTeleportingEnabled(),
            defaults.telemetryEnabled(),
            defaults.telemetryBreadcrumbsEnabled()
    );

    assertTrue(TameworkSettingsStore.saveGlobalSettings(settingsFile, requested.toSnapshot(), null));
    ResolvedTameworkSettings loaded = TameworkSettingsStore.loadGlobalSettings(settingsFile, null);

    assertEquals("AlwaysFast", loaded.needsResourceMode());
}
```

- [ ] **Step 5: Run the failing persistence tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceModeTest,TameworkSettingsStoreTest test
```

Expected: compile failures because `ResolvedTameworkSettings` and settings records do not yet include `needsResourceMode`.

- [ ] **Step 6: Wire the field through settings**

Make these concrete changes:

- In `ResolvedTameworkSettings`, add `@Nonnull String needsResourceMode` immediately after `boolean needsEnabled`, add it to `toSnapshot()`, and update constructor call sites in tests.
- In `TameworkSettingsStore.GlobalSettingsSnapshot`, add `@Nonnull String needsResourceMode` immediately after `boolean needsEnabled`.
- In `TameworkSettingsStore.GlobalOverrides`, add `@Nullable String needsResourceMode` immediately after `@Nullable Boolean needsEnabled`.
- In `TameworkSettingsStore.NeedsSection`, add `private String resourceMode;`.
- In `createDefaultGlobalSettingsDocument()`, set `document.needs.resourceMode = "Accurate";`.
- In `createDocument(...)`, set `document.needs.resourceMode = NeedsResourceMode.fromConfigValue(snapshot.needsResourceMode()).toConfigValue();`.
- In `toOverrides(...)`, pass `needs != null ? trimToNull(needs.resourceMode) : null` after `needs.enabled`.
- In `TameworkSettingsResolver.resolve(...)`, pass `NeedsResourceMode.fromConfigValue(resolveString(values.needsResourceMode(), defaults.needsResourceMode())).toConfigValue()` after `needsEnabled`.
- In `TameworkRuntimeSettings`, add:

```java
@Nonnull
public String needsResourceMode() {
    return values.needsResourceMode();
}

@Nonnull
public NeedsResourceMode needsResourceModeValue() {
    return NeedsResourceMode.fromConfigValue(values.needsResourceMode());
}

@Nonnull
public static NeedsResourceMode needsResourceMode(@Nullable String configMode) {
    TameworkRuntimeSettings settings = currentOrNull();
    return settings != null
            ? settings.needsResourceModeValue()
            : NeedsResourceMode.fromConfigValue(configMode);
}
```

- [ ] **Step 7: Run persistence tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceModeTest,TameworkSettingsStoreTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/settings/NeedsResourceMode.java src/main/java/com/alechilles/alecstamework/settings/ResolvedTameworkSettings.java src/main/java/com/alechilles/alecstamework/settings/TameworkSettingsResolver.java src/main/java/com/alechilles/alecstamework/settings/TameworkRuntimeSettings.java src/main/java/com/alechilles/alecstamework/persistence/TameworkSettingsStore.java src/test/java/com/alechilles/alecstamework/settings/NeedsResourceModeTest.java src/test/java/com/alechilles/alecstamework/persistence/TameworkSettingsStoreTest.java
git commit -m "Feat: persist needs resource mode"
```

## Task 2: Expose Runtime Pressure Level

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/performance/RuntimePressureLevel.java`
- Modify: `src/main/java/com/alechilles/alecstamework/performance/TameworkRuntimePressureService.java`
- Test: `src/test/java/com/alechilles/alecstamework/performance/TameworkRuntimePressureServiceTest.java`

- [ ] **Step 1: Add pressure-level tests**

Add to `TameworkRuntimePressureServiceTest`:

```java
@Test
void exposesCurrentPressureLevel() {
    TameworkRuntimePressureService service = new TameworkRuntimePressureService();
    long nowMs = 10_000L;

    assertEquals(RuntimePressureLevel.NORMAL, service.level(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, nowMs));

    for (int i = 0; i < 512; i++) {
        service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, nowMs);
    }

    assertEquals(RuntimePressureLevel.HOT, service.level(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, nowMs));
    assertTrue(service.isAtLeast(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, RuntimePressureLevel.WARM, nowMs));
    assertFalse(service.isAtLeast(RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, RuntimePressureLevel.WARM, nowMs));
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\mvnw.cmd -Dtest=TameworkRuntimePressureServiceTest test
```

Expected: compile failure because `RuntimePressureLevel` and pressure accessors do not exist.

- [ ] **Step 3: Add public pressure level enum and accessors**

Create `src/main/java/com/alechilles/alecstamework/performance/RuntimePressureLevel.java`:

```java
package com.alechilles.alecstamework.performance;

/**
 * Coarse runtime pressure buckets used by adaptive performance policies.
 */
public enum RuntimePressureLevel {
    NORMAL(1.0),
    WARM(1.5),
    HOT(2.5),
    EMERGENCY(4.0);

    private final double ttlMultiplier;

    RuntimePressureLevel(double ttlMultiplier) {
        this.ttlMultiplier = ttlMultiplier;
    }

    double ttlMultiplier() {
        return ttlMultiplier;
    }

    boolean isAtLeast(RuntimePressureLevel minimum) {
        return ordinal() >= minimum.ordinal();
    }

    RuntimePressureLevel decayOneStep() {
        int index = ordinal();
        return index <= 0 ? NORMAL : values()[index - 1];
    }
}
```

In `TameworkRuntimePressureService`, remove the private `PressureLevel` enum and replace uses with `RuntimePressureLevel`. Add:

```java
@Nonnull
public RuntimePressureLevel level(@Nonnull RuntimePressureDomain domain, long nowMs) {
    DomainState state = states.get(domain);
    return state != null ? state.level(nowMs) : RuntimePressureLevel.NORMAL;
}

public boolean isAtLeast(@Nonnull RuntimePressureDomain domain,
                         @Nonnull RuntimePressureLevel minimum,
                         long nowMs) {
    return level(domain, nowMs).isAtLeast(minimum);
}
```

Add to `DomainState`:

```java
@Nonnull
private synchronized RuntimePressureLevel level(long nowMs) {
    rolloverIfNeeded(nowMs);
    return level;
}
```

Update `multiplier` to return `level.ttlMultiplier()`.

- [ ] **Step 4: Run pressure tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TameworkRuntimePressureServiceTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/performance/RuntimePressureLevel.java src/main/java/com/alechilles/alecstamework/performance/TameworkRuntimePressureService.java src/test/java/com/alechilles/alecstamework/performance/TameworkRuntimePressureServiceTest.java
git commit -m "Perf: expose runtime pressure level"
```

## Task 3: Add Fast Mode Policy

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicy.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicyTest.java`

- [ ] **Step 1: Write policy tests**

Create `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicyTest.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import org.junit.jupiter.api.Test;

class NeedsResourceFastModePolicyTest {
    @Test
    void accurateNeverActivatesFastMode() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        for (int i = 0; i < 1_024; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, 100_000L, 1_000L);
        }

        assertFalse(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.ACCURATE, service, 1_000L));
    }

    @Test
    void alwaysFastIgnoresPressure() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();

        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.ALWAYS_FAST, service, 1_000L));
    }

    @Test
    void autoFastActivatesOnHotSearchOrPathPressure() {
        TameworkRuntimePressureService searchPressure = new TameworkRuntimePressureService();
        TameworkRuntimePressureService pathPressure = new TameworkRuntimePressureService();
        for (int i = 0; i < 512; i++) {
            searchPressure.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, 1_000L);
            pathPressure.recordWork(RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, 100_000L, 1_000L);
        }

        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, searchPressure, 1_000L));
        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, pathPressure, 1_000L));
    }

    @Test
    void autoFastStaysAccurateBelowHotPressure() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        for (int i = 0; i < 128; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, 1_000L);
        }

        assertFalse(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, service, 1_000L));
    }
}
```

- [ ] **Step 2: Run failing policy tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceFastModePolicyTest test
```

Expected: compile failure because `NeedsResourceFastModePolicy` does not exist.

- [ ] **Step 3: Add policy helper**

Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicy.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.RuntimePressureLevel;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Chooses whether needs resource seeking should bypass pathing and consume directly from source targets.
 */
public final class NeedsResourceFastModePolicy {
    private NeedsResourceFastModePolicy() {
    }

    public static boolean isFastModeActive(long nowMs) {
        return isFastModeActive(
                TameworkRuntimeSettings.needsResourceMode(NeedsResourceMode.ACCURATE.toConfigValue()),
                TameworkRuntimePressureService.getInstance(),
                nowMs
        );
    }

    static boolean isFastModeActive(@Nullable NeedsResourceMode mode,
                                    @Nonnull TameworkRuntimePressureService pressureService,
                                    long nowMs) {
        NeedsResourceMode resolved = mode != null ? mode : NeedsResourceMode.ACCURATE;
        return switch (resolved) {
            case ACCURATE -> false;
            case ALWAYS_FAST -> true;
            case AUTO_FAST -> pressureService.isAtLeast(
                    RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                    RuntimePressureLevel.HOT,
                    nowMs
            ) || pressureService.isAtLeast(
                    RuntimePressureDomain.NEEDS_PATH_PREFLIGHT,
                    RuntimePressureLevel.HOT,
                    nowMs
            );
        };
    }
}
```

- [ ] **Step 4: Run policy tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceFastModePolicyTest,TameworkRuntimePressureServiceTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicy.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceFastModePolicyTest.java
git commit -m "Perf: add needs fast mode policy"
```

## Task 4: Sensor Fast Path

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTarget.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTargetFastModeTest.java`

- [ ] **Step 1: Add source-level tests**

Create `src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTargetFastModeTest.java`:

```java
package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensorTameworkNeedsResourceTargetFastModeTest {
    @Test
    void fastModeBypassesPathPreflightOnlyWhenTargetExists() {
        assertTrue(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(true, true));
        assertFalse(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(true, false));
        assertFalse(SensorTameworkNeedsResourceTarget.shouldBypassPathPreflightForTests(false, true));
    }

    @Test
    void fastModeUsesDiagnosticReason() {
        assertEquals(
                "food_target_search_primary_fast_consume",
                SensorTameworkNeedsResourceTarget.fastModeReasonForTests("food_target_search_primary")
        );
    }
}
```

- [ ] **Step 2: Run failing sensor tests**

Run:

```powershell
.\mvnw.cmd -Dtest=SensorTameworkNeedsResourceTargetFastModeTest test
```

Expected: compile failure because the test hooks do not exist.

- [ ] **Step 3: Add preflight bypass hook**

In `SensorTameworkNeedsResourceTarget.matches(...)`, compute fast mode before resolving targets:

```java
boolean fastModeActive = NeedsResourceFastModePolicy.isFastModeActive(nowMs);
TargetResolution resolution = switch (resourceType) {
    case WATER -> resolveWaterTarget(ref, role, store, needsConfig, npcUuid, worldName, nowMs, fastModeActive);
    case FOOD_CONTAINER -> resolveFoodTarget(ref, role, store, needsConfig, npcUuid, worldName, nowMs, fastModeActive);
};
```

Update `resolveWaterTarget` and `resolveFoodTarget` signatures to accept `boolean fastModeActive`, and create the target rejector only when accurate mode is active:

```java
TargetRejector targetRejector = fastModeActive
        ? null
        : createTargetRejector(npcUuid, ResourceType.WATER, worldName, nowMs);
```

Use `ResourceType.FOOD_CONTAINER` for the food method.

After `target == null` handling and before `npcUuid == null` preflight failure handling, add:

```java
if (shouldBypassPathPreflight(fastModeActive, target != null)) {
    cacheTarget(npcUuid, target, fastModeReason(resolution.reason()), resolution.approachRadius(), currentCacheBlock, nowMs);
    if (resourceType == ResourceType.WATER) {
        recentTargetCache.remember(npcUuid, target, nowMs);
    }
    positionInfo.setTarget(target.x, target.y, target.z);
    maybeLog(
            ref,
            store,
            npcId,
            roleId,
            resolveResourceLabel(),
            "target_found",
            fastModeReason(resolution.reason()),
            false,
            eligibility.currentRatio(),
            target
    );
    return true;
}
```

Add helpers:

```java
private static boolean shouldBypassPathPreflight(boolean fastModeActive, boolean hasTarget) {
    return fastModeActive && hasTarget;
}

@Nonnull
private static String fastModeReason(@Nonnull String reason) {
    return reason.endsWith("_fast_consume") ? reason : reason + "_fast_consume";
}

static boolean shouldBypassPathPreflightForTests(boolean fastModeActive, boolean hasTarget) {
    return shouldBypassPathPreflight(fastModeActive, hasTarget);
}

@Nonnull
static String fastModeReasonForTests(@Nonnull String reason) {
    return fastModeReason(reason);
}
```

Import `NeedsResourceFastModePolicy`.

- [ ] **Step 4: Run sensor tests**

Run:

```powershell
.\mvnw.cmd -Dtest=SensorTameworkNeedsResourceTargetFastModeTest,SensorTameworkNeedsResourceTargetItemIdsTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTarget.java src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceTargetFastModeTest.java
git commit -m "Perf: bypass needs path preflight in fast mode"
```

## Task 5: Asset Sensor For Fast Consume Branch

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/sensors/BuilderSensorTameworkNeedsResourceFastMode.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastMode.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastModeRegistrationTest.java`

- [ ] **Step 1: Add registration guard test**

Create `src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastModeRegistrationTest.java`:

```java
package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SensorTameworkNeedsResourceFastModeRegistrationTest {
    @Test
    void builderIdIsRegisteredAndDocumentedInRegistrar() throws Exception {
        String registrar = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java"),
                StandardCharsets.UTF_8
        );
        String docs = Files.readString(
                Path.of("docs/Actions-Sensors-Components.md"),
                StandardCharsets.UTF_8
        );

        assertTrue(registrar.contains("BuilderSensorTameworkNeedsResourceFastMode"));
        assertTrue(docs.contains("TameworkNeedsResourceFastMode"));
    }
}
```

- [ ] **Step 2: Run failing registration test**

Run:

```powershell
.\mvnw.cmd -Dtest=SensorTameworkNeedsResourceFastModeRegistrationTest test
```

Expected: assertion failure because the builder is not registered or documented.

- [ ] **Step 3: Add builder and sensor**

Create `BuilderSensorTameworkNeedsResourceFastMode.java`:

```java
package com.alechilles.alecstamework.npc.sensors;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

/**
 * Builder for SensorTameworkNeedsResourceFastMode.
 */
public final class BuilderSensorTameworkNeedsResourceFastMode extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceFastMode";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderSensorTameworkNeedsResourceFastMode readConfig(JsonElement element) {
        return this;
    }

    public SensorTameworkNeedsResourceFastMode build(BuilderSupport support) {
        return new SensorTameworkNeedsResourceFastMode(this);
    }

    public String getShortDescription() {
        return "Matches while needs fast consume mode is active.";
    }

    public String getLongDescription() {
        return "Custom sensor used by needs resource role templates to skip movement and consume directly from a resolved food or water source while /tw settings enables fast resource mode.";
    }
}
```

Create `SensorTameworkNeedsResourceFastMode.java`:

```java
package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsResourceFastModePolicy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Sensor that exposes the global needs fast consume policy to role assets.
 */
public final class SensorTameworkNeedsResourceFastMode extends TameworkSensorBase {
    public SensorTameworkNeedsResourceFastMode(@Nonnull BuilderSensorTameworkNeedsResourceFastMode builder) {
        super(builder);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        return super.matches(ref, role, dt, store)
                && NeedsResourceFastModePolicy.isFastModeActive(System.currentTimeMillis());
    }
}
```

In `TameworkNpcBuilderRegistrar`, register `BuilderSensorTameworkNeedsResourceFastMode` beside other custom sensors.

In `docs/Actions-Sensors-Components.md`, add:

```markdown
- `TameworkNeedsResourceFastMode`: Matches while `/tw settings` has needs resource mode in active fast-consume behavior.
```

- [ ] **Step 4: Run registration test**

Run:

```powershell
.\mvnw.cmd -Dtest=SensorTameworkNeedsResourceFastModeRegistrationTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/sensors/BuilderSensorTameworkNeedsResourceFastMode.java src/main/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastMode.java src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java docs/Actions-Sensors-Components.md src/test/java/com/alechilles/alecstamework/npc/sensors/SensorTameworkNeedsResourceFastModeRegistrationTest.java
git commit -m "Feat: expose needs fast mode sensor"
```

## Task 6: Role Template Fast Consume Branch

**Files:**
- Modify: `src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json`
- Test: `src/test/java/com/alechilles/alecstamework/npc/assets/NeedsSeekResourceFastModeAssetTest.java`

- [ ] **Step 1: Add asset structure test**

Create `src/test/java/com/alechilles/alecstamework/npc/assets/NeedsSeekResourceFastModeAssetTest.java`:

```java
package com.alechilles.alecstamework.npc.assets;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NeedsSeekResourceFastModeAssetTest {
    @Test
    void needsSeekResourceHasFastConsumeBranchBeforeSeekMotion() throws Exception {
        String asset = Files.readString(
                Path.of("src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json"),
                StandardCharsets.UTF_8
        );

        int fastBranch = asset.indexOf("\"$Comment\": \"Fast mode: consume directly from the stored resource target.\"");
        int seekBranch = asset.indexOf("\"$Comment\": \"Seek toward the stored destination.\"");

        assertTrue(fastBranch >= 0, "Needs seek asset must include a fast consume branch.");
        assertTrue(seekBranch >= 0, "Needs seek asset must keep the accurate movement branch.");
        assertTrue(fastBranch < seekBranch, "Fast consume branch must run before movement.");
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceFastMode\""));
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceConsume\""));
    }
}
```

- [ ] **Step 2: Run failing asset test**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSeekResourceFastModeAssetTest test
```

Expected: assertion failure because the fast branch is absent.

- [ ] **Step 3: Insert fast consume branch before movement**

In `Component_Tamework_Instruction_Needs_Seek_Resource.json`, insert this branch immediately before the object whose comment is `"Seek toward the stored destination."`:

```json
{
  "$Comment": "Fast mode: consume directly from the stored resource target.",
  "Continue": true,
  "Sensor": {
    "Type": "And",
    "Sensors": [
      {
        "Type": "TameworkNeedsResourceFastMode"
      },
      {
        "Type": "ReadPosition",
        "Slot": {
          "Compute": "NeedsSeekActiveTargetSlot"
        },
        "Range": {
          "Compute": "NeedsSeekReadRange"
        }
      }
    ]
  },
  "Actions": [
    {
      "Type": "TameworkNeedsResourceMovementDiagnostic",
      "ResourceType": {
        "Compute": "NeedsSeekResourceType"
      },
      "Stage": "fast_consume",
      "Detail": "pathing_bypassed"
    },
    {
      "Type": "TameworkNeedsResourceConsume",
      "ResourceType": {
        "Compute": "NeedsSeekResourceType"
      },
      "FoodItemIDs": {
        "Compute": "NeedsSeekFoodItemIDs"
      }
    },
    {
      "Type": "State",
      "State": ".PostConsume"
    }
  ]
}
```

Keep the existing accurate movement, consume delay, timeout, repeated-consume, and release branches unchanged.

- [ ] **Step 4: Run asset test**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSeekResourceFastModeAssetTest,SensorTameworkNeedsResourceFastModeRegistrationTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json src/test/java/com/alechilles/alecstamework/npc/assets/NeedsSeekResourceFastModeAssetTest.java
git commit -m "Feat: add needs fast consume asset branch"
```

## Task 7: Settings UI

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsValues.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPageTextBinder.java`
- Modify: `src/main/resources/Common/UI/Custom/TameworkSettingsPage.ui`
- Modify: `src/main/resources/Server/Languages/en-US/server.lang`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPageLocalizationTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPresetTest.java`

- [ ] **Step 1: Add UI/localization guard test**

Add to `TameworkSettingsPageLocalizationTest`:

```java
@Test
void needsResourceModeUsesLocalizedSettingsBindings() throws Exception {
    String page = Files.readString(
            Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java"),
            StandardCharsets.UTF_8
    );
    String binder = Files.readString(
            Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPageTextBinder.java"),
            StandardCharsets.UTF_8
    );
    String ui = Files.readString(
            Path.of("src/main/resources/Common/UI/Custom/TameworkSettingsPage.ui"),
            StandardCharsets.UTF_8
    );
    String lang = Files.readString(
            Path.of("src/main/resources/Server/Languages/en-US/server.lang"),
            StandardCharsets.UTF_8
    );

    assertTrue(page.contains("KEY_NEEDS_RESOURCE_MODE"));
    assertTrue(page.contains("needsResourceModeEntries()"));
    assertTrue(binder.contains("#TwSettingsNeedsResourceModeLabel"));
    assertTrue(binder.contains("#TwSettingsNeedsResourceModeTooltip"));
    assertTrue(ui.contains("TwSettingsNeedsResourceModeDropdown"));
    assertTrue(lang.contains("tamework.ui.settings.label.needsResourceMode="));
    assertTrue(lang.contains("tamework.ui.settings.needsResourceMode.autoFast="));
}
```

- [ ] **Step 2: Run failing UI test**

Run:

```powershell
.\mvnw.cmd -Dtest=TameworkSettingsPageLocalizationTest test
```

Expected: assertion failure because the UI field is absent.

- [ ] **Step 3: Carry mode through form values**

In `TameworkSettingsValues`, add `@Nonnull NeedsResourceMode needsResourceMode` immediately after `boolean needsEnabled`.

Update:
- `toGlobalSettingsSnapshot()` to pass `needsResourceMode.toConfigValue()`.
- `withExperienceSettings(...)` to preserve `needsResourceMode`.
- `fromResolvedSettings(...)` to use `NeedsResourceMode.fromConfigValue(settings.needsResourceMode())`.
- All `new TameworkSettingsValues(...)` call sites and tests.

- [ ] **Step 4: Add UI event/render/parse handling**

In `TameworkSettingsPage`:

Add key:

```java
private static final String KEY_NEEDS_RESOURCE_MODE = "@NeedsResourceMode";
```

Append form data after needs enabled:

```java
.append(KEY_NEEDS_RESOURCE_MODE, "#TwSettingsNeedsResourceModeDropdown.Value")
```

Render entries and selected value after needs enabled:

```java
commandBuilder.set("#TwSettingsNeedsResourceModeDropdown.Entries", needsResourceModeEntries());
commandBuilder.set("#TwSettingsNeedsResourceModeDropdown.Value", currentValues.needsResourceMode().toConfigValue());
```

Parse:

```java
String needsResourceModeValue = trim(payload.needsResourceMode);
if (needsResourceModeValue.isBlank()) {
    needsResourceModeValue = currentValues.needsResourceMode().toConfigValue();
}
NeedsResourceMode needsResourceMode = NeedsResourceMode.fromConfigValue(needsResourceModeValue);
```

Pass `needsResourceMode` immediately after `needsEnabled` in the `TameworkSettingsValues` constructor call.

Add entries method:

```java
private List<DropdownEntryInfo> needsResourceModeEntries() {
    return List.of(
            new DropdownEntryInfo(
                    LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.accurate")),
                    NeedsResourceMode.ACCURATE.toConfigValue()
            ),
            new DropdownEntryInfo(
                    LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.autoFast")),
                    NeedsResourceMode.AUTO_FAST.toConfigValue()
            ),
            new DropdownEntryInfo(
                    LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.alwaysFast")),
                    NeedsResourceMode.ALWAYS_FAST.toConfigValue()
            )
    );
}
```

Add payload codec and field:

```java
.<String>append(new KeyedCodec<>(KEY_NEEDS_RESOURCE_MODE, Codec.STRING), (x, v) -> x.needsResourceMode = v, x -> x.needsResourceMode).add()
```

```java
private String needsResourceMode;
```

Import `NeedsResourceMode`.

- [ ] **Step 5: Add UI document row**

In `TameworkSettingsPage.ui`, add a dropdown row in the needs section after `#TwSettingsNeedsEnabledCheck` and before `#TwSettingsNeedsDamageEnabledLabel`. Use the existing label/tooltip/dropdown pattern:

```text
Label #TwSettingsNeedsResourceModeLabel {
  Text: "Needs Resource Mode";
}
TextButton #TwSettingsNeedsResourceModeTooltip {
  TextTooltipStyle: @SettingsLabelTooltipStyle;
  Style: @SettingsLabelTooltipButtonStyle;
}
DropdownBox #TwSettingsNeedsResourceModeDropdown {
  Style: (...$C.@DefaultDropdownBoxStyle, HorizontalPadding: 8);
}
```

Use the same row/grid structure as `#TwSettingsNeedsTickPolicyModeDropdown`.

- [ ] **Step 6: Bind text and language keys**

In `TameworkSettingsPageTextBinder`, add:

```java
{"#TwSettingsNeedsResourceModeLabel", "tamework.ui.settings.label.needsResourceMode"},
```

and:

```java
{"#TwSettingsNeedsResourceModeTooltip", "tamework.ui.settings.tooltip.needsResourceMode"},
```

and:

```java
{"#TwSettingsNeedsResourceModeDropdown", "tamework.ui.settings.noItems.needsResourceMode"},
```

In `server.lang`, add:

```properties
tamework.ui.settings.label.needsResourceMode=Needs Resource Mode
tamework.ui.settings.tooltip.needsResourceMode=Controls how hungry or thirsty companions reach food and water. Accurate uses path checks and movement. Auto Fast keeps accurate behavior until needs search/path pressure is high. Always Fast consumes directly from valid nearby sources without movement.
tamework.ui.settings.needsResourceMode.accurate=Accurate
tamework.ui.settings.needsResourceMode.autoFast=Auto Fast
tamework.ui.settings.needsResourceMode.alwaysFast=Always Fast
tamework.ui.settings.noItems.needsResourceMode=No resource modes available
```

- [ ] **Step 7: Run UI tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TameworkSettingsPageLocalizationTest,TameworkSettingsPresetTest,BuiltInTameworkLanguageKeyCoverageTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsValues.java src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPageTextBinder.java src/main/resources/Common/UI/Custom/TameworkSettingsPage.ui src/main/resources/Server/Languages/en-US/server.lang src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPageLocalizationTest.java src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPresetTest.java
git commit -m "Feat: add needs resource mode setting"
```

## Task 8: Patch Conditions And Docs

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionContext.java`
- Modify: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionTest.java`
- Modify: `docs/Config-Discovery.md`
- Modify: `wiki/Modder-Documentation/Testing-and-Diagnostics/Tamework-Settings-UI-and-Persistence.md`
- Modify: `wiki/Modder-Documentation/Optional-Integrations/Asset-Patches-Guide.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add patch-condition aliases test**

In `AssetPatchConditionTest`, add resource mode to the needs setting cases:

```java
setting("needsResourceMode", settings.needsResourceMode()),
setting("needs.resourceMode", settings.needsResourceMode()),
```

- [ ] **Step 2: Run failing patch-condition test**

Run:

```powershell
.\mvnw.cmd -Dtest=AssetPatchConditionTest test
```

Expected: assertion failure until the settings path map includes the new aliases.

- [ ] **Step 3: Add condition aliases**

In `AssetPatchConditionContext.settingsByPath(...)`, add:

```java
put(values, settings.needsResourceMode(),
        "needsResourceMode", "needs.resourceMode");
```

- [ ] **Step 4: Update docs and changelog**

Add to `docs/Config-Discovery.md` settings-owned list:

```markdown
- Needs resource mode is owned by `/tw settings`: `Accurate` keeps path preflight and movement, `AutoFast` bypasses food/water movement only under high needs pressure, and `AlwaysFast` always consumes directly from valid nearby food/water sources.
```

Add to the settings wiki page under the Needs section:

```markdown
- `needs.resourceMode`: controls hunger/thirst resource acquisition.
  - `Accurate`: path-preflight and movement flow.
  - `AutoFast`: uses accurate behavior until needs resource search or path-preflight pressure becomes high, then consumes directly from valid nearby sources.
  - `AlwaysFast`: always skips needs resource pathing and movement, consuming from valid nearby sources when found.
```

Update the optional asset patch setting aliases table with `needsResourceMode` and `needs.resourceMode`.

Add to `CHANGELOG.md` under the current unreleased section:

```markdown
- Added a `/tw settings` Needs Resource Mode with Accurate, Auto Fast, and Always Fast options so large animal-heavy servers can bypass food/water pathing and consume directly from valid nearby resources when desired.
```

- [ ] **Step 5: Run docs/tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AssetPatchConditionTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionContext.java src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionTest.java docs/Config-Discovery.md wiki/Modder-Documentation/Testing-and-Diagnostics/Tamework-Settings-UI-and-Persistence.md wiki/Modder-Documentation/Optional-Integrations/Asset-Patches-Guide.md CHANGELOG.md
git commit -m "Docs: document needs resource mode"
```

## Task 9: Final Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run focused feature suite**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceModeTest,TameworkSettingsStoreTest,TameworkRuntimePressureServiceTest,NeedsResourceFastModePolicyTest,SensorTameworkNeedsResourceTargetFastModeTest,SensorTameworkNeedsResourceTargetItemIdsTest,SensorTameworkNeedsResourceFastModeRegistrationTest,NeedsSeekResourceFastModeAssetTest,TameworkSettingsPageLocalizationTest,TameworkSettingsPresetTest,AssetPatchConditionTest,BuiltInTameworkLanguageKeyCoverageTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run thread-affinity grep**

Run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no new matches in needs runtime tick/system paths.

- [ ] **Step 3: Run guard tests**

Run:

```powershell
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest,DamageExecutionWriteSafetyGuardTest,NeedsDamageDispatchGuardTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run full tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Build package**

Run:

```powershell
.\mvnw.cmd -DskipTests package
```

Expected: package build succeeds.

## Self-Review

- Spec coverage: The plan covers a settings-panel mode, an automatic pressure-triggered mode, an always-fast mode, skipping pathing, direct consume from scanned valid resources, and documentation for server owners.
- Base-game first policy: The behavior stays in existing role assets and Tamework sensors/actions. Java is limited to settings persistence, runtime policy, and a small asset-facing sensor because the global pressure trigger cannot be expressed in static role JSON alone.
- Performance risk control: Fast mode skips path preflight and movement only after a valid resource target is resolved by existing scans. It reuses existing scan caches, pressure backoff, and consume services instead of adding a second scanner.
- Compatibility: Default mode is `Accurate`, so older worlds keep current behavior until a server owner changes `/tw settings`.
