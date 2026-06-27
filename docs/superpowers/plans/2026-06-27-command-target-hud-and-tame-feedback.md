# Command Target HUD and Tame Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add clearer companion taming feedback and a compact right-side HUD that appears while holding any Tamework command item and looking at a supported NPC within 6 units.

**Architecture:** Keep the linked panel as the source of truth for companion status presentation by extracting reusable loaded-NPC snapshot services instead of copying UI assembly. Add thin presentation adapters for tame notifications and the target HUD. Keep runtime/tick logic read-only, throttled, and thread-safe.

**Tech Stack:** Java 25, Maven/JUnit 5, Hytale Custom UI/HUD APIs, Tamework `TwInteractionConfig`, `TwCommandItemConfig`, `TwAttachmentDisplayConfig`, localized `Server/Languages/*/server.lang`.

---

## Scope And Constraints

- Source repo: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework`.
- Runtime copy under `C:\Users\22ale\AppData\Roaming\Hytale\UserData\Mods\alecstamework` is not the edit target for this plan.
- Do not add Java behavior when existing Tamework/base-game asset data can answer the question.
- Do not add broad per-tick scans. Target HUD lookup must be driven by the local player holding a command item and a direct crosshair target lookup.
- Runtime system classes must not mutate ECS components directly. This feature should only read components.
- Preserve the existing linked panel behavior and visual layout.

## File Map

### New Java Files

- `src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkResult.java`
  - Result record returned by auto-link attempts after taming.
- `src/main/java/com/alechilles/alecstamework/items/CommandItemDisplayResolver.java`
  - Resolves command item display name and optional crafting station label.
- `src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java`
  - Shared loaded-NPC status snapshot builder currently embedded in `CommandLinkedPanelEntryService`.
- `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
  - Orchestrates held command item checks, target resolution, snapshot building, and HUD show/hide.
- `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudViewModel.java`
  - Compact HUD model built from a `LinkedNpcEntry` plus food, attachments, and tame requirement rows.
- `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudFoodResolver.java`
  - Resolves favorite food icon/name from feed/loved-item role parameters and interaction config.
- `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudAttachmentResolver.java`
  - Resolves attachment display rows from `TameworkAttachmentsComponent` and `AttachmentDisplayResolver`.
- `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudTameRequirementResolver.java`
  - Resolves whether the target tame flow requires tranquilizer stacks.
- `src/main/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayService.java`
  - Shared stack/time math extracted from the NameplateBuilder integration.
- `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java`
  - Custom HUD class with stable HUD key and UI binding.
- `src/main/java/com/alechilles/alecstamework/ui/CommandTargetHudBinder.java`
  - Binds `CommandTargetHudViewModel` to `TameworkCommandTargetHud.ui`.

### Modified Java Files

- `src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkService.java`
  - Return `CommandAutoLinkResult`; keep existing static entry points compatible where needed.
- `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionExecutor.java`
  - Emit localized tame auto-link notifications after a successful tame.
- `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java`
  - Delegate loaded status assembly to `CommandLoadedNpcStatusSnapshotService`.
- `src/main/java/com/alechilles/alecstamework/items/CommandPanelEntrySourceService.java`
  - Use the shared loaded snapshot service for nearby-mode rows instead of partial duplicate health/trait assembly.
- `src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java`
  - Use `TranquilizerStackDisplayService`.
- `src/main/java/com/alechilles/alecstamework/Tamework.java`
  - Register/start the target HUD service if current Hytale APIs require a system/listener registration.

### New UI Asset

- `src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui`
  - Compact right-side HUD shell.

### Modified Language Files

- `src/main/resources/Server/Languages/en-US/server.lang`
- `src/main/resources/Server/Languages/de-DE/server.lang`
- `src/main/resources/Server/Languages/fr-FR/server.lang`
- `src/main/resources/Server/Languages/fr-CA/server.lang`
- `src/main/resources/Server/Languages/pt-BR/server.lang`

### Modified Docs

- `docs/Command-Items.md`
- `CHANGELOG.md`

### New/Modified Tests

- `src/test/java/com/alechilles/alecstamework/items/CommandAutoLinkServiceResultTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandItemDisplayResolverTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotServiceTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudViewModelTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudFoodResolverTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudAttachmentResolverTest.java`
- `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudTameRequirementResolverTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayServiceTest.java`
- Modify `src/test/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridgeTest.java`
- Modify `src/test/java/com/alechilles/alecstamework/localization/BuiltInTameworkLanguageKeyCoverageTest.java`

---

### Task 1: Extract Shared Tranquilizer Stack Math

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayService.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayServiceTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java`
- Modify: `src/test/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridgeTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `TranquilizerStackDisplayServiceTest`:

