package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentStateService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ensures progression components exist for tamed companions when NPC entities are loaded into the world store.
 *
 * <p>This self-heals missing shared happiness/breeding/traits state after reloads.
 */
public final class CompanionProgressionBootstrapOnLoadSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;

    public CompanionProgressionBootstrapOnLoadSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                                     ComponentType<EntityStore, TameworkTamedComponent> tamedType) {
        this.npcType = npcType;
        this.tamedType = tamedType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        TameworkTamedComponent tamed = tamedType != null ? store.getComponent(reference, tamedType) : null;
        boolean attachmentLoadBootstrap = isAttachmentBootstrapRequired(reference, store, roleId, reason, tamed);
        boolean progressionBootstrap = isTamed(tamed) && requiresProgressionBootstrap(reference, store, roleId);
        if (!attachmentLoadBootstrap && !progressionBootstrap) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            if (bufferStore == null || reference == null || !reference.isValid()) {
                return;
            }
            if (bufferStore.getComponent(reference, npcType) == null) {
                return;
            }
            TameworkTamedComponent latestTamed = tamedType != null ? bufferStore.getComponent(reference, tamedType) : null;
            if (isTamed(latestTamed) && requiresProgressionBootstrap(reference, bufferStore, roleId)) {
                CompanionProgressionBootstrapService.ensureProgressionComponents(reference, bufferStore, roleId);
            }
            if (attachmentLoadBootstrap) {
                CompanionAttachmentStateService.seedStoredAttachmentsOnLoad(reference, bufferStore);
            }
        });
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op.
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null) {
            return Query.any();
        }
        return Query.and(npcType);
    }

    private boolean requiresProgressionBootstrap(@Nonnull Ref<EntityStore> reference,
                                                 @Nonnull Store<EntityStore> store,
                                                 String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        if (isNeedsBootstrapRequired(reference, store, roleId)) {
            return true;
        }
        if (isHappinessBootstrapRequired(reference, store, roleId)) {
            return true;
        }
        if (isBreedingBootstrapRequired(reference, store, roleId)) {
            return true;
        }
        return isTraitBootstrapRequired(reference, store, roleId);
    }

    private boolean isNeedsBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull String roleId) {
        TwNeedsConfig config = TwNeedsConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        var needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent needs = store.getComponent(reference, needsType);
        return needs == null || needs.getLastUpdateMs() <= 0L || needs.getLastPassiveSweepMs() <= 0L;
    }

    private boolean isHappinessBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                                 @Nonnull Store<EntityStore> store,
                                                 @Nonnull String roleId) {
        TwHappinessConfig config = TwHappinessConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        var happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return false;
        }
        TameworkHappinessComponent happiness = store.getComponent(reference, happinessType);
        return happiness == null || happiness.getLastUpdateMs() <= 0L;
    }

    private boolean isBreedingBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull String roleId) {
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        var breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return false;
        }
        return store.getComponent(reference, breedingType) == null;
    }

    private boolean isTraitBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull String roleId) {
        TwTraitConfig config = TwTraitConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        var traitsType = TameworkTraitsComponent.getComponentType();
        var lifeStageType = TameworkLifeStageComponent.getComponentType();
        if (traitsType == null || lifeStageType == null) {
            return false;
        }
        TameworkTraitsComponent traits = store.getComponent(reference, traitsType);
        if (traits == null || traits.getRollSeed() == 0L || traits.getTraitValues() == null || traits.getTraitValues().length == 0) {
            return true;
        }
        return store.getComponent(reference, lifeStageType) == null;
    }

    private boolean isAttachmentBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull String roleId,
                                                  @Nonnull AddReason reason,
                                                  @Nullable TameworkTamedComponent tamed) {
        var attachmentsType = TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null) {
            return false;
        }
        TameworkAttachmentsComponent attachments = store.getComponent(reference, attachmentsType);
        boolean hasStoredAttachments = attachments != null
                && attachments.getAttachmentIds() != null
                && !attachments.getAttachmentIds().isEmpty();
        boolean hasMigrationConfig = TwAttachmentMigrationConfig.resolveForRole(roleId) != null;
        return shouldRunAttachmentLoadBootstrap(reason, tamed, hasStoredAttachments, hasMigrationConfig);
    }

    static boolean shouldRunAttachmentLoadBootstrap(@Nonnull AddReason reason,
                                                    @Nullable TameworkTamedComponent tamed,
                                                    boolean hasStoredAttachments,
                                                    boolean hasMigrationConfig) {
        return reason == AddReason.LOAD && (hasStoredAttachments || hasMigrationConfig);
    }

    private static boolean isTamed(@Nullable TameworkTamedComponent tamed) {
        return tamed != null && tamed.isTamed();
    }
}
