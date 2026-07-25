package com.alechilles.alecstamework;

import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightInputComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightRiderVisualComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.capture.runtime.TameworkCaptureSourceReceiptsComponent;
import com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileComponent;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectComponent;
import com.alechilles.alecstamework.items.components.TameworkFeedTroughWaterChargesComponent;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkFlyingCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureMarkerComponent;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Registers persistent Tamework ECS component types as one focused plugin setup responsibility. */
final class TameworkComponentRegistrar {
    private TameworkComponentRegistrar() {
    }

    @Nonnull
    static RegisteredComponents register(@Nonnull Tamework plugin) {
        ComponentType<EntityStore, TameworkOwnerComponent> owner = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkOwnerComponent.class, "TameworkOwner", TameworkOwnerComponent.CODEC);
        ComponentType<EntityStore, TameworkTamedComponent> tamed = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkTamedComponent.class, "TameworkTamed", TameworkTamedComponent.CODEC);
        ComponentType<EntityStore, TameworkHookComponent> hook = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkHookComponent.class, "TameworkHook", TameworkHookComponent.CODEC);
        ComponentType<EntityStore, TameworkNpcNameComponent> npcName = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkNpcNameComponent.class, "TameworkNpcName", TameworkNpcNameComponent.CODEC);
        ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplate = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkMountedNameplateComponent.class, "TameworkMountedNameplate",
                        TameworkMountedNameplateComponent.CODEC);
        ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinks = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkCommandLinksComponent.class, "TameworkCommandLinks",
                        TameworkCommandLinksComponent.CODEC);
        ComponentType<EntityStore, TameworkHappinessComponent> happiness = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkHappinessComponent.class, "TameworkHappiness",
                        TameworkHappinessComponent.CODEC);
        ComponentType<EntityStore, TameworkNeedsComponent> needs = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkNeedsComponent.class, "TameworkNeeds", TameworkNeedsComponent.CODEC);
        ComponentType<EntityStore, TameworkBreedingComponent> breeding = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkBreedingComponent.class, "TameworkBreeding", TameworkBreedingComponent.CODEC);
        ComponentType<EntityStore, TameworkAlarmComponent> alarm = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkAlarmComponent.class, "TameworkAlarm", TameworkAlarmComponent.CODEC);
        ComponentType<EntityStore, TameworkFlyingCompanionComponent> flyingCompanion = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkFlyingCompanionComponent.class, "TameworkFlyingCompanion",
                        TameworkFlyingCompanionComponent.CODEC);
        ComponentType<EntityStore, TameworkRideMountComponent> rideMount = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkRideMountComponent.class, "TameworkRideMount", TameworkRideMountComponent.CODEC);
        ComponentType<EntityStore, TameworkRideRiderComponent> rideRider = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkRideRiderComponent.class, "TameworkRideRider", TameworkRideRiderComponent.CODEC);
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlide = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkMountedGlideComponent.class, "TameworkMountedGlide",
                        TameworkMountedGlideComponent.CODEC);
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> mountedGlideRider =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkMountedGlideRiderComponent.class,
                        "TameworkMountedGlideRider",
                        TameworkMountedGlideRiderComponent.CODEC
                );
        ComponentType<EntityStore, AvatarFlightComponent> avatarFlight = plugin.getEntityStoreRegistry()
                .registerComponent(AvatarFlightComponent.class, "TameworkAvatarFlight", AvatarFlightComponent.CODEC);
        ComponentType<EntityStore, AvatarFlightInputComponent> avatarFlightInput = plugin.getEntityStoreRegistry()
                .registerComponent(AvatarFlightInputComponent.class, "TameworkAvatarFlightInput",
                        AvatarFlightInputComponent.CODEC);
        ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisual =
                plugin.getEntityStoreRegistry().registerComponent(
                        AvatarFlightRiderVisualComponent.class,
                        "TameworkAvatarFlightRiderVisual",
                        AvatarFlightRiderVisualComponent.CODEC
                );
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> avatarFlightMountSession =
                plugin.getEntityStoreRegistry().registerComponent(
                        AvatarFlightMountSessionComponent.class,
                        "TameworkAvatarFlightMountSession",
                        AvatarFlightMountSessionComponent.CODEC
                );
        ComponentType<EntityStore, AvatarFlightSourceComponent> avatarFlightSource =
                plugin.getEntityStoreRegistry().registerComponent(
                        AvatarFlightSourceComponent.class,
                        "TameworkAvatarFlightSource",
                        AvatarFlightSourceComponent.CODEC
                );
        ComponentType<EntityStore, TameworkLevelingComponent> leveling = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkLevelingComponent.class, "TameworkLeveling", TameworkLevelingComponent.CODEC);
        ComponentType<EntityStore, TameworkTraitsComponent> traits = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkTraitsComponent.class, "TameworkTraits", TameworkTraitsComponent.CODEC);
        ComponentType<EntityStore, TameworkTalentsComponent> talents = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkTalentsComponent.class, "TameworkTalents", TameworkTalentsComponent.CODEC);
        ComponentType<EntityStore, TameworkTranquilizerPeakComponent> tranquilizerPeak =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkTranquilizerPeakComponent.class,
                        "TameworkTranquilizerPeak",
                        TameworkTranquilizerPeakComponent.CODEC
                );
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachments = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkAttachmentsComponent.class, "TameworkAttachments",
                        TameworkAttachmentsComponent.CODEC);
        ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachments =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkDynamicAttachmentsComponent.class,
                        "TameworkDynamicAttachments",
                        TameworkDynamicAttachmentsComponent.CODEC
                );
        ComponentType<EntityStore, TameworkLifeStageComponent> lifeStage = plugin.getEntityStoreRegistry()
                .registerComponent(TameworkLifeStageComponent.class, "TameworkLifeStage", TameworkLifeStageComponent.CODEC);
        ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> projectileImpactEffect =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkProjectileImpactEffectComponent.class,
                        "TameworkProjectileImpactEffect",
                        TameworkProjectileImpactEffectComponent.CODEC
                );
        ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringHazardProjectile =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkLingeringHazardProjectileComponent.class,
                        "TameworkLingeringHazardProjectile",
                        TameworkLingeringHazardProjectileComponent.CODEC
                );
        ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazard =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkLingeringHazardComponent.class,
                        "TameworkLingeringHazard",
                        TameworkLingeringHazardComponent.CODEC
                );
        ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> apiSelfTestFixtureMarker =
                plugin.getEntityStoreRegistry().registerComponent(
                        ApiSelfTestFixtureMarkerComponent.class,
                        "TameworkApiSelfTestFixture",
                        ApiSelfTestFixtureMarkerComponent.CODEC
                );
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentity =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkProjectionIdentityComponent.class,
                        "TameworkProjectionIdentity",
                        TameworkProjectionIdentityComponent.CODEC
                );
        ComponentType<EntityStore, TameworkPersistenceRetirementComponent> persistenceRetirement =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkPersistenceRetirementComponent.class,
                        "TameworkPersistenceRetirement",
                        TameworkPersistenceRetirementComponent.CODEC
                );
        ComponentType<EntityStore, TameworkCaptureSourceReceiptsComponent> captureSourceReceipts =
                plugin.getEntityStoreRegistry().registerComponent(
                        TameworkCaptureSourceReceiptsComponent.class,
                        "TameworkCaptureSourceReceipts",
                        TameworkCaptureSourceReceiptsComponent.CODEC
                );
        ComponentType<EntityStore, HomingVisualProjectileComponent> homingVisualProjectile =
                plugin.getEntityStoreRegistry().registerComponent(
                        HomingVisualProjectileComponent.class,
                        "TameworkHomingVisualProjectile",
                        HomingVisualProjectileComponent.CODEC
                );
        ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> feedTroughWaterCharges =
                plugin.getChunkStoreRegistry().registerComponent(
                        TameworkFeedTroughWaterChargesComponent.class,
                        "TameworkFeedTroughWaterCharges",
                        TameworkFeedTroughWaterChargesComponent.CODEC
                );
        ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> coopCaptureReceipts =
                plugin.getChunkStoreRegistry().registerComponent(
                        TameworkCoopCaptureReceiptsComponent.class,
                        "TameworkCoopCaptureReceipts",
                        TameworkCoopCaptureReceiptsComponent.CODEC
                );
        return new RegisteredComponents(
                owner, tamed, hook, npcName, mountedNameplate, commandLinks, happiness, needs,
                breeding, alarm, flyingCompanion, rideMount, rideRider, mountedGlide,
                mountedGlideRider, avatarFlight, avatarFlightInput, avatarFlightRiderVisual,
                avatarFlightMountSession, avatarFlightSource,
                leveling, traits, talents, tranquilizerPeak, attachments, dynamicAttachments,
                lifeStage, projectileImpactEffect, lingeringHazardProjectile, lingeringHazard,
                apiSelfTestFixtureMarker, projectionIdentity, persistenceRetirement,
                captureSourceReceipts, homingVisualProjectile, feedTroughWaterCharges,
                coopCaptureReceipts
        );
    }

    record RegisteredComponents(
            ComponentType<EntityStore, TameworkOwnerComponent> owner,
            ComponentType<EntityStore, TameworkTamedComponent> tamed,
            ComponentType<EntityStore, TameworkHookComponent> hook,
            ComponentType<EntityStore, TameworkNpcNameComponent> npcName,
            ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplate,
            ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinks,
            ComponentType<EntityStore, TameworkHappinessComponent> happiness,
            ComponentType<EntityStore, TameworkNeedsComponent> needs,
            ComponentType<EntityStore, TameworkBreedingComponent> breeding,
            ComponentType<EntityStore, TameworkAlarmComponent> alarm,
            ComponentType<EntityStore, TameworkFlyingCompanionComponent> flyingCompanion,
            ComponentType<EntityStore, TameworkRideMountComponent> rideMount,
            ComponentType<EntityStore, TameworkRideRiderComponent> rideRider,
            ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlide,
            ComponentType<EntityStore, TameworkMountedGlideRiderComponent> mountedGlideRider,
            ComponentType<EntityStore, AvatarFlightComponent> avatarFlight,
            ComponentType<EntityStore, AvatarFlightInputComponent> avatarFlightInput,
            ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisual,
            ComponentType<EntityStore, AvatarFlightMountSessionComponent> avatarFlightMountSession,
            ComponentType<EntityStore, AvatarFlightSourceComponent> avatarFlightSource,
            ComponentType<EntityStore, TameworkLevelingComponent> leveling,
            ComponentType<EntityStore, TameworkTraitsComponent> traits,
            ComponentType<EntityStore, TameworkTalentsComponent> talents,
            ComponentType<EntityStore, TameworkTranquilizerPeakComponent> tranquilizerPeak,
            ComponentType<EntityStore, TameworkAttachmentsComponent> attachments,
            ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachments,
            ComponentType<EntityStore, TameworkLifeStageComponent> lifeStage,
            ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> projectileImpactEffect,
            ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringHazardProjectile,
            ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazard,
            ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> apiSelfTestFixtureMarker,
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentity,
            ComponentType<EntityStore, TameworkPersistenceRetirementComponent> persistenceRetirement,
            ComponentType<EntityStore, TameworkCaptureSourceReceiptsComponent> captureSourceReceipts,
            ComponentType<EntityStore, HomingVisualProjectileComponent> homingVisualProjectile,
            ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> feedTroughWaterCharges,
            ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> coopCaptureReceipts) {
    }
}