```java
package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TranquilizerStackDisplayServiceTest {
    @Test
    void computeStacksRoundsFromThirtySecondStackDuration() {
        Assertions.assertEquals(0, TranquilizerStackDisplayService.computeStacks(0.0));
        Assertions.assertEquals(1, TranquilizerStackDisplayService.computeStacks(30.0));
        Assertions.assertEquals(3, TranquilizerStackDisplayService.computeStacks(80.0));
        Assertions.assertEquals(4, TranquilizerStackDisplayService.computeStacks(105.0));
    }

    @Test
    void resolvesPeakDurationFromTrackedAndCurrentValues() {
        Assertions.assertEquals(90.0, TranquilizerStackDisplayService.resolvePeakDuration(90.0, 30.0));
        Assertions.assertEquals(45.0, TranquilizerStackDisplayService.resolvePeakDuration(0.0, 45.0));
        Assertions.assertEquals(0.0, TranquilizerStackDisplayService.resolvePeakDuration(-5.0, Double.NaN));
    }

    @Test
    void formatsRemainingDurationForHudAndNameplates() {
        Assertions.assertEquals("0s", TranquilizerStackDisplayService.formatRemainingDuration(0.0));
        Assertions.assertEquals("12s", TranquilizerStackDisplayService.formatRemainingDuration(11.2));
        Assertions.assertEquals("1m 5s", TranquilizerStackDisplayService.formatRemainingDuration(64.2));
    }

    @Test
    void formatsCombinedVariant() {
        Assertions.assertEquals(
                "3 (1m 45s)",
                TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 0)
        );
        Assertions.assertEquals("3", TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 1));
        Assertions.assertEquals("1m 45s", TranquilizerStackDisplayService.formatStackValue(3, "1m 45s", 2));
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\mvnw.cmd -Dtest=TranquilizerStackDisplayServiceTest test
```

Expected: compilation failure because `TranquilizerStackDisplayService` does not exist.

- [ ] **Step 3: Implement the shared service**

Create:

```java
package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nullable;

/** Shared formatting and stack-count math for Tamework tranquilizer presentation. */
public final class TranquilizerStackDisplayService {
    public static final double STACK_DURATION_SECONDS = 30.0;

    private TranquilizerStackDisplayService() {
    }

    public static int computeStacks(double initialDurationSeconds) {
        if (!Double.isFinite(initialDurationSeconds) || initialDurationSeconds <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(initialDurationSeconds / STACK_DURATION_SECONDS));
    }

    public static double resolvePeakDuration(double trackedPeakSeconds, double currentRemainingSeconds) {
        double tracked = sanitizePositive(trackedPeakSeconds);
        double current = sanitizePositive(currentRemainingSeconds);
        return Math.max(tracked, current);
    }

    public static String formatRemainingDuration(double remainingSeconds) {
        long totalSeconds = Math.max(0L, (long) Math.ceil(remainingSeconds));
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return totalSeconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    @Nullable
    public static String formatStackValue(int stacks, @Nullable String remainingText, int variantIndex) {
        return switch (variantIndex) {
            case 1 -> stacks > 0 ? Integer.toString(stacks) : null;
            case 2 -> remainingText;
            default -> {
                if (stacks <= 0) {
                    yield remainingText;
                }
                if (remainingText == null || remainingText.isBlank()) {
                    yield Integer.toString(stacks);
                }
                yield stacks + " (" + remainingText + ")";
            }
        };
    }

    private static double sanitizePositive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}
```

- [ ] **Step 4: Update NameplateBuilder bridge to call the shared service**

Replace the old constants/methods in `NameplateBuilderCompanionSegmentBridge`:

```java
import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
```

Then update calls:

```java
int stacks = TranquilizerStackDisplayService.computeStacks(peakRemainingSeconds);
return TranquilizerStackDisplayService.formatStackValue(stacks, "inf", variantIndex);
```

```java
int stacks = TranquilizerStackDisplayService.computeStacks(
        TranquilizerStackDisplayService.resolvePeakDuration(peakRemainingSeconds, remainingSeconds)
);
return TranquilizerStackDisplayService.formatStackValue(
        stacks,
        TranquilizerStackDisplayService.formatRemainingDuration(remainingSeconds),
        variantIndex
);
```

```java
return TranquilizerStackDisplayService.resolvePeakDuration(trackedPeak, activeEffect.getRemainingDuration());
```

Keep package-private bridge methods only if existing tests still call them; otherwise move tests to the new service.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TranquilizerStackDisplayServiceTest,NameplateBuilderCompanionSegmentBridgeTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayService.java src/test/java/com/alechilles/alecstamework/npc/progression/TranquilizerStackDisplayServiceTest.java src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java src/test/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridgeTest.java
git commit -m "Refactor: share tranquilizer stack display math"
```

---

### Task 2: Return Auto-Link Results And Send Tame Notifications

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkResult.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandItemDisplayResolver.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionExecutor.java`
- Modify: `src/main/resources/Server/Languages/*/server.lang`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandAutoLinkServiceResultTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandItemDisplayResolverTest.java`

- [ ] **Step 1: Add result model test**

Create `CommandAutoLinkServiceResultTest`:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandAutoLinkServiceResultTest {
    @Test
    void linkedResultCarriesAnimalAndToolNames() {
        CommandAutoLinkResult result = CommandAutoLinkResult.linked("Tamed Fox", "Command Whistle");

        Assertions.assertEquals(CommandAutoLinkResult.Status.LINKED, result.status());
        Assertions.assertEquals("Tamed Fox", result.animalDisplayName());
        Assertions.assertEquals("Command Whistle", result.commandItemDisplayName());
    }

    @Test
    void missingToolResultCarriesCraftingHint() {
        CommandAutoLinkResult result = CommandAutoLinkResult.noApplicableTool(
                "Tamed Fox",
                "Command Whistle",
                "Crafting Bench"
        );

        Assertions.assertEquals(CommandAutoLinkResult.Status.NO_APPLICABLE_TOOL, result.status());
        Assertions.assertEquals("Tamed Fox", result.animalDisplayName());
        Assertions.assertEquals("Command Whistle", result.commandItemDisplayName());
        Assertions.assertEquals("Crafting Bench", result.craftingStationDisplayName());
    }
}
```

