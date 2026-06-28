package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Builds the loaded-NPC status surface shared by linked panels, nearby panels, and target HUDs.
 */
final class CommandLoadedNpcStatusSnapshotService {
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkedPanelProgressionPresentationService progressionPresentationService;
    private final CommandLinkedPanelCooldownSnapshotService cooldownSnapshotService;

    CommandLoadedNpcStatusSnapshotService(CommandNpcNameResolver npcNameResolver,
                                          CommandLinkPolicyService linkPolicyService,
                                          CommandLinkedPanelProgressionPresentationService progressionPresentationService,
                                          CommandLinkedPanelCooldownSnapshotService cooldownSnapshotService) {
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.progressionPresentationService = progressionPresentationService != null
                ? progressionPresentationService
                : new CommandLinkedPanelProgressionPresentationService();
        this.cooldownSnapshotService = cooldownSnapshotService != null
                ? cooldownSnapshotService
                : new CommandLinkedPanelCooldownSnapshotService();
    }

    @Nullable
    LinkedNpcEntry buildLoadedEntry(@Nullable Player player,
                                    @Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable NpcStatusContext context) {
        return buildLoadedEntry(player, npcRef, store, context, SnapshotOptions.linkedPanel());
    }

    @Nullable
    LinkedNpcEntry buildLoadedEntry(@Nullable Player player,
                                    @Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable NpcStatusContext context,
                                    @Nullable SnapshotOptions options) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        NPCEntity npc = safeGetComponent(store, npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        SnapshotOptions resolvedOptions = options != null ? options : SnapshotOptions.linkedPanel();
        String language = player != null && player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
        NpcStatusContext resolvedContext = context != null ? context : NpcStatusContext.empty(npc.getUuid());
        UUID npcUuid = resolvedContext.npcUuid() != null ? resolvedContext.npcUuid() : npc.getUuid();
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = resolvedContext.fallbackDisplayName();
        }
        String resolvedRoleId = resolveSpeciesRoleId(npc, resolvedContext.cachedRoleId());
        String speciesId = resolvedRoleId;
        String speciesLabel = resolvedRoleId;
        String gender = CompanionGenderService.resolveGender(npcRef, store, resolvedRoleId, null);
        boolean hasHome = resolvedContext.hasHome();
        TameworkCommandLinksComponent links = safeGetComponent(store, npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links != null && links.hasHome()) {
            hasHome = true;
        }

        int health = 0;
        int maxHealth = 0;
        HealthSnapshot healthSnapshot = readNpcHealthSnapshot(npcRef, store);
        if (healthSnapshot != null) {
            health = healthSnapshot.current;
            maxHealth = healthSnapshot.max;
        }

        int happiness = 0;
        int maxHappiness = 0;
        int targetHappinessPercent = 0;
        String happinessModifierBreakdown = null;
        HappinessSnapshot happinessSnapshot = readNpcHappinessSnapshot(
                npcRef,
                store,
                language,
                resolvedOptions.includeHappinessBreakdown()
        );
        if (happinessSnapshot != null) {
            happiness = happinessSnapshot.current;
            maxHappiness = happinessSnapshot.max;
            targetHappinessPercent = happinessSnapshot.targetPercent;
            happinessModifierBreakdown = happinessSnapshot.modifierBreakdown;
        }

        int hunger = 0;
        int maxHunger = 0;
        int thirst = 0;
        int maxThirst = 0;
        NeedsSnapshot needsSnapshot = readNpcNeedsSnapshot(npcRef, store, TameworkNeedsComponent.getComponentType());
        if (needsSnapshot != null) {
            hunger = needsSnapshot.hungerCurrent;
            maxHunger = needsSnapshot.hungerMax;
            thirst = needsSnapshot.thirstCurrent;
            maxThirst = needsSnapshot.thirstMax;
        }

        boolean breedingEnabled = resolvedContext.breedingEnabled();
        boolean breedingAvailable = false;
        boolean breedingCooldownActive = false;
        long breedingCooldownRemainingMs = 0L;
        double breedingCooldownRatio = 0.0;
        boolean breedingCooldownKnown = false;
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot breedingSnapshot =
                cooldownSnapshotService.readBreedingCooldownSnapshot(npcRef, store, speciesId);
        if (breedingSnapshot != null) {
            breedingAvailable = breedingSnapshot.available;
            if (breedingSnapshot.known) {
                breedingEnabled = breedingSnapshot.enabled;
            }
            breedingCooldownKnown = breedingSnapshot.known;
            breedingCooldownActive = breedingSnapshot.active;
            breedingCooldownRemainingMs = breedingSnapshot.remainingMs;
            breedingCooldownRatio = breedingSnapshot.ratio;
        }

