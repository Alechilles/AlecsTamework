package com.alechilles.alecstamework;

import com.alechilles.alecstamework.avatarflight.*;
import com.alechilles.alecstamework.debug.PlayerInputDebugSystem;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsBatchRunner;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsRuntimeRegistry;
import com.alechilles.alecstamework.npc.systems.*;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationEntitySystem;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationOwnerChangeSystem;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

/** Declares companion and mount systems for the startup activation boundary. */
public final class TameworkCompanionRuntimeParticipants {
    private TameworkCompanionRuntimeParticipants() {
    }

    /** Adds potential participants and returns the optional spawn-marker type. */
    public static ComponentType<EntityStore, SpawnMarkerEntity> add(
            Tamework plugin,
            TameworkRuntimeParticipantRegistry participants
    ) {
        addCore(plugin, participants);
        addAvatarFlight(plugin, participants);
        addMounts(plugin, participants);
        addCompanionFeatures(plugin, participants);
        return plugin.resolveOptionalSpawnMarkerEntityComponentType();
    }

    private static void addCore(Tamework plugin, TameworkRuntimeParticipantRegistry participants) {
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "ownerpopulationentitysystem",
                () -> new OwnerPopulationEntitySystem(plugin.getOwnerPopulationLiveIndex(),
                        NPCEntity.getComponentType(), plugin.getOwnerComponentType()));
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "ownerpopulationownerchangesystem",
                () -> new OwnerPopulationOwnerChangeSystem(plugin.getOwnerPopulationLiveIndex(),
                        NPCEntity.getComponentType(), plugin.getOwnerComponentType()));
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "npcnamepersistencesystem",
                () -> new NpcNamePersistenceSystem(plugin.getNpcNameComponentType(), NPCEntity.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.DEBUG_SELF_TEST, "playerinputdebugsystem",
                () -> new PlayerInputDebugSystem(PlayerInput.getComponentType(), UUIDComponent.getComponentType(),
                        MovementStatesComponent.getComponentType(), HeadRotation.getComponentType(),
                        TransformComponent.getComponentType(), Velocity.getComponentType(),
                        ModelComponent.getComponentType()));
    }

    private static void addAvatarFlight(
            Tamework plugin,
            TameworkRuntimeParticipantRegistry participants
    ) {
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflightmovementsystem",
                () -> new AvatarFlightMovementSystem(plugin.getAvatarFlightComponentType(),
                        plugin.getAvatarFlightInputComponentType(), plugin.getAvatarFlightMountSessionComponentType(),
                        plugin.getAvatarFlightSourceComponentType(), UUIDComponent.getComponentType(),
                        Velocity.getComponentType(), MovementStatesComponent.getComponentType(),
                        HeadRotation.getComponentType(), TransformComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflightmountsessionsystem",
                () -> new AvatarFlightMountSessionSystem(plugin.getAvatarFlightMountSessionComponentType(),
                        plugin.getAvatarFlightSourceComponentType(), plugin.getAvatarFlightInputComponentType(),
                        UUIDComponent.getComponentType(), TransformComponent.getComponentType(),
                        DeathComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflighthudsystem",
                () -> new AvatarFlightHudSystem(plugin.getAvatarFlightComponentType(),
                        plugin.getAvatarFlightInputComponentType(), plugin.getAvatarFlightMountSessionComponentType(),
                        plugin.getAvatarFlightSourceComponentType(), UUIDComponent.getComponentType(),
                        Player.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflightinventoryguardsystem",
                () -> new AvatarFlightInventoryGuardSystem(plugin.getAvatarFlightComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflighthotbarguardsystem",
                () -> new AvatarFlightHotbarGuardSystem(plugin.getAvatarFlightComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflightequipmentvisualsystem",
                () -> new AvatarFlightEquipmentVisualSystem(plugin.getAvatarFlightComponentType(),
                        plugin.getAvatarFlightRiderVisualComponentType(),
                        EntityTrackerSystems.Visible.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.AVATAR_FLIGHT, "avatarflightridervisualcleanupsystem",
                () -> new AvatarFlightRiderVisualCleanupSystem(plugin.getAvatarFlightRiderVisualComponentType(),
                        plugin.getAvatarFlightComponentType()));
    }

    private static void addMounts(Tamework plugin, TameworkRuntimeParticipantRegistry participants) {
        ComponentType<EntityStore, NPCMountComponent> npcMount = plugin.resolveNpcMountComponentTypeOrNull();
        if (npcMount != null) {
            addNpcMounts(plugin, participants, npcMount);
        } else {
            plugin.getLogger().at(java.util.logging.Level.WARNING).log(
                    "Mount plugin component type unavailable; mount-dependent Tamework systems stay dormant."
            );
        }
        ComponentType<EntityStore, MountedComponent> mounted = plugin.resolveMountedComponentTypeOrNull();
        if (mounted != null) {
            addLegacyMounts(plugin, participants, mounted);
        } else {
            plugin.getLogger().at(java.util.logging.Level.WARNING).log(
                    "Mounted component type unavailable; legacy Tamework ride systems stay dormant."
            );
        }
    }

    private static void addNpcMounts(
            Tamework plugin,
            TameworkRuntimeParticipantRegistry participants,
            ComponentType<EntityStore, NPCMountComponent> npcMount
    ) {
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedownerreferencesanitysystem",
                () -> new MountedOwnerReferenceSanitySystem(NPCEntity.getComponentType(), npcMount,
                        Player.getComponentType(), Interactable.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountednpcteleportsafetysystem",
                () -> new MountedNpcTeleportSafetySystem(NPCEntity.getComponentType(), npcMount,
                        Teleport.getComponentType(), Player.getComponentType(), Interactable.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "npcmountednameplatevisibilitysystem",
                () -> new NpcMountedNameplateVisibilitySystem(NPCEntity.getComponentType(), npcMount,
                        plugin.getMountedNameplateComponentType(), plugin.getNpcNameComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedinteractablesafetysystem",
                MountedInteractableSafetySystem::new);
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedglideinputcapturesystem",
                () -> new MountedGlideInputCaptureSystem(npcMount, PlayerInput.getComponentType(),
                        MovementStatesComponent.getComponentType(), HeadRotation.getComponentType(),
                        plugin.getMountedGlideRiderComponentType(), plugin.getMountedGlideComponentType(),
                        UUIDComponent.getComponentType(), TransformComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedglideplayervelocitysystem",
                () -> new MountedGlidePlayerVelocitySystem(plugin.getMountedGlideComponentType(), npcMount,
                        TransformComponent.getComponentType(), Velocity.getComponentType(),
                        MovementStatesComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedglidecleanupsystem",
                () -> new MountedGlideCleanupSystem(npcMount, plugin.getMountedGlideRiderComponentType(),
                        plugin.getMountedGlideComponentType(), UUIDComponent.getComponentType(),
                        NPCEntity.getComponentType(), DeathComponent.getComponentType(), Player.getComponentType()));
    }

    private static void addLegacyMounts(
            Tamework plugin,
            TameworkRuntimeParticipantRegistry participants,
            ComponentType<EntityStore, MountedComponent> mounted
    ) {
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedrideinputcapturesystem",
                () -> new MountedRideInputCaptureSystem(mounted, PlayerInput.getComponentType(),
                        plugin.getRideRiderComponentType(), plugin.getRideMountComponentType(),
                        UUIDComponent.getComponentType(), MovementStatesComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedridecleanupsystem",
                () -> new MountedRideCleanupSystem(mounted, plugin.getRideRiderComponentType(),
                        plugin.getRideMountComponentType(), UUIDComponent.getComponentType(),
                        NPCEntity.getComponentType(), TransformComponent.getComponentType(),
                        DeathComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedrideriderfollowsystem",
                () -> new MountedRideRiderFollowSystem(mounted, plugin.getRideRiderComponentType(),
                        plugin.getRideMountComponentType(), TransformComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "mountedrideridercleanupsystem",
                () -> new MountedRideRiderCleanupSystem(mounted, plugin.getRideRiderComponentType(),
                        plugin.getRideMountComponentType(), UUIDComponent.getComponentType(),
                        NPCEntity.getComponentType(), DeathComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "shoulderridenpcfollowsystem",
                () -> new ShoulderRideNpcFollowSystem(plugin.getShoulderRideComponentType(), mounted,
                        TransformComponent.getComponentType(), Velocity.getComponentType(),
                        DeathComponent.getComponentType(), MovementStatesComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "shoulderridenpcstatesystem",
                () -> new ShoulderRideNpcStateSystem(plugin.getShoulderRideComponentType(), mounted,
                        Interactable.getComponentType(), Intangible.getComponentType(),
                        Invulnerable.getComponentType(), Frozen.getComponentType(),
                        MovementStatesComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.MOUNTS, "shoulderrideplayerteleportsystem",
                () -> new ShoulderRidePlayerTeleportSystem(Player.getComponentType(),
                        Teleport.getComponentType(), MountedByComponent.getComponentType(), mounted,
                        plugin.getShoulderRideComponentType()));
    }

    private static void addCompanionFeatures(
            Tamework plugin,
            TameworkRuntimeParticipantRegistry participants
    ) {
        CompanionNeedsRuntimeRegistry needsRegistry = new CompanionNeedsRuntimeRegistry();
        CompanionNeedsBatchRunner needsRunner = new CompanionNeedsBatchRunner();
        participants.entitySystem(TameworkRuntimeModule.DEBUG_SELF_TEST, "npcdebugdisplayresumeonloadsystem",
                () -> new NpcDebugDisplayResumeOnLoadSystem(NPCEntity.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.TRAITS, "companiontraitstatsyncsystem",
                () -> new CompanionTraitStatSyncSystem(NPCEntity.getComponentType(),
                        EntityStatMap.getComponentType(), plugin.getTraitsComponentType()));
        participants.entitySystem(TameworkRuntimeModule.CAPTURE, "companiontranquilizerpeaksystem",
                () -> new CompanionTranquilizerPeakSystem(NPCEntity.getComponentType(),
                        EffectControllerComponent.getComponentType(), plugin.getTranquilizerPeakComponentType()));
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "companionprogressionbootstraponloadsystem",
                () -> new CompanionProgressionBootstrapOnLoadSystem(NPCEntity.getComponentType(),
                        plugin.getTamedComponentType()));
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "companionspawnauthoritycleanupsystems-npc",
                () -> new CompanionSpawnAuthorityCleanupSystems.Npc(NPCEntity.getComponentType(),
                        plugin.getTamedComponentType()));
        participants.entitySystem(TameworkRuntimeModule.LEVELING, "summonedcompanionexperiencesystem",
                () -> new SummonedCompanionExperienceSystem(NPCEntity.getComponentType(),
                        plugin.getProjectionIdentityComponentType(), plugin.getLevelingComponentType(),
                        DeathComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.BREEDING, "companionlifestageresumeonloadsystem",
                () -> new CompanionLifeStageResumeOnLoadSystem(NPCEntity.getComponentType(),
                        plugin.getLifeStageComponentType()));
        participants.optionalEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-linked-revivable-drop-suppression", CommandLinkedRevivableDropSuppressionSystem::new);
        participants.entitySystem(TameworkRuntimeModule.ATTACHMENTS, "dynamicattachmentevaluationsystem",
                () -> new DynamicAttachmentEvaluationSystem(NPCEntity.getComponentType(),
                        plugin.getAttachmentsComponentType(), plugin.getDynamicAttachmentsComponentType(),
                        plugin.getOwnerComponentType(), plugin.getTamedComponentType(),
                        plugin.getLifeStageComponentType(), plugin.getHappinessComponentType(),
                        plugin.getNeedsComponentType(), plugin.getTraitsComponentType(),
                        plugin.getCommandLinksComponentType()));
        participants.entitySystem(TameworkRuntimeModule.ATTACHMENTS, "companionattachmentsyncsystem",
                CompanionAttachmentSyncSystem::new);
        participants.entitySystem(TameworkRuntimeModule.COMPANION_MOVEMENT, "companionmovementspeedsyncsystem",
                CompanionMovementSpeedSyncSystem::new);
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "companiondespawnprotectionsystem",
                CompanionDespawnProtectionSystem::new);
        participants.entitySystem(TameworkRuntimeModule.COMPANION_MOVEMENT, "flyingcompanioncontrolsystem",
                FlyingCompanionControlSystem::new);
        participants.entitySystem(TameworkRuntimeModule.CORE_OWNERSHIP, "companiondespawndiagnosticssystem",
                () -> new CompanionDespawnDiagnosticsSystem(NPCEntity.getComponentType(),
                        plugin.getTamedComponentType(), plugin.getOwnerComponentType(),
                        plugin.resolveOptionalSpawnMarkerReferenceComponentType(),
                        plugin.resolveOptionalSpawnBeaconReferenceComponentType(), UUIDComponent.getComponentType()));
        participants.entitySystem(TameworkRuntimeModule.NEEDS, "companionneedslifecyclesystem",
                () -> new CompanionNeedsLifecycleSystem(needsRegistry, NPCEntity.getComponentType(),
                        plugin.getTamedComponentType()));
        participants.entitySystem(TameworkRuntimeModule.NEEDS, "companionneedstamedchangesystem",
                () -> new CompanionNeedsTamedChangeSystem(needsRegistry, NPCEntity.getComponentType(),
                        plugin.getTamedComponentType()));
        participants.entitySystem(TameworkRuntimeModule.NEEDS, "companionneedssystem",
                () -> new CompanionNeedsSystem(needsRegistry, needsRunner));
        participants.entitySystem(TameworkRuntimeModule.BREEDING, "companionpassivebreedingsystem",
                () -> new CompanionPassiveBreedingSystem(plugin.getBreedingPairAdmissionRegistry()));
    }
}
