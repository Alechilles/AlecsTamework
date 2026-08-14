package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
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
 * Ensures trait and progression components exist when NPC entities enter the world store.
 *
 * <p>One role and config decision now serves untamed traits, tamed progression, and attachment repair.
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
        LoadDecision decision = buildLoadDecision(reference, store, roleId, reason);
        if (!decision.requiresWork()) {
            return;
        }
        commandBuffer.run(bufferStore -> applyLoadRepairs(reference, bufferStore, reason));
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

    private void applyLoadRepairs(@Nonnull Ref<EntityStore> reference,
                                  @Nullable Store<EntityStore> store,
                                  @Nonnull AddReason reason) {
        if (store == null || !reference.isValid() || store.getComponent(reference, npcType) == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        LoadDecision decision = buildLoadDecision(reference, store, roleId, reason);
        if (decision.progressionRepair()) {
            CompanionProgressionBootstrapService.ensureNonTraitProgressionComponents(reference, store, roleId);
        }
        if (decision.traitPlan() == CompanionTraitBootstrapPlan.LIFE_STAGE_ONLY) {
            CompanionProgressionBootstrapService.ensureLifeStageOnLoad(reference, store, roleId);
        } else if (decision.traitPlan() == CompanionTraitBootstrapPlan.FULL_REPAIR) {
            CompanionProgressionBootstrapService.ensureTraitComponents(reference, store, roleId);
        }
        if (decision.attachmentRepair()) {
            CompanionAttachmentStateService.seedStoredAttachmentsOnLoad(reference, store);
        } else if (decision.progressionRepair()) {
            CompanionAttachmentStateService.seedStoredAttachments(reference, store);
        }
    }

    private LoadDecision buildLoadDecision(@Nonnull Ref<EntityStore> reference,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull String roleId,
                                           @Nonnull AddReason reason) {
        TameworkTamedComponent tamed = tamedType != null ? store.getComponent(reference, tamedType) : null;
        CompanionTraitBootstrapPlan traitPlan = resolveTraitBootstrapPlan(reference, store, roleId);
        boolean tamedNpc = isTamed(tamed);
        boolean progressionRepair = tamedNpc
                && requiresNonTraitProgressionBootstrap(reference, store, roleId, traitPlan);
        boolean attachmentRepair = isAttachmentBootstrapRequired(reference, store, roleId, reason, tamed);
        return LoadDecision.classify(tamedNpc, traitPlan, progressionRepair, attachmentRepair);
    }

    private boolean requiresNonTraitProgressionBootstrap(@Nonnull Ref<EntityStore> reference,
                                                         @Nonnull Store<EntityStore> store,
                                                         String roleId,
                                                         @Nonnull CompanionTraitBootstrapPlan traitPlan) {
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
        if (traitPlan == CompanionTraitBootstrapPlan.NONE
                && isLifeStageBootstrapRequired(reference, store, roleId)) {
            return true;
        }
        if (isTalentBootstrapRequired(reference, store, roleId)) {
            return true;
        }
        return false;
    }

    private boolean isTalentBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                              @Nonnull Store<EntityStore> store,
                                              @Nonnull String roleId) {
        TwTalentConfig config = TwTalentConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        ComponentType<EntityStore, TameworkTalentsComponent> talentsType =
                TameworkTalentsComponent.getComponentType();
        if (talentsType == null) {
            return false;
        }
        TameworkTalentsComponent talents = store.getComponent(reference, talentsType);
        return talents == null || !CompanionTalentService.isAllocationCompatible(talents, config);
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
        return needsTimestampsRequireBootstrap(needs);
    }

    static boolean needsTimestampsRequireBootstrap(@Nullable TameworkNeedsComponent needs) {
        return needs == null
                || needs.getLastUpdateMs() == 0L
                || needs.getLastPassiveSweepMs() == 0L;
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

    private CompanionTraitBootstrapPlan resolveTraitBootstrapPlan(@Nonnull Ref<EntityStore> reference,
                                                                  @Nonnull Store<EntityStore> store,
                                                                  @Nonnull String roleId) {
        TwTraitConfig config = TwTraitConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return CompanionTraitBootstrapPlan.NONE;
        }
        var traitsType = TameworkTraitsComponent.getComponentType();
        var lifeStageType = TameworkLifeStageComponent.getComponentType();
        if (traitsType == null) {
            return CompanionTraitBootstrapPlan.NONE;
        }
        TameworkTraitsComponent traits = store.getComponent(reference, traitsType);
        boolean lifeStagePresent = lifeStageType == null || store.getComponent(reference, lifeStageType) != null;
        return CompanionTraitBootstrapPlan.classify(traits, config.getId(), lifeStagePresent);
    }

    private boolean isLifeStageBootstrapRequired(@Nonnull Ref<EntityStore> reference,
                                                 @Nonnull Store<EntityStore> store,
                                                 @Nonnull String roleId) {
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.resolveOffspringLifecycle(roleId);
        if (lifecycle == null || !lifecycle.isEnabled()) {
            return false;
        }
        TwBreedingConfig.RoleFamily family = config.resolveLifecycleLineFamilyForRole(roleId);
        if (family == null) {
            family = config.resolveLifecycleFamilyForRole(roleId);
        }
        if (!isJuvenileLifecycleRole(roleId, family)) {
            return false;
        }
        var lifeStageType = TameworkLifeStageComponent.getComponentType();
        return lifeStageType != null && store.getComponent(reference, lifeStageType) == null;
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

    static boolean isJuvenileLifecycleRole(@Nullable String roleId,
                                           @Nullable TwBreedingConfig.RoleFamily family) {
        if (roleId == null || roleId.isBlank() || family == null) {
            return false;
        }
        if (matchesRoleId(roleId, family.getBabyRoleId())
                || matchesRoleId(roleId, family.getAdolescentRoleId())) {
            return true;
        }
        for (TwBreedingConfig.RoleLine line : family.getLines()) {
            if (line == null) {
                continue;
            }
            if (matchesRoleId(roleId, line.getBabyRoleId())
                    || matchesRoleId(roleId, line.getAdolescentRoleId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRoleId(@Nullable String left, @Nullable String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return normalizeRoleId(left).equals(normalizeRoleId(right));
    }

    private static String normalizeRoleId(@Nonnull String roleId) {
        String normalized = roleId.trim().toLowerCase(java.util.Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }

    private static boolean isTamed(@Nullable TameworkTamedComponent tamed) {
        return tamed != null && tamed.isTamed();
    }

    record LoadDecision(CompanionTraitBootstrapPlan traitPlan,
                        boolean progressionRepair,
                        boolean attachmentRepair) {
        static LoadDecision classify(boolean tamed,
                                     @Nonnull CompanionTraitBootstrapPlan traitPlan,
                                     boolean progressionRepair,
                                     boolean attachmentRepair) {
            return new LoadDecision(traitPlan, tamed && progressionRepair, attachmentRepair);
        }

        boolean requiresWork() {
            return traitPlan != CompanionTraitBootstrapPlan.NONE || progressionRepair || attachmentRepair;
        }
    }
}