- [ ] **Step 2: Implement `CommandAutoLinkResult`**

```java
package com.alechilles.alecstamework.items;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Result of attempting to auto-link a newly tamed NPC to a command item. */
public record CommandAutoLinkResult(@Nonnull Status status,
                                    @Nullable String animalDisplayName,
                                    @Nullable String commandItemDisplayName,
                                    @Nullable String craftingStationDisplayName) {
    public enum Status {
        LINKED,
        NO_APPLICABLE_TOOL,
        SKIPPED,
        FAILED
    }

    public static CommandAutoLinkResult linked(String animalDisplayName, String commandItemDisplayName) {
        return new CommandAutoLinkResult(Status.LINKED, animalDisplayName, commandItemDisplayName, null);
    }

    public static CommandAutoLinkResult noApplicableTool(String animalDisplayName,
                                                         String commandItemDisplayName,
                                                         String craftingStationDisplayName) {
        return new CommandAutoLinkResult(
                Status.NO_APPLICABLE_TOOL,
                animalDisplayName,
                commandItemDisplayName,
                craftingStationDisplayName
        );
    }

    public static CommandAutoLinkResult skipped(String animalDisplayName) {
        return new CommandAutoLinkResult(Status.SKIPPED, animalDisplayName, null, null);
    }

    public static CommandAutoLinkResult failed(String animalDisplayName) {
        return new CommandAutoLinkResult(Status.FAILED, animalDisplayName, null, null);
    }
}
```

- [ ] **Step 3: Add display resolver**

Create `CommandItemDisplayResolver`:

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import javax.annotation.Nullable;

/** Resolves player-facing command item display labels for feedback and HUD text. */
final class CommandItemDisplayResolver {
    private static final String DEFAULT_COMMAND_ITEM_LABEL_KEY =
            "tamework.ui.notifications.tame.commandItem.default";
    private static final String DEFAULT_CRAFTING_STATION_KEY =
            "tamework.ui.notifications.tame.commandItem.craftingStation.default";

