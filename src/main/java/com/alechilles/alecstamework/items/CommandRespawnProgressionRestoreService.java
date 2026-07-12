package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentMigrationService;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Restores persisted progression state onto a durably admitted replacement companion. */
final class CommandRespawnProgressionRestoreService {
    void applyProgression(Ref<EntityStore> ref, Store<EntityStore> store,
                          CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (ref == null || !ref.isValid() || store == null || snapshot == null) return;
        applyHappiness(ref, store, snapshot);
        applyBreeding(ref, store, snapshot);
        applyLeveling(ref, store, snapshot);
        applyTraits(ref, store, snapshot);
        applyTalents(ref, store, snapshot);
        applyLifeStage(ref, store, snapshot);
        CompanionStatModifierService.applyTraitModifiers(ref, store);
    }

    void applyRecovery(Ref<EntityStore> ref, Store<EntityStore> store,
                       CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (ref == null || !ref.isValid() || store == null) return;
        CompanionHealthStateService.applyStoredHealthPercent(ref, store, 100.0);
        ComponentType<EntityStore, TameworkNeedsComponent> type = TameworkNeedsComponent.getComponentType();
        if (type == null) return;
        String roleId = snapshot != null && snapshot.roleId() != null && !snapshot.roleId().isBlank()
                ? snapshot.roleId() : CompanionRoleIdResolver.resolveRoleId(ref, store);
        TwNeedsConfig config = TwNeedsConfig.resolveForRole(roleId);
        if (config != null && TameworkRuntimeSettings.needsEnabled(config.isEnabled())) {
            store.putComponent(ref, type, createNeeds(config, CompanionRuntimeClock.nowMs()));
        }
    }

    static TameworkNeedsComponent createNeeds(TwNeedsConfig config, long nowMs) {
        TwNeedsConfig.ValueSettings values = config.getValues();
        TameworkNeedsComponent component = new TameworkNeedsComponent(
                config.getId(), values.getHungerDefault(), values.getThirstDefault(),
                0.0, 0.0, nowMs, nowMs
        );
        component.setRegenSuppressionBaselineHealth(-1.0);
        component.setRegenSuppressionAllowedHeal(0.0);
        component.setLastManagedHealth(-1.0);
        return component;
    }

