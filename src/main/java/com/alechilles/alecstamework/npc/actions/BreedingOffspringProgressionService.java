package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.TraitInheritanceService;
import com.alechilles.alecstamework.npc.progression.TraitRollService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Applies offspring inheritance and progression initialization after spawn.
 */
final class BreedingOffspringProgressionService {
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
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
                             boolean hasBabyVariant,
                             Store<EntityStore> store) {
        if (childRef == null || !childRef.isValid() || store == null || childRoleId == null || childRoleId.isBlank()) {
            return;
        }
        applyOffspringOwnershipAndTamedState(
                childRef,
                parentAOwner,
                parentBOwner,
                parentATamed,
                parentBTamed,
                breedingConfigId,
                store
        );
        applyOffspringTraits(childRef, parentARef, parentBRef, childRoleId, childNpc, breedingConfigId, store);
        CompanionLifeStageService.initializeOffspringLifeStage(childRef, childNpc, store, hasBabyVariant);
        CompanionLifeStageService.refreshLifeStage(childRef, childNpc, store);
        applyOffspringBreedingLock(childRef, childNpc, childCooldownMs, store);
        familyFlockService.assignFamilyFlock(childRef, parentARef, parentBRef, store);
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

        long seed = resolveOffspringSeed(childNpc, parentARef, parentBRef, store);
        TwBreedingConfig breedingConfig = resolveBreedingConfig(breedingConfigId);
        boolean inheritTraits = breedingConfig != null && breedingConfig.getInheritance().isInheritTraits();
        TameworkTraitsComponent.TraitValue[] values = inheritTraits
                ? TraitInheritanceService.inheritTraits(traitConfig, parentATraits, parentBTraits, seed)
                : TraitRollService.rollTraits(traitConfig, seed);
        store.putComponent(
                childRef,
                traitsType,
                new TameworkTraitsComponent(traitConfig.getId(), seed, values)
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
        long now = System.currentTimeMillis();
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
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null || cooldownUntilMs <= 0L) {
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