    String resolveItemDisplayName(@Nullable Player player, @Nullable String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            String itemKey = "items." + itemId.trim() + ".name";
            String localized = LocalizedText.resolve(player, itemKey);
            if (localized != null && !localized.isBlank() && !localized.equals(itemKey)) {
                return localized;
            }
            try {
                Item item = Item.getAssetMap().getAsset(itemId.trim());
                if (item != null && item.getTranslationKey() != null && !item.getTranslationKey().isBlank()) {
                    String translated = LocalizedText.resolve(player, item.getTranslationKey());
                    if (translated != null && !translated.isBlank() && !translated.equals(item.getTranslationKey())) {
                        return translated;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Tests and degraded startup can run without item assets.
            }
            return humanize(itemId);
        }
        return LocalizedText.resolve(player, DEFAULT_COMMAND_ITEM_LABEL_KEY);
    }

    String resolveCraftingStationDisplayName(@Nullable Player player) {
        return LocalizedText.resolve(player, DEFAULT_CRAFTING_STATION_KEY);
    }

    private String humanize(String itemId) {
        String value = itemId == null ? "" : itemId.trim().replace('_', ' ');
        return value.isBlank() ? "Command Item" : value;
    }
}
```

- [ ] **Step 4: Update `CommandAutoLinkService` to return results**

Change static method signature:

```java
public static CommandAutoLinkResult autoLinkNewlyTamedNpc(@Nullable Player player,
                                                          @Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store)
```

Keep a silent result for invalid state:

```java
if (registry == null) {
    return CommandAutoLinkResult.skipped(null);
}
```

Inside `autoLinkNewlyTamedNpcInternal`, resolve:

```java
String animalName = new CommandNpcNameResolver().resolveNpcDisplayNameFromComponents(npcRef, store);
```

When no candidate exists, return:

```java
ApplicableToolDisplay missing = resolveFirstApplicableToolDisplay(player, npcRef, store);
return CommandAutoLinkResult.noApplicableTool(
        animalName,
        missing.commandItemName(),
        missing.craftingStationName()
);
```

When a candidate links, return:

```java
return CommandAutoLinkResult.linked(
        animalName,
        displayResolver.resolveItemDisplayName(player, candidate.stack().getItemId())
);
```

Add a helper record:

```java
private record ApplicableToolDisplay(String commandItemName, String craftingStationName) {
}
```

Add a helper that scans enabled configs and finds the first config whose role policy applies to the tamed NPC. If no specific config applies, return defaults from `CommandItemDisplayResolver`.

- [ ] **Step 5: Send notifications from `InteractionExecutor`**

After:

```java
CommandAutoLinkService.autoLinkNewlyTamedNpc(player, npcRef, store);
```

replace with:

```java
CommandAutoLinkResult autoLink = CommandAutoLinkService.autoLinkNewlyTamedNpc(player, npcRef, store);
sendTameAutoLinkFeedback(player, autoLink);
```

Add:

```java
private void sendTameAutoLinkFeedback(Player player, CommandAutoLinkResult result) {
    if (player == null || result == null) {
        return;
    }
    InteractionUiMessageService ui = new InteractionUiMessageService();
    if (result.status() == CommandAutoLinkResult.Status.LINKED) {
        ui.showSuccessKey(
                player,
                "tamework.ui.notifications.tame.autoLink.linked",
                safe(result.animalDisplayName()),
                safe(result.commandItemDisplayName())
        );
        return;
    }
    if (result.status() == CommandAutoLinkResult.Status.NO_APPLICABLE_TOOL) {
        ui.showWarningKey(
                player,
                "tamework.ui.notifications.tame.autoLink.noTool",
                safe(result.animalDisplayName()),
                safe(result.commandItemDisplayName()),
                safe(result.craftingStationDisplayName())
        );
    }
}

private String safe(String value) {
    return value == null || value.isBlank() ? "Companion" : value;
}
```

If `InteractionUiMessageService` does not already expose `showSuccessKey` and `showWarningKey`, add those wrappers over `TameworkUiMessageService.showKey`.

- [ ] **Step 6: Add language keys**

Add to every bundled `server.lang`, with translated text for non-English files if maintainers want localized copy now; otherwise use English as a temporary built-in fallback:

```properties
tamework.ui.notifications.tame.autoLink.linked={0} tamed and linked to {1}.
tamework.ui.notifications.tame.autoLink.noTool={0} Tamed - No {1} found to link. Craft one at the {2}.
tamework.ui.notifications.tame.commandItem.default=command item
tamework.ui.notifications.tame.commandItem.craftingStation.default=crafting bench
```

- [ ] **Step 7: Run focused tests**

```powershell
.\mvnw.cmd -Dtest=CommandAutoLinkServiceResultTest,CommandItemDisplayResolverTest,BuiltInTameworkLanguageKeyCoverageTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkResult.java src/main/java/com/alechilles/alecstamework/items/CommandItemDisplayResolver.java src/main/java/com/alechilles/alecstamework/items/CommandAutoLinkService.java src/main/java/com/alechilles/alecstamework/npc/actions/InteractionExecutor.java src/main/resources/Server/Languages/en-US/server.lang src/main/resources/Server/Languages/de-DE/server.lang src/main/resources/Server/Languages/fr-FR/server.lang src/main/resources/Server/Languages/fr-CA/server.lang src/main/resources/Server/Languages/pt-BR/server.lang src/test/java/com/alechilles/alecstamework/items/CommandAutoLinkServiceResultTest.java src/test/java/com/alechilles/alecstamework/items/CommandItemDisplayResolverTest.java src/test/java/com/alechilles/alecstamework/localization/BuiltInTameworkLanguageKeyCoverageTest.java
git commit -m "Feat: notify players about tame command linking"
```

---

### Task 3: Extract Loaded NPC Status Snapshot Assembly

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandPanelEntrySourceService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotServiceTest.java`

- [ ] **Step 1: Add service shell and static calculation tests**

Create tests around pure helpers first:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandLoadedNpcStatusSnapshotServiceTest {
    @Test
    void computePercentClampsToZeroAndHundred() {
        Assertions.assertEquals(0, CommandLoadedNpcStatusSnapshotService.computePercentForTests(-10.0, 0.0, 100.0));
        Assertions.assertEquals(50, CommandLoadedNpcStatusSnapshotService.computePercentForTests(50.0, 0.0, 100.0));
        Assertions.assertEquals(100, CommandLoadedNpcStatusSnapshotService.computePercentForTests(150.0, 0.0, 100.0));
    }

    @Test
    void formatSignedUsesExplicitPositiveSign() {
        Assertions.assertEquals("+1.25", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(1.25));
        Assertions.assertEquals("-0.50", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(-0.5));
    }
}
```

- [ ] **Step 2: Create `CommandLoadedNpcStatusSnapshotService`**

Move these responsibilities out of `CommandLinkedPanelEntryService`:

- `readNpcHealthSnapshot`
- `readNpcHappinessSnapshot`
- `buildHappinessModifierBreakdown`
- `readNpcNeedsSnapshot`
- leveling/talent future stat construction
- trait indicator reads
- breeding/harvest cooldown snapshot reads

Add a public package-private method:

```java
LinkedNpcEntry buildLoadedEntry(Player player,
                                Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                NpcStatusContext context)
```

Define context:

```java
record NpcStatusContext(UUID npcUuid,
                        String fallbackDisplayName,
                        boolean linked,
                        boolean active,
                        boolean hasHome,
                        boolean breedingEnabled,
                        String groupId,
                        String groupName,
                        String groupColorHex,
                        String cachedRoleId,
                        String cachedNameKey) {
}
```

The service returns a `LinkedNpcEntry` with all loaded fields filled, and keeps death/captured/coop/lost behavior in `CommandLinkedPanelEntryService`.

- [ ] **Step 3: Modify linked panel service to delegate loaded assembly**

In `CommandLinkedPanelEntryService`, construct:

```java
this.loadedSnapshotService = new CommandLoadedNpcStatusSnapshotService(
        npcNameResolver,
        linkPolicyService,
        progressionPresentationService,
        cooldownSnapshotService
);
```

Replace the loaded block with:

```java
LinkedNpcEntry loadedEntry = loadedSnapshotService.buildLoadedEntry(
        player,
        npcRef,
        store,
        new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                record.npcUuid,
                displayName,
                true,
                active,
                hasHome,
                breedingEnabled,
                groupId,
                groupName,
                groupColor,
                record.cachedRoleId,
                record.cachedNameKey
        )
);
if (loadedEntry != null) {
    entries.add(loadedEntry);
    continue;
}
```

Keep existing unloaded/dead/captured/lost construction unchanged for records that are not loaded.

- [ ] **Step 4: Modify nearby panel source to use full loaded snapshots**

In `CommandPanelEntrySourceService`, replace the manual nearby `LinkedNpcEntry` constructor block with a call to `loadedSnapshotService.buildLoadedEntry`, passing `linked=false`, `active=true`, and group fields `null`.

- [ ] **Step 5: Run focused linked panel tests**

```powershell
.\mvnw.cmd -Dtest=CommandLoadedNpcStatusSnapshotServiceTest,CommandLinkedPanelEntryServiceHappinessTooltipTest,CommandLinkedPanelProgressionPresentationServiceTest,CommandLinkedPanelCooldownSnapshotServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java src/main/java/com/alechilles/alecstamework/items/CommandPanelEntrySourceService.java src/test/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotServiceTest.java
git commit -m "Refactor: share loaded command NPC status snapshots"
```

---

### Task 4: Add HUD Food, Attachment, And Tame Requirement View Models

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudViewModel.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudFoodResolver.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudAttachmentResolver.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudTameRequirementResolver.java`
- Test: corresponding resolver tests.

