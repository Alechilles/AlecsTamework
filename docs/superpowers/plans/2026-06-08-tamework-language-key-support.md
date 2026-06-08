# Tamework Language Key Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make player-facing Tamework UI/config text resolve through `Server/Languages/*/server.lang` keys while preserving raw text as a backward-compatible fallback.

**Architecture:** Keep asset schemas backward compatible by treating existing string fields as "localized string specs": if the string looks like a language key and resolves, show the translation; otherwise show the raw value. Add one small helper around `LocalizedText`, then migrate the highest-impact call sites: talents, traits, command item labels/messages, settings UI dropdown/status strings, and linked-panel progression summaries. Built-in Tamework assets should move their shipped English copy into `server.lang` keys so downstream mods can follow the same pattern.

**Tech Stack:** Java, Hytale custom UI builders, Tamework `Tw*Config` codecs, `LocalizedText`, `Server/Languages/en-US/server.lang`, Maven/JUnit.

---

## Current Findings

- Existing infrastructure: `src/main/java/com/alechilles/alecstamework/localization/LocalizedText.java` resolves keys through Hytale i18n, `TranslationRegistry`, and bundled `en-US/server.lang`.
- Existing localized UI examples: `TameworkCommandSelectionPage`, `CommandSelectionPanelOptions`, linked-panel binders, config editor, name input, and settings announcements already call `LocalizedText`.
- Current gaps found in source/assets:
  - `TwTalentConfig` fields `DisplayName`, `Description`, and `Branch` are rendered raw in `CommandTalentPageService` and `TameworkCompanionTalentsPage`.
  - `TwTraitConfig.TraitDefinition.DisplayName` is rendered raw by linked-panel progression and NameplateBuilder segment integration.
  - `TwCommandItemConfig.CommandEntry.DisplayName`, `ModeMapping.Message`, and `CommandFeedback` message fields are raw in shipped example config and parts of command UI/execution.
  - `TameworkSettingsPage` and `TameworkSettingsPreset` still build several dropdown/status/error labels from raw English strings.
  - `CommandLinkedPanelProgressionPresentationService` includes raw progression summary labels.
  - Built-in configs under `src/main/resources/Server/Tamework` still include English display copy instead of language keys.

## File Map

- Modify: `src/main/java/com/alechilles/alecstamework/localization/LocalizedText.java`
  - Add explicit helpers for resolving a config-provided string as either a language key or raw fallback.
- Create: `src/test/java/com/alechilles/alecstamework/localization/LocalizedTextConfigValueTest.java`
  - Pin key resolution, raw fallback, blank fallback, and formatting.
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java`
  - Resolve talent names, descriptions, branches, prerequisite names, status text, effect labels, and feedback messages for the current player language.
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
  - Resolve page-owned labels such as "Choose a talent first", cost text, requirements, empty page data, and default effect fallback through language keys.
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwTalentConfig.java`
  - Update codec documentation for `DisplayName`, `Description`, and `Branch` to state that language keys are supported.
- Modify: `src/test/java/com/alechilles/alecstamework/items/CommandTalentPageServiceEffectSummaryTest.java`
  - Update expectations after effect labels move through language-aware formatting.
- Modify: `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`
  - Update static assertions that currently expect raw `"Level " + entry.minLevel()`.
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPreset.java`
  - Replace enum raw display names with language keys and language-aware dropdown entry creation.
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java`
  - Resolve preset dropdowns, status lines, parse validation labels, and option dropdowns via `LocalizedText`.
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java`
  - Resolve progression labels and trait display names with language-aware config string handling.
- Modify: `src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java`
  - Resolve trait segment labels from language keys before displaying them.
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
  - Resolve command option display names from config strings as language keys.
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwTraitConfig.java`
  - Update codec documentation for `DisplayName` to state language keys are supported.
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfig.java`
  - Update codec documentation for `DisplayName`, `Message`, `HudMessage`, and `ChatMessage` to state language keys are supported.
- Modify: `src/main/resources/Server/Languages/en-US/server.lang`
  - Add all new built-in keys.
- Modify: `src/main/resources/Server/Languages/de-DE/server.lang`
  - Add matching keys. Use English fallback text if translation is not ready yet, but keep the key inventory complete.
- Modify: `src/main/resources/Server/Tamework/Talents/TwTalentsExample.json`
  - Replace built-in talent `DisplayName`, `Description`, and `Branch` values with language keys.
- Modify: `src/main/resources/Server/Tamework/Traits/TwTraitsDefault.json`
  - Replace built-in trait `DisplayName` values with language keys.
- Modify: `src/main/resources/Server/Tamework/Items/Commands/TwCommandExample.json`
  - Replace built-in command display and feedback text values with language keys.
- Modify: `docs/Command-Items.md`, `docs/Interactions.md`, `README.md`, `CHANGELOG.md`
  - Document language-key support and user-facing change.

---

### Task 1: Add Config String Localization Helper

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/localization/LocalizedText.java`
- Create: `src/test/java/com/alechilles/alecstamework/localization/LocalizedTextConfigValueTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/alechilles/alecstamework/localization/LocalizedTextConfigValueTest.java`:

