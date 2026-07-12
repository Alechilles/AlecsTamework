package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.items.CommandAutoLinkService;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentInheritanceService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TraitInheritanceService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.npc.progression.TraitRollService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies offspring inheritance and progression initialization after spawn.
 */
final class BreedingOffspringProgressionService {
    private static final String TRAIT_MUTATION_CHANCE_MULTIPLIER_EFFECT_KEY = "TraitMutationChanceMultiplier";
    private static final String APPEARANCE_MUTATION_CHANCE_MULTIPLIER_EFFECT_KEY = "AppearanceMutationChanceMultiplier";
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
    private static final long FAMILY_FLOCK_RETRY_INTERVAL_MS = 100L;
    private static final int FAMILY_FLOCK_RETRY_MAX_ATTEMPTS = 12;
    private final BreedingFamilyFlockService familyFlockService;

    BreedingOffspringProgressionService() {
        this.familyFlockService = new BreedingFamilyFlockService();
    }

    void applyOffspringState(Ref<EntityStore> childRef,
                             NPCEntity childNpc,
                             @Nullable Ref<EntityStore> parentARef,
                             @Nullable Ref<EntityStore> parentBRef,
                             String childRoleId,
                             OwnerSnapshot parentAOwner,
                             OwnerSnapshot parentBOwner,
                             boolean parentATamed,
                             boolean parentBTamed,
                             @Nullable String breedingConfigId,
                             long childCooldownMs,
                             @Nullable String selectedAdultRoleId,
                             @Nullable TwBreedingConfig.Gender selectedGender,
                             @Nullable TwBreedingConfig.RoleFamily lifecycleFamily,
                             Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null || childRoleId == null || childRoleId.isBlank()) {
            return;
        }
        TwBreedingConfig breedingConfig = resolveBreedingConfig(breedingConfigId);
        applyOffspringOwnershipAndTamedState(
                childRef,
                parentARef,
                parentAOwner,
                parentBOwner,
                parentATamed,
                parentBTamed,
                childRoleId,
                breedingConfigId,
                store
        );
        if (TamedStateResolver.isTamed(childRef, store)) {
            CompanionProgressionBootstrapService.ensureProgressionComponents(childRef, store);
        }
        applyOffspringAttachments(
                childRef,
                childNpc,
                parentARef,
                parentBRef,
                breedingConfig,
                childRoleId,
                breedingConfigId,
                store
        );
        applyOffspringTraits(childRef, parentARef, parentBRef, childRoleId, childNpc, breedingConfigId, store);
        CompanionStatModifierService.applyTraitModifiers(childRef, store);
        CompanionLifeStageService.initializeOffspringLifeStage(
                childRef,
                childNpc,
                store,
                childRoleId,
                breedingConfig,
                selectedAdultRoleId,
                lifecycleFamily
        );
        CompanionGenderService.ensureGender(childRef, store, childRoleId, breedingConfig, selectedGender);
        CompanionLifeStageService.refreshLifeStage(childRef, childNpc, store);
        applyOffspringBreedingLock(childRef, childNpc, childCooldownMs, store);
        boolean familyAssigned = familyFlockService.assignFamilyFlock(childRef, parentARef, parentBRef, store);
        if (!familyAssigned) {
            scheduleFamilyFlockRetry(childRef, parentARef, parentBRef, store, FAMILY_FLOCK_RETRY_MAX_ATTEMPTS);
        }
    }

    private void scheduleFamilyFlockRetry(@Nullable Ref<EntityStore> childRef,
                                          @Nullable Ref<EntityStore> parentARef,
                                          @Nullable Ref<EntityStore> parentBRef,
                                          @Nullable Store<EntityStore> store,
                                          int attemptsRemaining) {
        if (childRef == null || !childRef.isValid() || store == null || attemptsRemaining <= 0) {
            return;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> onFamilyFlockRetry(childRef, parentARef, parentBRef, world, attemptsRemaining)),
                CompletableFuture.delayedExecutor(FAMILY_FLOCK_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void onFamilyFlockRetry(@Nullable Ref<EntityStore> childRef,
                                    @Nullable Ref<EntityStore> parentARef,
                                    @Nullable Ref<EntityStore> parentBRef,
                                    @Nullable World world,
                                    int attemptsRemaining) {
        if (world == null || childRef == null || !childRef.isValid() || attemptsRemaining <= 0) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return;
        }
        boolean assigned = familyFlockService.assignFamilyFlock(childRef, parentARef, parentBRef, store);
        if (!assigned) {
            scheduleFamilyFlockRetry(childRef, parentARef, parentBRef, store, attemptsRemaining - 1);
        }
    }

    private void applyOffspringAttachments(@Nullable Ref<EntityStore> childRef,
                                           @Nullable NPCEntity childNpc,
                                           @Nullable Ref<EntityStore> parentARef,
                                           @Nullable Ref<EntityStore> parentBRef,
                                           @Nullable TwBreedingConfig breedingConfig,
                                           @Nullable String childRoleId,
                                           @Nullable String breedingConfigId,
                                           @Nullable Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        TwBreedingConfig.InheritanceSettings inheritance = breedingConfig != null
                ? breedingConfig.resolveInheritance(childRoleId)
                : null;
        CompanionAttachmentInheritanceService.AttachmentInheritanceProfile profile =
                CompanionAttachmentInheritanceService.AttachmentInheritanceProfile.fromConfig(inheritance)
                        .withMutationChanceMultiplier(resolvePairMutationChanceMultiplier(
                                parentARef,
                                parentBRef,
                                store,
                                APPEARANCE_MUTATION_CHANCE_MULTIPLIER_EFFECT_KEY
                        ));
        if (!profile.enabled()) {
            return;
        }

        Map<String, Set<String>> attachmentOptions = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(childRef, store)
        );
        if (attachmentOptions.isEmpty()) {
            return;
        }

        long seed = resolveOffspringSeed(childNpc, parentARef, parentBRef, store);
        Map<String, String> inheritedSelections = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                CompanionModelAttachmentService.resolveCurrentAttachments(parentARef, store),
                CompanionModelAttachmentService.resolveCurrentAttachments(parentBRef, store),
                attachmentOptions,
                seed,
                profile
        );
        if (inheritedSelections.isEmpty()) {
            return;
        }

        CompanionModelAttachmentService.applyAttachments(childRef, childNpc, store, inheritedSelections);
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null) {
            return;
        }
        String resolvedConfigId = breedingConfig != null && breedingConfig.getId() != null && !breedingConfig.getId().isBlank()
                ? breedingConfig.getId()
                : breedingConfigId;
        store.putComponent(
                childRef,
                attachmentsType,
                new TameworkAttachmentsComponent(resolvedConfigId, inheritedSelections)
        );
    }

    private void applyOffspringOwnershipAndTamedState(Ref<EntityStore> childRef,
                                                      @Nullable Ref<EntityStore> parentARef,
                                                      OwnerSnapshot parentAOwner,
                                                      OwnerSnapshot parentBOwner,
                                                      boolean parentATamed,
                                                      boolean parentBTamed,
                                                      @Nullable String childRoleId,
                                                      @Nullable String breedingConfigId,
                                                      Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        TwBreedingConfig config = resolveBreedingConfig(breedingConfigId);
        TwBreedingConfig.InheritanceSettings inheritance = config != null
                ? config.resolveInheritance(childRoleId)
                : null;
        boolean inheritOwner = inheritance == null || inheritance.isInheritOwner();
        boolean inheritTamed = inheritance == null || inheritance.isInheritTamed();

        OwnerSnapshot owner = resolveInheritedOwner(parentAOwner, parentBOwner);

        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        boolean tamed = inheritTamed && (parentATamed || parentBTamed);
        if (tamedType != null) {
            store.putComponent(childRef, tamedType, new TameworkTamedComponent(tamed));
        }

        String inheritedToolId = resolveInheritedToolId(parentARef, store);
        if (inheritOwner && inheritTamed && tamed) {
            applyInheritedCommandLink(childRef, owner.ownerId(), inheritedToolId, store);
            Player ownerPlayer = resolveOnlineOwner(store, owner.ownerId());
            if (ownerPlayer != null && inheritedToolId != null && !inheritedToolId.isBlank()) {
                CommandAutoLinkService.autoLinkNpcToPreferredTool(ownerPlayer, childRef, store, inheritedToolId);
            }
        }
    }

    @Nullable
    private String resolveInheritedToolId(@Nullable Ref<EntityStore> parentARef,
                                          @Nullable Store<EntityStore> store) {
        if (parentARef == null || !parentARef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(parentARef, linksType);
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            return null;
        }
        for (String toolId : links.getToolIds()) {
            if (toolId != null && !toolId.isBlank()) {
                return toolId;
            }
        }
        return null;
    }

    private void applyInheritedCommandLink(@Nullable Ref<EntityStore> childRef,
                                           @Nullable UUID ownerId,
                                           @Nullable String toolId,
                                           @Nullable Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null || toolId == null || toolId.isBlank()) {
            return;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return;
        }
        TameworkCommandLinksComponent existing = store.getComponent(childRef, linksType);
        TameworkCommandLinksComponent updated;
        if (existing == null) {
            updated = new TameworkCommandLinksComponent(ownerId, new String[] { toolId });
        } else {
            updated = existing.withToolIdAdded(toolId);
            updated.setOwnerId(ownerId);
        }
        store.putComponent(childRef, linksType, updated);
    }

    @Nullable
    private Player resolveOnlineOwner(@Nonnull Store<EntityStore> store, @Nullable UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(ownerId);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        try {
            return store.getComponent(playerRef, Player.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void applyOffspringTraits(Ref<EntityStore> childRef,
                                      @Nullable Ref<EntityStore> parentARef,
                                      @Nullable Ref<EntityStore> parentBRef,
                                      String childRoleId,
                                      @Nullable NPCEntity childNpc,
                                      @Nullable String breedingConfigId,
                                      Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return;
        }
        TwTraitConfig traitConfig = TwTraitConfig.resolveForRole(childRoleId);
        if (traitConfig == null || !TameworkRuntimeSettings.traitsEnabled(traitConfig.isEnabled())) {
            return;
        }
        TameworkTraitsComponent parentATraits = parentARef != null && parentARef.isValid()
                ? store.getComponent(parentARef, traitsType)
                : null;
        TameworkTraitsComponent parentBTraits = parentBRef != null && parentBRef.isValid()
                ? store.getComponent(parentBRef, traitsType)
                : null;
        TameworkTraitsComponent existingTraits = store.getComponent(childRef, traitsType);

        long seed = resolveOffspringSeed(childNpc, parentARef, parentBRef, store);
        TwBreedingConfig breedingConfig = resolveBreedingConfig(breedingConfigId);
        boolean inheritTraits = breedingConfig != null && breedingConfig.resolveInheritance(childRoleId).isInheritTraits();
        double previousSizeMultiplier = TraitModifierService.resolveMultiplier(
                existingTraits,
                traitConfig,
                "SizeMultiplier",
                1.0
        );
        double mutationChanceMultiplier = resolvePairMutationChanceMultiplier(
                parentARef,
                parentBRef,
                store,
                TRAIT_MUTATION_CHANCE_MULTIPLIER_EFFECT_KEY
        );
        TameworkTraitsComponent.TraitValue[] values = inheritTraits
                ? TraitInheritanceService.inheritTraits(traitConfig, parentATraits, parentBTraits, seed, mutationChanceMultiplier)
                : TraitRollService.rollTraits(traitConfig, seed);
        TameworkTraitsComponent updatedTraits = new TameworkTraitsComponent(traitConfig.getId(), seed, values);
        double nextSizeMultiplier = TraitModifierService.resolveMultiplier(
                updatedTraits,
                traitConfig,
                "SizeMultiplier",
                1.0
        );
        store.putComponent(
                childRef,
                traitsType,
                updatedTraits
        );
        CompanionLifeStageService.applySizeMultiplierDelta(
                childRef,
                store,
                previousSizeMultiplier,
                nextSizeMultiplier
        );
    }

    private double resolvePairMutationChanceMultiplier(@Nullable Ref<EntityStore> parentARef,
                                                       @Nullable Ref<EntityStore> parentBRef,
                                                       @Nonnull Store<EntityStore> store,
                                                       @Nonnull String effectKey) {
        double parentA = CompanionProgressionModifierService.resolveMultiplier(
                parentARef,
                store,
                effectKey,
                1.0
        );
        double parentB = CompanionProgressionModifierService.resolveMultiplier(
                parentBRef,
                store,
                effectKey,
                1.0
        );
        if (!Double.isFinite(parentA) || parentA <= 0.0) {
            parentA = 1.0;
        }
        if (!Double.isFinite(parentB) || parentB <= 0.0) {
            parentB = 1.0;
        }
        return Math.max(0.0, (parentA + parentB) * 0.5);
    }

    private void applyOffspringBreedingLock(Ref<EntityStore> childRef,
                                            @Nullable NPCEntity childNpc,
                                            long childCooldownMs,
                                            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return;
        }
        TameworkBreedingComponent breeding = store.getComponent(childRef, breedingType);
        if (breeding == null) {
            return;
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long cooldownDurationMs = Math.max(0L, childCooldownMs);
        long cooldownUntilMs = BreedingTimeService.deadlineAfter(now, cooldownDurationMs);
        breeding.setReady(false);
        breeding.setCooldownUntilMs(cooldownUntilMs);
        breeding.setCooldownStartedAtMs(BreedingTimeService.cooldownStartedAt(now, cooldownDurationMs));
        breeding.setCooldownDurationMs(cooldownDurationMs);
        breeding.setLastPartnerUuid(null);
        breeding.clearManualBreedingReady();
        store.putComponent(childRef, breedingType, breeding);
        applyCooldownAlarm(childRef, childNpc, cooldownUntilMs, store);
    }

    private void applyCooldownAlarm(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable NPCEntity npc,
                                    long cooldownUntilMs,
                                    @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null || cooldownUntilMs == 0L) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return;
        }
        Alarm alarm = alarmStore.get(npc, BREEDING_COOLDOWN_ALARM_NAME);
        if (alarm == null) {
            return;
        }
        alarm.set(npcRef, Instant.ofEpochMilli(cooldownUntilMs), store);
    }

    @Nullable
    private TwBreedingConfig resolveBreedingConfig(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        return TwBreedingConfig.resolveById(configId);
    }

    private long resolveOffspringSeed(@Nullable NPCEntity childNpc,
                                      @Nullable Ref<EntityStore> parentARef,
                                      @Nullable Ref<EntityStore> parentBRef,
                                      Store<EntityStore> store) {
        if (childNpc != null && childNpc.getUuid() != null) {
            UUID uuid = childNpc.getUuid();
            long seed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            if (seed != 0L) {
                return seed;
            }
        }
        long parentASeed = resolveEntitySeed(parentARef, store);
        long parentBSeed = resolveEntitySeed(parentBRef, store);
        long seed = parentASeed ^ parentBSeed ^ System.nanoTime();
        return seed != 0L ? seed : 1L;
    }

    private long resolveEntitySeed(@Nullable Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return 0L;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return 0L;
        }
        UUID uuid = npc.getUuid();
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }

    private OwnerSnapshot resolveInheritedOwner(OwnerSnapshot parentAOwner, OwnerSnapshot parentBOwner) {
        if (parentAOwner != null && parentAOwner.ownerId() != null) {
            return parentAOwner;
        }
        if (parentBOwner != null && parentBOwner.ownerId() != null) {
            return parentBOwner;
        }
        return OwnerSnapshot.empty();
    }

    record OwnerSnapshot(@Nullable UUID ownerId, @Nullable String ownerName) {
        static OwnerSnapshot empty() {
            return new OwnerSnapshot(null, null);
        }
    }
}