- [ ] **Step 1: Create view model**

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compact data model rendered by the command target HUD. */
public record CommandTargetHudViewModel(@Nonnull LinkedNpcEntry status,
                                        @Nullable FoodRow favoriteFood,
                                        @Nonnull List<AttachmentRow> attachments,
                                        @Nullable TameRequirementRow tameRequirement) {
    public CommandTargetHudViewModel {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record FoodRow(@Nonnull String itemId, @Nonnull String displayName, @Nullable String iconPath) {
    }

    public record AttachmentRow(@Nonnull String setLabel, @Nonnull String valueLabel) {
    }

    public record TameRequirementRow(boolean tranquilizerRequired,
                                     int requiredStacks,
                                     @Nullable String currentStacksText) {
    }
}
```

- [ ] **Step 2: Add attachment resolver test**

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class CommandTargetHudAttachmentResolverTest {
    @Test
    void capsAttachmentRowsAndReportsFriendlyLabels() {
        CommandTargetHudAttachmentResolver resolver = new CommandTargetHudAttachmentResolver(
                (roleId, modelId, attachments) -> List.of(
                        new ResolvedAttachmentDisplay("Coat", "Coat", "Black", "Black"),
                        new ResolvedAttachmentDisplay("Horns", "Horns", "Curled", "Curled"),
                        new ResolvedAttachmentDisplay("Mane", "Mane", "Long", "Long"),
                        new ResolvedAttachmentDisplay("Tail", "Tail", "Short", "Short")
                )
        );

        List<CommandTargetHudViewModel.AttachmentRow> rows = resolver.resolveRows(
                "Role",
                "Model",
                Map.of("Coat", "Black", "Horns", "Curled", "Mane", "Long", "Tail", "Short"),
                3
        );

        Assertions.assertEquals(3, rows.size());
        Assertions.assertEquals("Coat", rows.get(0).setLabel());
        Assertions.assertEquals("Black", rows.get(0).valueLabel());
    }
}
```

- [ ] **Step 3: Implement attachment resolver**

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Resolves compact attachment rows for the command target HUD. */
final class CommandTargetHudAttachmentResolver {
    private static final int DEFAULT_MAX_ROWS = 3;

    private final AttachmentDisplayResolver resolver;

    CommandTargetHudAttachmentResolver() {
        this(AttachmentDisplayResolver.ASSET_BACKED);
    }

    CommandTargetHudAttachmentResolver(AttachmentDisplayResolver resolver) {
        this.resolver = resolver != null ? resolver : AttachmentDisplayResolver.ASSET_BACKED;
    }

    List<CommandTargetHudViewModel.AttachmentRow> resolveRows(@Nullable String roleId,
                                                              @Nullable String modelId,
                                                              @Nullable Map<String, String> attachments) {
        return resolveRows(roleId, modelId, attachments, DEFAULT_MAX_ROWS);
    }

    List<CommandTargetHudViewModel.AttachmentRow> resolveRows(@Nullable String roleId,
                                                              @Nullable String modelId,
                                                              @Nullable Map<String, String> attachments,
                                                              int maxRows) {
        if (attachments == null || attachments.isEmpty() || maxRows <= 0) {
            return List.of();
        }
        List<ResolvedAttachmentDisplay> displays = resolver.resolveAll(roleId, modelId, attachments);
        ArrayList<CommandTargetHudViewModel.AttachmentRow> rows = new ArrayList<>();
        for (ResolvedAttachmentDisplay display : displays) {
            if (display == null || rows.size() >= maxRows) {
                break;
            }
            rows.add(new CommandTargetHudViewModel.AttachmentRow(
                    safe(display.setLabel(), display.setId()),
                    safe(display.valueLabel(), display.valueId())
            ));
        }
        return rows;
    }

    private String safe(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null || fallback.isBlank() ? "Unknown" : fallback;
    }
}
```

- [ ] **Step 4: Implement food resolver**

Create `CommandTargetHudFoodResolverTest`:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudFoodResolverTest {
    @Test
    void choosesFirstNonBlankItemId() {
        CommandTargetHudFoodResolver resolver = new CommandTargetHudFoodResolver(
                (player, itemId) -> "Name:" + itemId,
                itemId -> "Icon:" + itemId
        );

        CommandTargetHudViewModel.FoodRow row = resolver.resolveFavoriteFood(
                null,
                new String[] { "", "Tw_Feed_Herbivore", "Apple" }
        );

        Assertions.assertNotNull(row);
        Assertions.assertEquals("Tw_Feed_Herbivore", row.itemId());
        Assertions.assertEquals("Name:Tw_Feed_Herbivore", row.displayName());
        Assertions.assertEquals("Icon:Tw_Feed_Herbivore", row.iconPath());
    }

    @Test
    void returnsNullWhenNoFoodItemsExist() {
        CommandTargetHudFoodResolver resolver = new CommandTargetHudFoodResolver(
                (player, itemId) -> "Name:" + itemId,
                itemId -> "Icon:" + itemId
        );

        Assertions.assertNull(resolver.resolveFavoriteFood(null, new String[0]));
    }
}
```

Implement `CommandTargetHudFoodResolver`:

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Resolves compact favorite-food display rows for the command target HUD. */
final class CommandTargetHudFoodResolver {
    private final BiFunction<Player, String, String> nameResolver;
    private final Function<String, String> iconResolver;

    CommandTargetHudFoodResolver() {
        this(CommandTargetHudFoodResolver::resolveItemName, CommandTargetHudFoodResolver::resolveItemIcon);
    }

    CommandTargetHudFoodResolver(BiFunction<Player, String, String> nameResolver,
                                 Function<String, String> iconResolver) {
        this.nameResolver = nameResolver;
        this.iconResolver = iconResolver;
    }

    @Nullable
    CommandTargetHudViewModel.FoodRow resolveFavoriteFood(@Nullable Player player,
                                                          @Nullable String[] itemIds) {
        String itemId = firstNonBlank(itemIds);
        if (itemId == null) {
            return null;
        }
        return new CommandTargetHudViewModel.FoodRow(
                itemId,
                safe(nameResolver.apply(player, itemId), humanize(itemId)),
                iconResolver.apply(itemId)
        );
    }

    @Nullable
    private static String firstNonBlank(@Nullable String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return null;
        }
        for (String itemId : itemIds) {
            if (itemId != null && !itemId.isBlank()) {
                return itemId.trim();
            }
        }
        return null;
    }

    private static String resolveItemName(@Nullable Player player, String itemId) {
        String key = "items." + itemId + ".name";
        String localized = LocalizedText.resolve(player, key);
        if (localized != null && !localized.isBlank() && !localized.equals(key)) {
            return localized;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null && item.getTranslationKey() != null && !item.getTranslationKey().isBlank()) {
                String translated = LocalizedText.resolve(player, item.getTranslationKey());
                if (translated != null && !translated.isBlank() && !translated.equals(item.getTranslationKey())) {
                    return translated;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Asset maps are not always available in focused unit tests.
        }
        return humanize(itemId);
    }

    @Nullable
    private static String resolveItemIcon(String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            return item != null ? item.getIcon() : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String humanize(String itemId) {
        return itemId == null || itemId.isBlank() ? "Food" : itemId.replace('_', ' ');
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
```

In Task 6, pass item IDs from the same sources currently used by interaction food matching:

- `FeedInteraction.ItemsInHand`
- `FeedInteraction.ItemsParam`
- role loved-item/FoodItemIDs parameters resolved through the existing interaction parameter path

- [ ] **Step 5: Implement tame requirement resolver**

Create `CommandTargetHudTameRequirementResolverTest`:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudTameRequirementResolverTest {
    @Test
    void buildsNoRowWhenNoTranquilizerRequirementExists() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        Assertions.assertNull(resolver.fromRequiredRemainingSeconds(0.0, null));
    }

    @Test
    void convertsRequiredSecondsToStacks() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        CommandTargetHudViewModel.TameRequirementRow row =
                resolver.fromRequiredRemainingSeconds(80.0, null);

        Assertions.assertNotNull(row);
        Assertions.assertTrue(row.tranquilizerRequired());
        Assertions.assertEquals(3, row.requiredStacks());
        Assertions.assertNull(row.currentStacksText());
    }

    @Test
    void includesCurrentStackTextWhenAvailable() {
        CommandTargetHudTameRequirementResolver resolver = new CommandTargetHudTameRequirementResolver();

        CommandTargetHudViewModel.TameRequirementRow row =
                resolver.fromRequiredRemainingSeconds(90.0, "2 (42s)");

        Assertions.assertNotNull(row);
        Assertions.assertEquals(3, row.requiredStacks());
        Assertions.assertEquals("2 (42s)", row.currentStacksText());
    }
}
```

Implement the resolver:

```java
package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import javax.annotation.Nullable;

/** Resolves tame requirement rows shown in the command target HUD. */
final class CommandTargetHudTameRequirementResolver {
    static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";

    @Nullable
    CommandTargetHudViewModel.TameRequirementRow fromRequiredRemainingSeconds(double requiredRemainingSeconds,
                                                                              @Nullable String currentStacksText) {
        int requiredStacks = TranquilizerStackDisplayService.computeStacks(requiredRemainingSeconds);
        if (requiredStacks <= 0) {
            return null;
        }
        return new CommandTargetHudViewModel.TameRequirementRow(true, requiredStacks, currentStacksText);
    }
}
```

In Task 6, feed `fromRequiredRemainingSeconds` by deriving `requiredRemainingSeconds` from the active tame interaction config:

1. Find tame interaction entries for the role/config.
2. Inspect `Requirements.All` and `Requirements.Any`.
3. Match requirement payloads that reference `TameworkEffectActive` and `EffectId=Tw_Status_Tranquilized`.
4. Read `MinRemainingSeconds`.
5. If the NPC is currently tranquilized, read current/peak active effect and pass a formatted `currentStacksText`.

Expected display behavior:

- no row when no tranquilizer requirement is detected
- `Requires tranquilizer: 3 stacks` when required and not currently active
- `Requires tranquilizer: 3 stacks (current 2, 0:48)` when active

- [ ] **Step 6: Run focused resolver tests**

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudViewModelTest,CommandTargetHudFoodResolverTest,CommandTargetHudAttachmentResolverTest,CommandTargetHudTameRequirementResolverTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudViewModel.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudFoodResolver.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudAttachmentResolver.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudTameRequirementResolver.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudViewModelTest.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudFoodResolverTest.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudAttachmentResolverTest.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudTameRequirementResolverTest.java
git commit -m "Feat: build command target HUD detail model"
```

---

### Task 5: Build And Bind The Target HUD UI

**Files:**
- Create: `src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui`
- Create: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java`
- Create: `src/main/java/com/alechilles/alecstamework/ui/CommandTargetHudBinder.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/CommandTargetHudBinderTest.java`

- [ ] **Step 1: Add UI asset**

Create a compact right-side HUD asset with stable selectors:

- `#Root`
- `#Name`
- `#HealthText`
- `#HealthBar`
- `#HappinessRow`
- `#HungerRow`
- `#ThirstRow`
- `#LevelRow`
- `#TraitRow0` through `#TraitRow3`
- `#FoodRow`, `#FoodIcon`, `#FoodName`
- `#AttachmentRow0` through `#AttachmentRow2`
- `#HarvestCooldownRow`
- `#BreedingCooldownRow`
- `#TameRequirementRow`

Keep width around 220-260 px. Anchor to the right side, vertically near center-right, leaving normal crosshair and hotbar regions unobstructed.

- [ ] **Step 2: Create HUD class**

```java
package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** Right-side HUD shown while a command item targets a supported NPC. */
public final class TameworkCommandTargetHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:command_target";
    public static final String UI_PATH = "TameworkCommandTargetHud.ui";

    private final CommandTargetHudViewModel model;
    private final String language;

    public TameworkCommandTargetHud(@Nonnull PlayerRef playerRef,
                                    @Nonnull CommandTargetHudViewModel model,
                                    String language) {
        super(playerRef, HUD_KEY);
        this.model = model;
        this.language = language;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        CommandTargetHudBinder.bind(commandBuilder, model, language);
    }
}
```

- [ ] **Step 3: Implement binder**

Bind only available rows. Empty data must hide rows:

```java
commandBuilder.set("#FoodRow.Visible", model.favoriteFood() != null);
commandBuilder.set("#AttachmentRow0.Visible", model.attachments().size() > 0);
commandBuilder.set("#TameRequirementRow.Visible", model.tameRequirement() != null);
```

Reuse `LinkedNpcPanelVitalsBinder` logic where possible. If that binder is too panel-specific, extract row/ring helpers to avoid copying ring math.

- [ ] **Step 4: Add binder test**

Use source-contract assertions like existing UI tests:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

class CommandTargetHudBinderTest {
    @Test
    void binderControlsOptionalRowsExplicitly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/CommandTargetHudBinder.java"
        ));

        Assertions.assertTrue(source.contains("#FoodRow.Visible"));
        Assertions.assertTrue(source.contains("#AttachmentRow0.Visible"));
        Assertions.assertTrue(source.contains("#TameRequirementRow.Visible"));
    }

    @Test
    void uiAssetContainsExpectedSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui"
        ));

        Assertions.assertTrue(ui.contains("FoodRow"));
        Assertions.assertTrue(ui.contains("AttachmentRow0"));
        Assertions.assertTrue(ui.contains("TameRequirementRow"));
    }
}
```

- [ ] **Step 5: Run focused UI tests**

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudBinderTest,LinkedNpcPanelCardLayoutTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java src/main/java/com/alechilles/alecstamework/ui/CommandTargetHudBinder.java src/test/java/com/alechilles/alecstamework/ui/CommandTargetHudBinderTest.java
git commit -m "Feat: add command target HUD UI"
```