```java
package com.alechilles.alecstamework.localization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalizedTextConfigValueTest {

    @Test
    void resolvesBundledLanguageKeyFromConfigValue() {
        assertEquals(
                "Companion Talents",
                LocalizedText.resolveConfigValue(null, "tamework.ui.talents.title", "Fallback")
        );
    }

    @Test
    void keepsRawConfigTextWhenNoTranslationExists() {
        assertEquals(
                "Custom Raw Label",
                LocalizedText.resolveConfigValue(null, "Custom Raw Label", "Fallback")
        );
    }

    @Test
    void usesFallbackForBlankConfigValue() {
        assertEquals("Fallback", LocalizedText.resolveConfigValue(null, " ", "Fallback"));
    }

    @Test
    void formatsResolvedConfigValue() {
        assertEquals(
                "Level 7",
                LocalizedText.formatConfigValue(null, "tamework.ui.talents.requirement.level", "Level {0}", 7)
        );
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
./mvnw.cmd -Dtest=LocalizedTextConfigValueTest test
```

Expected: compilation fails because `resolveConfigValue` and `formatConfigValue` do not exist.

- [ ] **Step 3: Implement the helper methods**

Add these public methods to `LocalizedText` after the existing `format(...)` overloads:

```java
@Nonnull
public static String resolveConfigValue(@Nullable String language,
                                        @Nullable String configuredValue,
                                        @Nullable String fallbackValue) {
    String trimmed = configuredValue == null ? "" : configuredValue.trim();
    if (trimmed.isEmpty()) {
        return fallbackValue == null ? "" : fallbackValue;
    }
    String resolved = resolve(language, trimmed);
    if (resolved == null || resolved.isBlank() || resolved.equals(trimmed)) {
        return trimmed;
    }
    return resolved;
}

@Nonnull
public static String formatConfigValue(@Nullable String language,
                                       @Nullable String configuredValue,
                                       @Nullable String fallbackTemplate,
                                       Object... args) {
    return formatTemplate(resolveConfigValue(language, configuredValue, fallbackTemplate), args);
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```powershell
./mvnw.cmd -Dtest=LocalizedTextConfigValueTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/localization/LocalizedText.java src/test/java/com/alechilles/alecstamework/localization/LocalizedTextConfigValueTest.java
git commit -m "Feat: add config value localization helper"
```

---

### Task 2: Localize Talent Config Rendering

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwTalentConfig.java`
- Modify: `src/test/java/com/alechilles/alecstamework/items/CommandTalentPageServiceEffectSummaryTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`

- [ ] **Step 1: Write failing static guards for talent localization**

Add to `CommandTalentPageServiceEffectSummaryTest`:

```java
@Test
void serviceUsesLocalizedTextForTalentPresentation() throws Exception {
    String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java"),
            java.nio.charset.StandardCharsets.UTF_8
    );

    org.junit.jupiter.api.Assertions.assertTrue(content.contains("LocalizedText.resolveConfigValue(language"));
    org.junit.jupiter.api.Assertions.assertTrue(content.contains("LocalizedText.format(language, \"tamework.ui.talents.levelSummary"));
    org.junit.jupiter.api.Assertions.assertTrue(content.contains("LocalizedText.format(language, \"tamework.ui.talents.status"));
    org.junit.jupiter.api.Assertions.assertFalse(content.contains("\"Passive talent\""));
}
```

Add to `TameworkCompanionTalentsPageNavigationTest`:

```java
@Test
void talentPageOwnedTextUsesLanguageKeys() throws IOException {
    String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);

    assertTrue(content.contains("LocalizedText.resolve(playerRef, \"tamework.ui.talents.empty.title\")"));
    assertTrue(content.contains("LocalizedText.format(playerRef, \"tamework.ui.talents.node.cost\""));
    assertTrue(content.contains("LocalizedText.format(playerRef, \"tamework.ui.talents.requirement.level\""));
    assertFalse(content.contains("Choose a talent first."));
    assertFalse(content.contains("entry.pointCost() + \" pt\""));
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```powershell
./mvnw.cmd -Dtest=CommandTalentPageServiceEffectSummaryTest,TameworkCompanionTalentsPageNavigationTest test
```

Expected: FAIL on the new localization assertions.

- [ ] **Step 3: Update `CommandTalentPageService`**

Add import:

```java
import com.alechilles.alecstamework.localization.LocalizedText;
```

At the start of `buildTalentPageData`, add:

```java
String language = player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
```

Replace raw talent summaries/status strings with key-based formatting:

```java
levelSummary = LocalizedText.resolve(language, "tamework.ui.talents.levelSummary.unavailable");
levelSummary = LocalizedText.format(language, "tamework.ui.talents.levelSummary.max", leveling.level());
levelSummary = LocalizedText.format(
        language,
        "tamework.ui.talents.levelSummary.xp",
        leveling.level(),
        Math.max(0, Math.round(leveling.currentXp())),
        Math.max(1, Math.round(leveling.nextLevelDeltaXp()))
);
String pointsSummary = LocalizedText.format(language, "tamework.ui.talents.points.available", availablePoints);
```

When building each `TreeNodeEntry`, resolve config strings:

```java
String talentName = LocalizedText.resolveConfigValue(language, talent.getDisplayName(), talent.getId());
String talentDescription = LocalizedText.resolveConfigValue(
        language,
        talent.getDescription(),
        LocalizedText.resolve(language, "tamework.ui.talents.description.default")
);
String branchName = LocalizedText.resolveConfigValue(
        language,
        talent.getBranch(),
        LocalizedText.resolve(language, "tamework.ui.talents.branch.general")
);
```

Use localized status strings:

```java
if (purchased) {
    state = TameworkCompanionTalentsPage.STATE_PURCHASED;
    status = LocalizedText.resolve(language, "tamework.ui.talents.status.unlocked");
} else if (!levelMet) {
    state = TameworkCompanionTalentsPage.STATE_LOCKED;
    status = LocalizedText.format(language, "tamework.ui.talents.status.requiresLevel", talent.getMinLevel());
} else if (!prerequisitesMet) {
    state = TameworkCompanionTalentsPage.STATE_LOCKED;
    status = LocalizedText.format(language, "tamework.ui.talents.status.requiresTalent", missingPrerequisite);
} else if (availablePoints < talent.getPointCost()) {
    state = TameworkCompanionTalentsPage.STATE_UNAFFORDABLE;
    status = LocalizedText.format(language, "tamework.ui.talents.status.costsPoints", talent.getPointCost());
} else {
    state = TameworkCompanionTalentsPage.STATE_AVAILABLE;
    status = LocalizedText.format(language, "tamework.ui.talents.status.costPoints", talent.getPointCost());
}
```

Pass the resolved values into `TreeNodeEntry`, and localize page status:

```java
entries.isEmpty()
        ? LocalizedText.resolve(language, "tamework.ui.talents.status.noTalentsConfigured")
        : LocalizedText.resolve(language, "tamework.ui.talents.status.chooseTalent")
```

Update `resolvePrerequisiteNames` and `resolveMissingPrerequisiteName` to accept `language` and resolve prerequisite display names with `LocalizedText.resolveConfigValue(language, prerequisite.getDisplayName(), requiredTalentId.trim())`.

Update `summarizeEffects` to accept `language` and use:

```java
return LocalizedText.resolve(language, "tamework.ui.talents.effects.none");
summaries.add(LocalizedText.format(
        language,
        "tamework.ui.talents.effects.line",
        formatEffectKey(language, effect.getEffectKey()),
        formatMultiplierChange(effect.getMultiplier())
));
```

Add a language-aware `formatEffectKey`:

```java
@Nonnull
private String formatEffectKey(@Nullable String language, @Nonnull String effectKey) {
    String fallback = effectKey
            .replace("Multiplier", "")
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .trim();
    return LocalizedText.resolveConfigValue(
            language,
            "tamework.ui.talents.effect." + effectKey,
            fallback.isBlank() ? effectKey : fallback
    );
}
```

- [ ] **Step 4: Update `TameworkCompanionTalentsPage`**

Add import:

```java
import com.alechilles.alecstamework.localization.LocalizedText;
```

Replace page-owned raw strings:

```java
statusMessage = LocalizedText.resolve(playerRef, "tamework.ui.talents.status.chooseFirst");
commandBuilder.set(selector + " #TalentNodeCost.Text",
        LocalizedText.format(playerRef, "tamework.ui.talents.node.cost", entry.pointCost()));
commandBuilder.set("#TalentDetailBranch.Text",
        LocalizedText.format(playerRef, "tamework.ui.talents.detail.branchTier", selectedEntry.branchName(), selectedEntry.tier()));