        boolean harvestCooldownActive = false;
        long harvestCooldownRemainingMs = 0L;
        double harvestCooldownRatio = 0.0;
        boolean harvestCooldownKnown = false;
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot harvestSnapshot =
                cooldownSnapshotService.readHarvestCooldownSnapshot(npcRef, store);
        if (harvestSnapshot != null) {
            harvestCooldownKnown = harvestSnapshot.known;
            harvestCooldownActive = harvestSnapshot.active;
            harvestCooldownRemainingMs = harvestSnapshot.remainingMs;
            harvestCooldownRatio = harvestSnapshot.ratio;
        }

        LinkedNpcEntry.FutureStat futureStatA = null;
        LinkedNpcEntry.FutureStat futureStatB = null;
        boolean talentsActionVisible = false;
        boolean talentsActionEnabled = false;
        CompanionLevelingService.LevelingSnapshot levelingSnapshot =
                CompanionLevelingService.resolveSnapshot(npcRef, store, resolvedRoleId);
        if (levelingSnapshot != null) {
            String modifierTooltip = resolvedOptions.includeProgressionModifierTooltip()
                    ? progressionPresentationService.buildModifierTooltip(npcRef, store, npc, language)
                    : null;
            futureStatA = progressionPresentationService.buildLevelFutureStat(
                    levelingSnapshot,
                    language,
                    modifierTooltip
            );
        }
        TwTalentConfig talentConfig = CompanionTalentService.resolveTalentConfig(npcRef, store);
        if (talentConfig != null && talentConfig.isEnabled()) {
            int availableTalentPoints = CompanionTalentService.resolveAvailablePoints(npcRef, store);
            int totalEarnedTalentPoints = levelingSnapshot != null
                    ? CompanionLevelingService.resolveEarnedTalentPoints(
                    levelingSnapshot.level(),
                    levelingSnapshot.configId()
            )
                    : 0;
            futureStatB = progressionPresentationService.buildTalentPointFutureStat(
                    availableTalentPoints,
                    totalEarnedTalentPoints,
                    language
            );
            talentsActionVisible = true;
            talentsActionEnabled = true;
        }
        LinkedNpcTraitIndicator[] traitIndicators =
                progressionPresentationService.readLoadedTraitIndicators(npcRef, store, language);