---

### Task 6: Wire Runtime Target Detection And HUD Updates

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java`
- Test: architecture guard tests already exist.

- [ ] **Step 1: Add service behavior tests**

Create tests for pure decision helpers:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudServiceTest {
    @Test
    void shouldRefreshWhenTargetChanges() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests("old", "new", 0L, 0L, 1000L));
    }

    @Test
    void shouldNotRefreshSameTargetBeforeThrottleWindow() {
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests("same", "same", 1000L, 1200L, 1000L));
    }

    @Test
    void shouldRefreshSameTargetAfterThrottleWindow() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests("same", "same", 1000L, 2200L, 1000L));
    }
}
```

- [ ] **Step 2: Implement service**

Responsibilities:

1. Determine active held item.
2. Check held item against `CommandItemRegistry`.
3. Resolve target with `TargetUtil.getTargetEntity(playerRef, 6.0f, store)`.
4. Validate target is an NPC and role is allowed/supported.
5. Build `LinkedNpcEntry` with `CommandLoadedNpcStatusSnapshotService`.
6. Add favorite food, attachments, and tame requirement rows.
7. Show `TameworkCommandTargetHud` for valid target.
8. Hide HUD for invalid/no target.

Static helpers:

```java
static boolean shouldRefreshForTests(String previousTarget,
                                     String currentTarget,
                                     long lastRefreshMs,
                                     long nowMs,
                                     long throttleMs) {
    if (currentTarget == null || currentTarget.isBlank()) {
        return true;
    }
    if (!currentTarget.equals(previousTarget)) {
        return true;
    }
    return nowMs - lastRefreshMs >= throttleMs;
}
```

