package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentInheritanceService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TraitInheritanceService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.npc.progression.TraitRollService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
import javax.annotation.Nullable;

/**
 * Applies offspring inheritance and progression initialization after spawn.
 */
final class BreedingOffspringProgressionService {
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
                             @Nullable TwBreedingConfig.RoleFamily lifecycleFamily,
                             Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null || childRoleId == null || childRoleId.isBlank()) {
            return;
        }
        TwBreedingConfig breedingConfig = resolveBreedingConfig(breedingConfigId);
        applyOffspringOwnershipAndTamedState(
                childRef,
                parentAOwner,
                parentBOwner,
                parentATamed,
                parentBTamed,
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
                lifecycleFamily
        );
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
                                           @Nullable String breedingConfigId,
                                           @Nullable Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        TwBreedingConfig.InheritanceSettings inheritance = breedingConfig != null
                ? breedingConfig.getInheritance()
                : null;
        CompanionAttachmentInheritanceService.AttachmentInheritanceProfile profile =
                CompanionAttachmentInheritanceService.AttachmentInheritanceProfile.fromConfig(inheritance);
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
                                                      OwnerSnapshot parentAOwner,
                                                      OwnerSnapshot parentBOwner,
                                                      boolean parentATamed,
                                                      boolean parentBTamed,
                                                      @Nullable String breedingConfigId,
                                                      Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        TwBreedingConfig config = resolveBreedingConfig(breedingConfigId);
        TwBreedingConfig.InheritanceSettings inheritance = config != null
                ? config.getInheritance()
                : null;
        boolean inheritOwner = inheritance == null || inheritance.isInheritOwner();
        boolean inheritTamed = inheritance == null || inheritance.isInheritTamed();

        OwnerSnapshot owner = resolveInheritedOwner(parentAOwner, parentBOwner);
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (inheritOwner && ownerType != null) {
            store.putComponent(childRef, ownerType, new TameworkOwnerComponent(owner.ownerId(), owner.ownerName()));
        }

        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        boolean tamed = inheritTamed && (parentATamed || parentBTamed);
        if (tamedType != null) {
            store.putComponent(childRef, tamedType, new TameworkTamedComponent(tamed));
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
        if (traitConfig == null || !traitConfig.isEnabled()) {
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
        boolean inheritTraits = breedingConfig != null && breedingConfig.getInheritance().isInheritTraits();
        double previousSizeMultiplier = TraitModifierService.resolveMultiplier(
                existingTraits,
                traitConfig,
                "SizeMultiplier",
                1.0
        );
        TameworkTraitsComponent.TraitValue[] values = inheritTraits
                ? TraitInheritanceService.inheritTraits(traitConfig, parentATraits, parentBTraits, seed)
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
        long cooldownUntilMs = now + Math.max(0L, childCooldownMs);
        breeding.setReady(false);
        breeding.setCooldownUntilMs(cooldownUntilMs);
        breeding.setLastPartnerUuid(null);
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