    void applyAttachments(Ref<EntityStore> ref, NPCEntity npc, Store<EntityStore> store,
                          CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (ref == null || !ref.isValid() || store == null || snapshot == null) return;
        Map<String, String> selections =
                CommandLinkedNpcDeathService.decodeAttachmentSelections(snapshot.attachmentsValues());
        if (selections.isEmpty()) return;
        Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(ref, store)
        );
        String roleId = firstNonBlank(snapshot.roleId(), CompanionRoleIdResolver.resolveRoleId(ref, store));
        selections = resolveAttachments(TwAttachmentMigrationConfig.resolveForRole(roleId), selections, options);
        if (!selections.isEmpty()) CompanionModelAttachmentService.applyAttachments(ref, npc, store, selections);
        ComponentType<EntityStore, TameworkAttachmentsComponent> type =
                TameworkAttachmentsComponent.getComponentType();
        if (type != null && !selections.isEmpty()) {
            store.putComponent(ref, type,
                    new TameworkAttachmentsComponent(snapshot.attachmentsConfigId(), selections));
        }
    }

    static Map<String, String> resolveAttachments(
            @Nullable TwAttachmentMigrationConfig migration,
            @Nullable Map<String, String> snapshot,
            @Nullable Map<String, Set<String>> options) {
        Map<String, String> filtered =
                CompanionModelAttachmentService.filterAttachmentSelections(snapshot, options);
        if (filtered.isEmpty()) return Map.of();
        Map<String, String> migrated =
                CompanionAttachmentMigrationService.applyConfiguredMigrations(migration, filtered, options);
        return CompanionModelAttachmentService.filterAttachmentSelections(migrated, options);
    }

    private void applyHappiness(Ref<EntityStore> ref, Store<EntityStore> store,
                                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkHappinessComponent> type =
                TameworkHappinessComponent.getComponentType();
        boolean present = (snapshot.happinessConfigId() != null && !snapshot.happinessConfigId().isBlank())
                || snapshot.happinessValue() != null || snapshot.happinessLastUpdateMs() > 0L
                || snapshot.breedingHappiness() != null;
        if (type == null || !present) return;
        double value = snapshot.happinessValue() != null ? snapshot.happinessValue()
                : snapshot.breedingHappiness() != null ? snapshot.breedingHappiness() : 0.0;
        long updated = snapshot.happinessLastUpdateMs() > 0L
                ? snapshot.happinessLastUpdateMs() : System.currentTimeMillis();
        store.putComponent(ref, type,
                new TameworkHappinessComponent(snapshot.happinessConfigId(), value, updated));
    }

    private void applyBreeding(Ref<EntityStore> ref, Store<EntityStore> store,
                               CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        boolean present = (snapshot.breedingConfigId() != null && !snapshot.breedingConfigId().isBlank())
                || snapshot.breedingHappiness() != null || snapshot.breedingEnabled()
                || snapshot.breedingCooldownUntilMs() != 0L || snapshot.breedingLastPartnerUuid() != null;
        if (type == null || !present) return;
        Double restored = restoredHappiness(ref, store);
        double happiness = restored != null ? restored
                : snapshot.breedingHappiness() != null ? snapshot.breedingHappiness() : 0.0;
        String configId = snapshot.breedingConfigId();
        boolean ready = snapshot.breedingEnabled() && breedingReady(configId, happiness, ref, store);
        long cooldownUntil = snapshot.breedingCooldownUntilMs();
        BreedingTimeService.CooldownTiming timing = restoreCooldownTiming(
                cooldownUntil, BreedingTimeService.resolveCurrentTimeMs(store)
        );
        long happinessUpdated = restoredHappinessTimestamp(ref, store);
        store.putComponent(ref, type, new TameworkBreedingComponent(
                configId, happiness, happinessUpdated > 0L ? happinessUpdated : System.currentTimeMillis(),
                ready, snapshot.breedingEnabled(), cooldownUntil, snapshot.breedingLastPartnerUuid(),
                timing.startedAtMs(), timing.durationMs()
        ));
    }

    static BreedingTimeService.CooldownTiming restoreCooldownTiming(long deadlineMs, long nowMs) {
        return BreedingTimeService.reconstructCooldownTiming(deadlineMs, nowMs);
    }

    private void applyTraits(Ref<EntityStore> ref, Store<EntityStore> store,
                             CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        boolean present = (snapshot.traitsConfigId() != null && !snapshot.traitsConfigId().isBlank())
                || snapshot.traitsRollSeed() != 0L
                || (snapshot.traitsValues() != null && !snapshot.traitsValues().isBlank());
        if (type != null && present) store.putComponent(ref, type, new TameworkTraitsComponent(
                snapshot.traitsConfigId(), snapshot.traitsRollSeed(),
                TraitValueCodec.decode(snapshot.traitsValues())
        ));
    }

    private void applyLeveling(Ref<EntityStore> ref, Store<EntityStore> store,
                               CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) return;
        boolean present = (snapshot.levelingConfigId() != null && !snapshot.levelingConfigId().isBlank())
                || snapshot.levelingLevel() > 1 || snapshot.levelingTotalXp() > 0.0;
        if (present) store.putComponent(ref, type, new TameworkLevelingComponent(
                snapshot.levelingConfigId(), snapshot.levelingLevel(), 0.0, snapshot.levelingTotalXp()
        ));
        CompanionLevelingService.ensureLevelingComponent(ref, store);
    }

    private void applyTalents(Ref<EntityStore> ref, Store<EntityStore> store,
                              CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        boolean present = (snapshot.talentsConfigId() != null && !snapshot.talentsConfigId().isBlank())
                || snapshot.talentsSpentPoints() > 0
                || (snapshot.purchasedTalentIds() != null && !snapshot.purchasedTalentIds().isBlank());
        if (type != null && present) store.putComponent(ref, type, new TameworkTalentsComponent(
                snapshot.talentsConfigId(), snapshot.talentsSpentPoints(),
                TalentIdCodec.decode(snapshot.purchasedTalentIds())
        ));
    }

    private void applyLifeStage(Ref<EntityStore> ref, Store<EntityStore> store,
                                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) return;
        boolean present = (snapshot.lifeStage() != null && !snapshot.lifeStage().isBlank())
                || snapshot.lifeStageBornAtMs() != 0L || snapshot.lifeStageAdolescentAtMs() != 0L
                || snapshot.lifeStageAdultAtMs() != 0L || snapshot.lifeStageFullyGrownAtMs() != 0L;
        if (!present) {
            CompanionLifeStageService.ensureLifeStageComponent(ref, store);
            applyGender(ref, store, type, snapshot.lifeStageGender());
        } else {
            TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                    snapshot.lifeStage(), snapshot.lifeStageBornAtMs(), snapshot.lifeStageAdolescentAtMs(),
                    snapshot.lifeStageAdultAtMs(), snapshot.lifeStageFullyGrownAtMs(),
                    snapshot.lifeStageBabyScale(), snapshot.lifeStageAdolescentScale(),
                    snapshot.lifeStageAdolescentSwitchScale(), snapshot.lifeStageAdultStartScale(),
                    snapshot.lifeStageAdultSwitchScale(), snapshot.lifeStageAdultScale(),
                    snapshot.lifeStageGrowthScalingEnabled()
            );
            component.setGender(snapshot.lifeStageGender());
            store.putComponent(ref, type, component);
        }
        CompanionLifeStageService.refreshLifeStage(ref, store.getComponent(ref, NPCEntity.getComponentType()), store);
    }

    private void applyGender(Ref<EntityStore> ref, Store<EntityStore> store,
                             ComponentType<EntityStore, TameworkLifeStageComponent> type,
                             @Nullable String gender) {
        if (gender == null || gender.isBlank()) return;
        TameworkLifeStageComponent component = store.getComponent(ref, type);
        if (component != null) {
            component.setGender(gender);
            store.putComponent(ref, type, component);
        }
    }

    private boolean breedingReady(String configId, double happiness,
                                  Ref<EntityStore> ref, Store<EntityStore> store) {
        if (configId == null || configId.isBlank()) return false;
        TwBreedingConfig config = TwBreedingConfig.resolveById(configId);
        if (config == null) return false;
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        return happiness >= TameworkRuntimeSettings.breedingHappinessThreshold(
                config.resolveHappiness(roleId).getThreshold(), TwHappinessConfig.isEnabledForRole(roleId)
        );
    }

    @Nullable
    private Double restoredHappiness(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type =
                TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent value = type == null ? null : store.getComponent(ref, type);
        return value == null || !Double.isFinite(value.getValue()) ? null : value.getValue();
    }

    private long restoredHappinessTimestamp(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type =
                TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent value = type == null ? null : store.getComponent(ref, type);
        return value == null ? 0L : value.getLastUpdateMs();
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) return first;
        return second != null && !second.isBlank() ? second : null;
    }
}