Use stable UUID strings as cache keys. Do not store `Player`, `NPCEntity`, or `Ref` across async boundaries.

- [ ] **Step 3: Register service**

If Hytale has a player HUD tick/update hook already used in this repo, register the service there. If not, add a small main-thread system that reads players from the current world/store using safe APIs and does not call `PlayerRef.getComponent(Player)` from tick paths.

Before deciding, inspect current Hytale API and existing Tamework runtime systems. If the only available implementation would require unsafe player access, stop and redesign with an event-driven player item/target update path.

- [ ] **Step 4: Run focused tests and safety grep**

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected:

- Tests PASS.
- Grep has no new unsafe target-HUD runtime path matches.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java
git commit -m "Feat: show command target HUD for supported NPCs"
```

---

### Task 7: Documentation, Changelog, And Full Verification

**Files:**
- Modify: `docs/Command-Items.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/agents/generated-index.md` only if package/docs index changes require regeneration.

- [ ] **Step 1: Update command item docs**

Add a section to `docs/Command-Items.md`:

```markdown
## Command target HUD

When a player holds any configured command item and looks directly at a Tamework-supported NPC within 6 units, Tamework shows a compact right-side HUD. The HUD reuses linked-panel status data for loaded NPCs and can show name, health, happiness, hunger, thirst, level, traits, harvest cooldown, breeding cooldown, favorite food, attachment display labels, and tranquilizer tame requirements when those systems apply.