lines.add(LocalizedText.format(playerRef, "tamework.ui.talents.requirement.level", entry.minLevel()));
```

Update `PageData.empty()` so all text comes from keys with null-language fallback:

```java
return new PageData(
        LocalizedText.resolve((String) null, "tamework.ui.talents.empty.title"),
        LocalizedText.resolve((String) null, "tamework.ui.talents.levelSummary.unavailable"),
        LocalizedText.format((String) null, "tamework.ui.talents.points.available", 0),
        LocalizedText.resolve((String) null, "tamework.ui.talents.empty.status"),
        false,
        List.of()
);
```

Change `TreeNodeEntry` compact constructor fallback:

```java
effectSummary = effectSummary == null || effectSummary.isBlank()
        ? LocalizedText.resolve((String) null, "tamework.ui.talents.effects.none")
        : effectSummary;
```

- [ ] **Step 5: Update talent codec docs**

In `TwTalentConfig`, change documentation strings:

```java
.documentation("Player-facing talent name. May be raw text or a server.lang key.")
.documentation("Player-facing talent description. May be raw text or a server.lang key.")
.documentation("Optional branch label used by the talent UI. May be raw text or a server.lang key.")
```

- [ ] **Step 6: Run focused tests**

Run:

```powershell
./mvnw.cmd -Dtest=CommandTalentPageServiceEffectSummaryTest,TameworkCompanionTalentsPageNavigationTest,LocalizedTextConfigValueTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java src/main/java/com/alechilles/alecstamework/config/assets/TwTalentConfig.java src/test/java/com/alechilles/alecstamework/items/CommandTalentPageServiceEffectSummaryTest.java src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java
git commit -m "Feat: localize companion talent UI text"
```

---

### Task 3: Localize Settings UI Dropdown and Status Text

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPreset.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java`
- Modify: `src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPresetTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPageLocalizationTest.java`

- [ ] **Step 1: Add failing tests**

Add to `TameworkSettingsPresetTest`:

```java
@Test
void presetDisplayNamesUseLanguageKeys() throws Exception {
    String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPreset.java"),
            java.nio.charset.StandardCharsets.UTF_8
    );

    assertTrue(content.contains("displayKey"));
    assertTrue(content.contains("LocalizedText.resolve(language"));
    assertFalse(content.contains("\"Simplified (Minecraft-like)\""));
}
```