        return new LinkedNpcEntry(
                npcUuid,
                displayName,
                gender,
                health,
                maxHealth,
                happiness,
                maxHappiness,
                targetHappinessPercent,
                happinessModifierBreakdown,
                hunger,
                maxHunger,
                thirst,
                maxThirst,
                true,
                hasHome,
                false,
                false,
                false,
                false,
                0L,
                null,
                futureStatA,
                futureStatB,
                traitIndicators,
                false,
                false,
                talentsActionVisible,
                talentsActionEnabled,
                resolvedContext.linked(),
                resolvedContext.active(),
                speciesId,
                speciesLabel,
                resolvedContext.groupId(),
                resolvedContext.groupName(),
                resolvedContext.groupColorHex(),
                breedingEnabled,
                breedingAvailable,
                breedingCooldownActive,
                breedingCooldownRemainingMs,
                breedingCooldownRatio,
                breedingCooldownKnown,
                harvestCooldownActive,
                harvestCooldownRemainingMs,
                harvestCooldownRatio,
                harvestCooldownKnown,
                false,
                0L
        );
    }

    LinkedNpcTraitIndicator[] readLoadedTraitIndicators(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        @Nullable String language) {
        return progressionPresentationService.readLoadedTraitIndicators(npcRef, store, language);
    }

    @Nullable
    String buildHappinessModifierBreakdown(CompanionHappinessService.HappinessSnapshot snapshot) {
        return buildHappinessModifierBreakdown(snapshot, null);
    }

    @Nullable
    String buildHappinessModifierBreakdown(CompanionHappinessService.HappinessSnapshot snapshot,
                                           @Nullable String language) {
        if (snapshot == null) {
            return null;
        }
        ArrayList<String> modifierLines = new ArrayList<>();
        for (CompanionHappinessModifierService.ModifierEntry modifier : snapshot.modifiers()) {
            if (modifier == null || !Double.isFinite(modifier.value())) {
                continue;
            }
            if (Math.abs(modifier.value()) <= 0.000001) {
                continue;
            }
            String label = resolveModifierLabel(modifier, language);
            modifierLines.add(label + ": " + formatSigned(modifier.value()));
        }

        ArrayList<String> impulseLines = new ArrayList<>();
        for (CompanionHappinessService.ActiveImpulseSnapshot activeImpulse : snapshot.activeImpulses()) {
            if (activeImpulse == null || !Double.isFinite(activeImpulse.value())) {
                continue;
            }
            if (Math.abs(activeImpulse.value()) <= 0.000001) {
                continue;
            }
            String label = resolveImpulseLabel(activeImpulse, language);
            impulseLines.add(label + ": " + formatSigned(activeImpulse.value()));
        }

        if (modifierLines.isEmpty() && impulseLines.isEmpty()) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>(modifierLines.size() + impulseLines.size());
        lines.addAll(modifierLines);
        lines.addAll(impulseLines);
        return String.join("\n", lines);
    }

    static int computePercentForTests(double value, double min, double max) {
        return computePercent(value, min, max);
    }

    static String formatSignedForTests(double value) {
        return formatSigned(value);
    }

    @Nullable
    private HealthSnapshot readNpcHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = safeGetComponent(store, npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return null;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    @Nullable
    private HappinessSnapshot readNpcHappinessSnapshot(Ref<EntityStore> npcRef,
                                                       Store<EntityStore> store,
                                                       @Nullable String language,
                                                       boolean includeModifierBreakdown) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        CompanionHappinessService.HappinessSnapshot snapshot = CompanionHappinessService.resolveSnapshot(npcRef, store);
        if (snapshot == null) {
            return null;
        }
        double value = clamp(snapshot.value(), snapshot.min(), snapshot.max());
        double max = Math.max(1.0, snapshot.max());
        int roundedMax = Math.max(1, Math.round((float) max));
        int roundedValue = Math.max(0, Math.min(roundedMax, Math.round((float) value)));
        int targetPercent = computePercent(snapshot.target(), snapshot.min(), snapshot.max());
        String modifierBreakdown = includeModifierBreakdown ? buildHappinessModifierBreakdown(snapshot, language) : null;
        return new HappinessSnapshot(roundedValue, roundedMax, targetPercent, modifierBreakdown);
    }

    private String resolveModifierLabel(CompanionHappinessModifierService.ModifierEntry modifier,
                                        @Nullable String language) {
        if (modifier == null) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        String modifierId = normalize(modifier.id());
        if ("owner_nearby".equals(modifierId)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.ownerNearby");
        }
        String label = modifier.label();
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        String stripped = stripModifierPrefix(label);
        return LocalizedText.resolveConfigValue(language, stripped, stripped);
    }

    private String resolveImpulseLabel(CompanionHappinessService.ActiveImpulseSnapshot activeImpulse,
                                       @Nullable String language) {
        String key = normalize(activeImpulse.key());
        if ("feed:hand".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.handFed");
        }
        if (key != null && key.startsWith("feed:")) {
            String itemName = resolveItemDisplayName(language, activeImpulse.itemId());
            return LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.happiness.impulse.ate",
                    itemName
            );
        }
        if ("pet".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.petted");
        }
        if ("damage".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.attacked");
        }
        String label = activeImpulse.label();
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.generic");
        }
        String resolvedLabel = LocalizedText.resolveConfigValue(language, label, label);
        String itemId = activeImpulse.itemId();
        if (itemId != null && !itemId.isBlank()) {
            return resolvedLabel + " " + resolveItemDisplayName(language, itemId);
        }
        return resolvedLabel;
    }

    private String resolveItemDisplayName(@Nullable String language, @Nullable String itemId) {
        String canonicalItemId = sanitizeItemId(itemId);
        if (canonicalItemId == null || canonicalItemId.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.food.unknown");
        }
        String itemNameKey = "items." + canonicalItemId + ".name";
        String localizedFromKey = LocalizedText.resolve(language, itemNameKey);
        if (localizedFromKey != null && !localizedFromKey.isBlank() && !itemNameKey.equals(localizedFromKey)) {
            return localizedFromKey;
        }
        try {
            Item itemAsset = Item.getAssetMap().getAsset(canonicalItemId);
            if (itemAsset != null && itemAsset.getTranslationKey() != null && !itemAsset.getTranslationKey().isBlank()) {
                String translated = LocalizedText.resolve(language, itemAsset.getTranslationKey());
                if (translated != null && !translated.isBlank() && !translated.equals(itemAsset.getTranslationKey())) {
                    return translated;
                }
            }
        } catch (Throwable ignored) {
            // Some tests and degraded startup paths do not initialize Hytale item assets/logging.
        }
        return humanizeItemId(canonicalItemId);
    }

    private String humanizeItemId(String canonicalItemId) {
        if (canonicalItemId == null || canonicalItemId.isBlank()) {
            return LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.food.unknown");
        }
        String normalized = canonicalItemId;
        if (normalized.startsWith("Tw_")) {
            normalized = normalized.substring(3);
        }
        String[] rawParts = normalized.split("_");
        ArrayList<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.isBlank()) {
                continue;
            }
            parts.add(rawPart);
        }
        if (parts.isEmpty()) {
            return canonicalItemId;
        }
        if (parts.size() > 1 && parts.get(0).equalsIgnoreCase("Feed")) {
            String feed = parts.remove(0);
            parts.add(feed);
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private String stripModifierPrefix(String label) {
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        if (label.regionMatches(true, 0, "Hunger:", 0, "Hunger:".length())) {
            return label.substring("Hunger:".length()).trim();
        }
        if (label.regionMatches(true, 0, "Thirst:", 0, "Thirst:".length())) {
            return label.substring("Thirst:".length()).trim();
        }
        if (label.regionMatches(true, 0, "Population:", 0, "Population:".length())) {
            return label.substring("Population:".length()).trim();
        }
        return label.trim();
    }

    @Nullable
    private String sanitizeItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String sanitized = itemId.trim();
        if (sanitized.startsWith("*")) {
            sanitized = sanitized.substring(1);
        }
        int stateIndex = sanitized.indexOf("_State_");
        if (stateIndex > 0) {
            sanitized = sanitized.substring(0, stateIndex);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    @Nullable
    private NeedsSnapshot readNpcNeedsSnapshot(Ref<EntityStore> npcRef,
                                               Store<EntityStore> store,
                                               ComponentType<EntityStore, TameworkNeedsComponent> needsType) {
        if (npcRef == null || !npcRef.isValid() || store == null || needsType == null) {
            return null;
        }
        TameworkNeedsComponent needs = safeGetComponent(store, npcRef, needsType);
        if (needs == null) {
            return null;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (!NeedsConfigResolver.isRuntimeEnabled(config)) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hungerMin = values.getHungerMin();
        double hungerMax = values.getHungerMax();
        double thirstMin = values.getThirstMin();
        double thirstMax = values.getThirstMax();

        int roundedHungerMax = Math.max(1, Math.round((float) hungerMax));
        int roundedThirstMax = Math.max(1, Math.round((float) thirstMax));
        int roundedHunger = Math.max(
                0,
                Math.min(roundedHungerMax, Math.round((float) clamp(needs.getHunger(), hungerMin, hungerMax)))
        );
        int roundedThirst = Math.max(
                0,
                Math.min(roundedThirstMax, Math.round((float) clamp(needs.getThirst(), thirstMin, thirstMax)))
        );
        return new NeedsSnapshot(roundedHunger, roundedHungerMax, roundedThirst, roundedThirstMax);
    }

    private String resolveSpeciesRoleId(NPCEntity npc, @Nullable String fallbackRoleId) {
        String roleId = firstNonBlank(
                linkPolicyService.resolveRoleId(npc),
                npcNameResolver.resolveNpcRoleId(npc),
                fallbackRoleId
        );
        return normalize(roleId);
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        if (third != null && !third.isBlank()) {
            return third;
        }
        return null;
    }

    private <T extends Component<EntityStore>> T safeGetComponent(Store<EntityStore> store,
                                                                  Ref<EntityStore> npcRef,
                                                                  ComponentType<EntityStore, T> componentType) {
        if (store == null || npcRef == null || !npcRef.isValid() || componentType == null) {
            return null;
        }
        try {
            return store.getComponent(npcRef, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }

    @Nullable
    private String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int computePercent(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0;
        }
        double ratio = (clampStatic(value, min, max) - min) / (max - min);
        return Math.max(0, Math.min(100, Math.round((float) (ratio * 100.0))));
    }

    private static double clampStatic(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String formatSigned(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%+.2f", value);
    }

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
        static NpcStatusContext empty(@Nullable UUID npcUuid) {
            return new NpcStatusContext(npcUuid, null, false, true, false, false, null, null, null, null, null);
        }
    }

    record SnapshotOptions(boolean includeHappinessBreakdown,
                           boolean includeProgressionModifierTooltip) {
        static SnapshotOptions linkedPanel() {
            return new SnapshotOptions(true, true);
        }

        static SnapshotOptions compactHud() {
            return new SnapshotOptions(false, false);
        }
    }

    private static final class HealthSnapshot {
        private final int current;
        private final int max;

        private HealthSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class HappinessSnapshot {
        private final int current;
        private final int max;
        private final int targetPercent;
        private final String modifierBreakdown;

        private HappinessSnapshot(int current, int max, int targetPercent, String modifierBreakdown) {
            this.current = current;
            this.max = max;
            this.targetPercent = Math.max(0, Math.min(100, targetPercent));
            this.modifierBreakdown = modifierBreakdown;
        }
    }

    private static final class NeedsSnapshot {
        private final int hungerCurrent;
        private final int hungerMax;
        private final int thirstCurrent;
        private final int thirstMax;

        private NeedsSnapshot(int hungerCurrent, int hungerMax, int thirstCurrent, int thirstMax) {
            this.hungerCurrent = hungerCurrent;
            this.hungerMax = hungerMax;
            this.thirstCurrent = thirstCurrent;
            this.thirstMax = thirstMax;
        }
    }
}