The HUD is read-only. It does not link, command, mutate, or select NPCs.
```

- [ ] **Step 2: Update changelog**

Add under the current unreleased section:

```markdown
- Added a command-item target HUD that shows compact companion status when looking at supported NPCs, including vitals, progression, cooldowns, favorite food, attachment labels, and tranquilizer tame requirements when available.
- Added taming notifications that tell players whether a newly tamed companion was automatically linked to a command item or whether they need to craft one.
```

- [ ] **Step 3: Run focused suite**

```powershell
.\mvnw.cmd -Dtest=TranquilizerStackDisplayServiceTest,CommandAutoLinkServiceResultTest,CommandItemDisplayResolverTest,CommandLoadedNpcStatusSnapshotServiceTest,CommandTargetHudViewModelTest,CommandTargetHudFoodResolverTest,CommandTargetHudAttachmentResolverTest,CommandTargetHudTameRequirementResolverTest,CommandTargetHudBinderTest,CommandTargetHudServiceTest,NameplateBuilderCompanionSegmentBridgeTest,BuiltInTameworkLanguageKeyCoverageTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: PASS.

- [ ] **Step 4: Run full tests**

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 5: Run final safety grep**

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new unsafe player-access matches introduced by the HUD work.

- [ ] **Step 6: Run agent docs check only if package/docs indexes changed**

If this implementation changes package layout enough to require generated agent index refresh, run:

```powershell
.\scripts\tools\build-agent-index.ps1
.\scripts\tools\check-agent-docs.ps1
```

Expected: PASS.

- [ ] **Step 7: Manual runtime verification**

In a local test world:

1. Hold no command item and look at a supported NPC within 6 units.
   - Expected: target HUD hidden.
2. Hold a command item and look away.
   - Expected: target HUD hidden.
3. Hold a command item and look at a supported NPC within 6 units.
   - Expected: target HUD appears on the right side.
4. Verify displayed rows match the linked panel for name, health, happiness, hunger, thirst, level, traits, harvest cooldown, and breeding cooldown.
5. Verify favorite food icon/name appears for NPCs with feed/loved-item data.
6. Verify attachment labels appear for NPCs with `TameworkAttachmentsComponent` data.
7. Verify tranquilizer requirement row appears only for NPCs whose tame path requires tranquilizer status.
8. Tame with an applicable command item in inventory.
   - Expected: success notification says the animal was tamed and linked.
9. Tame without an applicable command item.
   - Expected: notification says no command item was found and includes crafting bench guidance.

- [ ] **Step 8: Commit docs**

```powershell
git add docs/Command-Items.md CHANGELOG.md
git commit -m "Docs: document command target HUD"
```

---

## Self-Review Checklist

- Spec coverage:
  - Tame notification when no flute/command item exists: Task 2.
  - Tame notification when auto-linked: Task 2.
  - HUD appears only when holding command item and looking at supported NPC within 6 units: Task 6.
  - HUD reuses linked panel status data: Task 3.
  - HUD shows health, happiness, hunger, thirst, level, traits, harvest cooldown, breeding cooldown: Task 3 and Task 5.
  - HUD shows favorite food icon/name: Task 4 and Task 5.
  - HUD shows attachment display info: Task 4 and Task 5.
  - HUD shows tranquilizer tame requirement and stack count: Task 1 and Task 4.
- Placeholder scan:
  - No task is allowed to finish with unspecified testing or validation instructions.
  - Runtime HUD API registration remains a deliberate verification gate because exact Hytale HUD tick APIs must be confirmed before coding.
- Type consistency:
  - `CommandTargetHudViewModel` is the model consumed by UI binder and service.
  - `TranquilizerStackDisplayService` is the shared stack math source for both HUD and NameplateBuilder integration.
  - `LinkedNpcEntry` remains the shared compact status surface.