Create `src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPageLocalizationTest.java`:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsPageLocalizationTest {

    @Test
    void settingsPageDropdownsAndStatusUseLocalizedText() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolve(playerRef"));
        assertTrue(content.contains("LocalizedText.format(playerRef"));
        assertTrue(content.contains("TameworkSettingsPreset.dropdownEntries(resolveLanguage())"));
        assertFalse(content.contains("LocalizableString.fromString(\"Per World\")"));
        assertFalse(content.contains("\"Failed to apply settings.\""));
        assertFalse(content.contains("\"Applied settings.\""));
    }
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```powershell
./mvnw.cmd -Dtest=TameworkSettingsPresetTest,TameworkSettingsPageLocalizationTest test
```

Expected: FAIL on raw string assertions.

- [ ] **Step 3: Update `TameworkSettingsPreset`**

Add import:

```java
import com.alechilles.alecstamework.localization.LocalizedText;
```

Change enum fields:

```java
CUSTOM("Custom", "tamework.ui.settings.preset.custom"),
SIMPLIFIED("Simplified", "tamework.ui.settings.preset.simplified"),
EASIER("Easier", "tamework.ui.settings.preset.easier"),
FULL_EXPERIENCE("FullExperience", "tamework.ui.settings.preset.fullExperience");

private final String displayKey;
```

Add:

```java
@Nonnull
public String displayName(@Nullable String language) {
    return LocalizedText.resolve(language, displayKey);
}

@Nonnull
public String displayKey() {
    return displayKey;
}
```

Replace `dropdownEntries()` with:

```java
@Nonnull
public static List<DropdownEntryInfo> dropdownEntries(@Nullable String language) {
    return List.of(
            new DropdownEntryInfo(LocalizableString.fromString(SIMPLIFIED.displayName(language)), SIMPLIFIED.value),
            new DropdownEntryInfo(LocalizableString.fromString(EASIER.displayName(language)), EASIER.value),
            new DropdownEntryInfo(LocalizableString.fromString(FULL_EXPERIENCE.displayName(language)), FULL_EXPERIENCE.value),
            new DropdownEntryInfo(LocalizableString.fromString(CUSTOM.displayName(language)), CUSTOM.value)
    );
}
```

- [ ] **Step 4: Update `TameworkSettingsPage`**

Add import:

```java
import com.alechilles.alecstamework.localization.LocalizedText;
```

Add helper:

```java
@Nullable
private String resolveLanguage() {
    return playerRef != null ? playerRef.getLanguage() : null;
}
```

Replace dropdown calls:

```java
commandBuilder.set("#TwSettingsPresetDropdown.Entries", TameworkSettingsPreset.dropdownEntries(resolveLanguage()));
```

Replace preset status lines:

```java
statusLine = parseResult.success()
        ? LocalizedText.format(playerRef, "tamework.ui.settings.status.loadedPreset", preset.displayName(resolveLanguage()))
        : LocalizedText.format(playerRef, "tamework.ui.settings.status.loadedPresetDiscardedInvalid", preset.displayName(resolveLanguage()));
```

Replace apply outcomes:

```java
return ApplyOutcome.failure(LocalizedText.resolve(playerRef, "tamework.ui.settings.warning.saveFailed"));
return ApplyOutcome.partial(
        LocalizedText.resolve(playerRef, "tamework.ui.settings.status.appliedUniverse"),
        telemetryWarning
);
return ApplyOutcome.success(LocalizedText.resolve(playerRef, "tamework.ui.settings.status.applied"));
```

Replace dropdown option labels:

```java
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.populationScope.perWorld")), TwGlobalConfig.PerPlayerLimitScope.PER_WORLD.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.populationScope.global")), TwGlobalConfig.PerPlayerLimitScope.GLOBAL.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.needsTickPolicy.ownerGraceThenDecay")), TwGlobalConfig.NeedsTickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.needsTickPolicy.anyLoadedPlayer")), TwGlobalConfig.NeedsTickPolicyMode.ANY_LOADED_PLAYER.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.needsDamageModel.minOnlyPercent")), TwGlobalConfig.NeedsDamageModel.MIN_ONLY_PERCENT.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.needsDamageModel.minOnlyFlat")), TwGlobalConfig.NeedsDamageModel.MIN_ONLY_FLAT.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.dualNeedRule.useHigherOnly")), TwGlobalConfig.NeedsDamageDualNeedRule.USE_HIGHER_ONLY.configValue())
new DropdownEntryInfo(LocalizableString.fromString(LocalizedText.resolve(resolveLanguage(), "tamework.ui.settings.dualNeedRule.sumBoth")), TwGlobalConfig.NeedsDamageDualNeedRule.SUM_BOTH.configValue())
```

- [ ] **Step 5: Run focused tests**

```powershell
./mvnw.cmd -Dtest=TameworkSettingsPresetTest,TameworkSettingsPageLocalizationTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPreset.java src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPresetTest.java src/test/java/com/alechilles/alecstamework/ui/TameworkSettingsPageLocalizationTest.java
git commit -m "Feat: localize settings UI text"
```

---

### Task 4: Localize Trait and Command Config Display Values

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwTraitConfig.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfig.java`
- Create: `src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageLocalizationTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionLocalizationTest.java`

- [ ] **Step 1: Add failing tests**

Create `TameworkCommandSelectionPageLocalizationTest`:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCommandSelectionPageLocalizationTest {

    @Test
    void commandEntryLabelsResolveConfigLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("resolveLabel(CommandEntry entry, @Nullable String language)"));
        assertTrue(content.contains("LocalizedText.resolveConfigValue(language, entry.getDisplayName(), entry.getId())"));
    }
}
```

Create `CommandLinkedPanelProgressionLocalizationTest`:

```java
package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelProgressionLocalizationTest {

    @Test
    void progressionPresentationUsesLocalizedLabels() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(language"));
        assertTrue(content.contains("tamework.ui.linkedPanel.progression.modifiersBreakdown"));
        assertFalse(content.contains("\"Modifiers: Total - [Level - Talents - Traits]\""));
    }
}
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
./mvnw.cmd -Dtest=TameworkCommandSelectionPageLocalizationTest,CommandLinkedPanelProgressionLocalizationTest test
```

Expected: FAIL.

- [ ] **Step 3: Update command labels in `TameworkCommandSelectionPage`**

Change `buildOptions` to pass the current language:

```java
out.add(new CommandOption(entry.getId(), resolveLabel(entry, resolveLanguage())));
```

Replace `resolveLabel` with:

```java
private static String resolveLabel(CommandEntry entry, @Nullable String language) {
    if (entry == null) {
        return "";
    }
    return LocalizedText.resolveConfigValue(language, entry.getDisplayName(), entry.getId());
}
```

- [ ] **Step 4: Update progression and trait presentation**

In `CommandLinkedPanelProgressionPresentationService`, resolve trait labels with:

```java
String displayName = LocalizedText.resolveConfigValue(language, definition.getDisplayName(), definition.getId());
```

Replace raw breakdown text with:

```java
lines.add(LocalizedText.resolve(language, "tamework.ui.linkedPanel.progression.modifiersBreakdown"));
```

In `NameplateBuilderCompanionSegmentBridge`, resolve trait display name before returning it:

```java
String displayName = LocalizedText.resolveConfigValue(null, definition.getDisplayName(), definition.getId());
```

- [ ] **Step 5: Update codec documentation**

In `TwTraitConfig`, change `DisplayName` docs to:

```java
.documentation("Display name shown to players. May be raw text or a server.lang key.")
```

In `TwCommandItemConfig`, update docs for command entry and feedback display text fields to include:

```java
"May be raw text or a server.lang key."
```

- [ ] **Step 6: Run focused tests**

```powershell
./mvnw.cmd -Dtest=TameworkCommandSelectionPageLocalizationTest,CommandLinkedPanelProgressionLocalizationTest,CommandLinkedPanelProgressionPresentationServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java src/main/java/com/alechilles/alecstamework/integration/nameplatebuilder/NameplateBuilderCompanionSegmentBridge.java src/main/java/com/alechilles/alecstamework/config/assets/TwTraitConfig.java src/main/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfig.java src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageLocalizationTest.java src/test/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionLocalizationTest.java
git commit -m "Feat: localize trait and command config labels"
```

---

### Task 5: Move Built-In Asset Copy Into `server.lang`

**Files:**
- Modify: `src/main/resources/Server/Languages/en-US/server.lang`
- Modify: `src/main/resources/Server/Languages/de-DE/server.lang`
- Modify: `src/main/resources/Server/Tamework/Talents/TwTalentsExample.json`
- Modify: `src/main/resources/Server/Tamework/Traits/TwTraitsDefault.json`
- Modify: `src/main/resources/Server/Tamework/Items/Commands/TwCommandExample.json`
- Create: `src/test/java/com/alechilles/alecstamework/localization/BuiltInTameworkLanguageKeyCoverageTest.java`

- [ ] **Step 1: Add failing coverage test**

Create `BuiltInTameworkLanguageKeyCoverageTest`:

```java
package com.alechilles.alecstamework.localization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BuiltInTameworkLanguageKeyCoverageTest {

    private static final Pattern LOCALIZED_FIELD = Pattern.compile(
            "\"(?:DisplayName|Description|Branch|Message|HudMessage|ChatMessage)\"\\s*:\\s*\"([^\"]+)\""
    );

    @Test
    void builtInTameworkDisplayFieldsUseLanguageKeysPresentInServerLang() throws Exception {
        String lang = Files.readString(
                Path.of("src/main/resources/Server/Languages/en-US/server.lang"),
                StandardCharsets.UTF_8
        );
        HashSet<String> missing = new HashSet<>();
        for (Path path : Files.walk(Path.of("src/main/resources/Server/Tamework")).filter(Files::isRegularFile).toList()) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher matcher = LOCALIZED_FIELD.matcher(content);
            while (matcher.find()) {
                String value = matcher.group(1);
                if (!value.startsWith("tamework.")) {
                    missing.add(path + " uses raw text: " + value);
                } else if (!lang.contains(value + "=")) {
                    missing.add(path + " missing server.lang key: " + value);
                }
            }
        }
        assertFalse(missing.isEmpty(), "Remove this assertion after confirming the test fails before asset migration.");
    }
}
```

Run once and confirm it fails for the existing raw values. Then invert the final assertion to:

```java
assertTrue(missing.isEmpty(), String.join("\n", missing));
```

and add `import static org.junit.jupiter.api.Assertions.assertTrue;`.

- [ ] **Step 2: Add English keys**

Append to `en-US/server.lang`:

```properties
# ===== Tamework Config Text: Talents =====
tamework.talents.branch.survival=Survival
tamework.talents.branch.utility=Utility
tamework.talents.branch.combat=Combat
tamework.talents.healthy.name=Healthy
tamework.talents.healthy.description=Raises maximum health.
tamework.talents.quick.name=Quick
tamework.talents.quick.description=Improves movement speed.
tamework.talents.strong.name=Strong
tamework.talents.strong.description=Increases outgoing damage.
tamework.talents.tough.name=Tough
tamework.talents.tough.description=Reduces incoming damage.
tamework.talents.bountiful.name=Bountiful
tamework.talents.bountiful.description=Improves harvest bonus chances.

# ===== Tamework Config Text: Traits =====
tamework.traits.disposition.name=Disposition
tamework.traits.fertility.name=Fertility
tamework.traits.health.name=Health
tamework.traits.size.name=Size
tamework.traits.swiftness.name=Swiftness
tamework.traits.toughness.name=Toughness
tamework.traits.strength.name=Strength
tamework.traits.bounty.name=Bounty

# ===== Tamework Config Text: Command Example =====
tamework.commands.follow.name=Follow
tamework.commands.follow.hud=Follow: {count}
tamework.commands.hold.name=Hold
tamework.commands.hold.hud=Hold: {count}
tamework.commands.recall.name=Recall
tamework.commands.recall.hud=Recall: {count}
tamework.commands.moveToPing.name=Move To Ping
tamework.commands.moveToPing.hud=Move To Ping: {count}
tamework.commands.setHome.name=Set Home
tamework.commands.setHome.chat=Home set for linked NPC(s).
tamework.commands.setHome.hud=Home Set
tamework.commands.returnHome.name=Return Home
tamework.commands.returnHome.hud=Return Home: {count}
tamework.commands.attackTarget.name=Attack Target
tamework.commands.attackTarget.hud=Attack Target: {count}
tamework.commands.idle.name=Idle
tamework.commands.idle.hud=Idle: {count}
```

Also add keys referenced by Tasks 2-4, including:

```properties
tamework.ui.talents.empty.title=Companion Talents
tamework.ui.talents.empty.status=No companion data is available.
tamework.ui.talents.status.chooseFirst=Choose a talent first.
tamework.ui.talents.status.noTree=No talent tree is configured for this companion.
tamework.ui.talents.status.noTalentsConfigured=No talents are configured for this companion.
tamework.ui.talents.status.chooseTalent=Choose a talent to inspect or unlock.
tamework.ui.talents.status.unlocked=Unlocked
tamework.ui.talents.status.requiresLevel=Requires Level {0}
tamework.ui.talents.status.requiresTalent=Requires {0}
tamework.ui.talents.status.costsPoints=Costs {0} points
tamework.ui.talents.status.costPoints=Cost {0} points
tamework.ui.talents.levelSummary.unavailable=Level data unavailable
tamework.ui.talents.levelSummary.max=Level {0} (MAX)
tamework.ui.talents.levelSummary.xp=Level {0} - XP {1}/{2}
tamework.ui.talents.points.available=Talent Points: {0} available
tamework.ui.talents.description.default=Passive talent
tamework.ui.talents.branch.general=General
tamework.ui.talents.node.cost={0} pt
tamework.ui.talents.detail.branchTier={0} - Tier {1}
tamework.ui.talents.requirement.level=Level {0}
tamework.ui.talents.effects.none=No passive effects
tamework.ui.talents.effects.line={0} {1}
tamework.ui.talents.effect.MaxHealthMultiplier=Max Health
tamework.ui.talents.effect.MoveSpeedMultiplier=Move Speed
tamework.ui.talents.effect.OutgoingDamageMultiplier=Outgoing Damage
tamework.ui.talents.effect.IncomingDamageMultiplier=Incoming Damage
tamework.ui.talents.effect.HarvestBonusMultiplier=Harvest Bonus
tamework.ui.talents.effect.ReviveCooldownMultiplier=Revive Cooldown
tamework.ui.settings.preset.custom=Custom
tamework.ui.settings.preset.simplified=Simplified (Minecraft-like)
tamework.ui.settings.preset.easier=Easier
tamework.ui.settings.preset.fullExperience=Full Experience
tamework.ui.settings.status.loadedPreset=Loaded {0} preset. Review and click Apply to save.
tamework.ui.settings.status.loadedPresetDiscardedInvalid=Loaded {0} preset. Invalid unsaved numeric inputs were discarded.
tamework.ui.settings.status.applied=Applied settings.
tamework.ui.settings.status.appliedUniverse=Applied universe settings.
tamework.ui.settings.warning.saveFailed=Failed to save universe settings.
tamework.ui.settings.populationScope.perWorld=Per World
tamework.ui.settings.populationScope.global=Global
tamework.ui.settings.needsTickPolicy.ownerGraceThenDecay=Owner Online Grace Then Decay
tamework.ui.settings.needsTickPolicy.anyLoadedPlayer=Any Loaded Player
tamework.ui.settings.needsDamageModel.minOnlyPercent=Min Only Percent
tamework.ui.settings.needsDamageModel.minOnlyFlat=Min Only Flat
tamework.ui.settings.dualNeedRule.useHigherOnly=Use Higher Only
tamework.ui.settings.dualNeedRule.sumBoth=Sum Both
tamework.ui.linkedPanel.progression.modifiersBreakdown=Modifiers: Total - [Level - Talents - Traits]
```

- [ ] **Step 3: Add matching German keys**

Add the same keys to `de-DE/server.lang`. Translate where obvious; otherwise use the English text as a temporary fallback so key coverage remains complete.

- [ ] **Step 4: Replace built-in JSON values with keys**

In `TwTalentsExample.json`, replace:

```json
"DisplayName": "Healthy"
```

with:

```json
"DisplayName": "tamework.talents.healthy.name"
```

Apply the same pattern for all listed talent names, descriptions, and branches.

In `TwTraitsDefault.json`, replace each `DisplayName` with its `tamework.traits.<id>.name` key.

In `TwCommandExample.json`, replace each command `DisplayName`, `HudMessage`, and `ChatMessage` with its `tamework.commands.<id>.*` key.

- [ ] **Step 5: Run coverage test**

```powershell
./mvnw.cmd -Dtest=BuiltInTameworkLanguageKeyCoverageTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/Server/Languages/en-US/server.lang src/main/resources/Server/Languages/de-DE/server.lang src/main/resources/Server/Tamework/Talents/TwTalentsExample.json src/main/resources/Server/Tamework/Traits/TwTraitsDefault.json src/main/resources/Server/Tamework/Items/Commands/TwCommandExample.json src/test/java/com/alechilles/alecstamework/localization/BuiltInTameworkLanguageKeyCoverageTest.java
git commit -m "Feat: move built-in Tamework display copy to lang keys"
```

---

### Task 6: Document Language-Key Support and Run Verification

**Files:**
- Modify: `docs/Command-Items.md`
- Modify: `docs/Interactions.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update docs**

In `docs/Command-Items.md`, add near the `DisplayName` field:

```markdown
`DisplayName`, `HudMessage`, and `ChatMessage` may be raw text or `server.lang` keys. Prefer keys such as `tamework.commands.follow.name` for built-in packs and downstream mods that plan to support multiple languages.
```

In `docs/Interactions.md`, add near presentation fields:

```markdown
Presentation strings such as interaction messages may be raw text or `server.lang` keys. Use language keys for player-facing copy whenever the text should be translatable.
```

In `README.md`, add a concise modder-facing note in the config/support section:

```markdown
Tamework player-facing config strings support `server.lang` keys. Built-in talents, traits, command labels, and major UI labels use language keys so translation packs can override copy without changing behavior assets.
```

In `CHANGELOG.md`, under the current unreleased version, add:

```markdown
- Added language-key support for Tamework talent, trait, command, settings, and progression UI text so translations can be provided through `Server/Languages/*/server.lang`.
```

- [ ] **Step 2: Run focused localization test suite**

```powershell
./mvnw.cmd -Dtest=LocalizedTextConfigValueTest,BuiltInTameworkLanguageKeyCoverageTest,CommandTalentPageServiceEffectSummaryTest,TameworkCompanionTalentsPageNavigationTest,TameworkSettingsPresetTest,TameworkSettingsPageLocalizationTest,TameworkCommandSelectionPageLocalizationTest,CommandLinkedPanelProgressionLocalizationTest,CommandLinkedPanelProgressionPresentationServiceTest test
```

Expected: PASS.

- [ ] **Step 3: Run full tests**

```powershell
./mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 4: Run thread-safety grep**

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no new matches in tick/runtime paths from this work.

- [ ] **Step 5: Run raw-copy audit**

```powershell
rg -n '"(DisplayName|Description|Branch|Message|HudMessage|ChatMessage|Label|Prompt|Title)"\s*:\s*"[A-Z][^"]+"' src/main/resources/Server/Tamework src/main/resources/Server/Item src/main/resources/Server/NPC
```

Expected: any remaining matches are either non-player-facing schema/docs metadata, debug-only copy, or deliberately raw user/modder examples documented as raw fallback compatible.

- [ ] **Step 6: Commit**

```powershell
git add docs/Command-Items.md docs/Interactions.md README.md CHANGELOG.md
git commit -m "Docs: document Tamework language key support"
```

---

## Execution Notes

- Keep raw text fallback behavior. Do not break downstream configs that already use `"DisplayName": "Follow"` or similar.
- Do not rename existing config fields in this pass. Adding `DisplayNameKey` could be a later schema cleanup, but the low-risk migration is to support language keys inside the existing fields.
- Player-created/custom NPC names are not language keys. Do not resolve custom names through `LocalizedText`.
- IDs, asset paths, command IDs, state names, alarm names, and internal telemetry/debug fields are not player-facing localization targets.
- If this work reveals a base-Hytale API limitation around `LocalizableString`, use `hytale-workshop-mcp` before changing the approach.
