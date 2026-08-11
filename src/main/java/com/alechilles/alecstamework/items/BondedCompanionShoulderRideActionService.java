package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.compat.HytaleMountedComponentAccess;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionShoulderRideSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Revalidates and toggles the built-in NPC-on-player mount used by shoulder companions. */
final class BondedCompanionShoulderRideActionService {
    private final PlayerResolver players;
    private final ProfileResolver profiles;
    private final MountOperator mounts;

    BondedCompanionShoulderRideActionService(PlayerResolver players,
                                             ProfileResolver profiles) {
        this(players, profiles, new HytaleMountOperator());
    }

    BondedCompanionShoulderRideActionService(PlayerResolver players,
                                             ProfileResolver profiles,
                                             MountOperator mounts) {
        this.players = players;
        this.profiles = profiles;
        this.mounts = mounts;
    }

    boolean toggle(@Nonnull UUID ownerUuid,
                   @Nonnull Ref<EntityStore> eventPlayerRef,
                   @Nonnull Store<EntityStore> eventStore,
                   @Nonnull BondedCompanionPanelPresentation row) {
        if (!isAvailable(row)
                || row.status().state() != BondedCompanionStateView.ACTIVE) {
            return false;
        }
        Player player = players.resolve(ownerUuid, eventPlayerRef, eventStore);
        BondedCompanionProfileView profile = profiles.resolve(ownerUuid,
                row.rosterId(), row.profileId());
        if (!matchesLiveProfile(ownerUuid, player, row, profile)) {
            return false;
        }
        UUID npcUuid = parseUuid(row.attributes().get(
                BondedCompanionPresentationAttributes.LIVE_NPC_UUID));
        if (npcUuid == null || !npcUuid.equals(
                profile.activeLease().liveNpcUuid()) || player.getWorld() == null) {
            return false;
        }
        try {
            Ref<EntityStore> npcRef = player.getWorld().getEntityRef(npcUuid);
            if (npcRef == null || !npcRef.isValid()
                    || npcRef.getStore() != eventStore) {
                return false;
            }
            String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, eventStore);
            TwCompanionShoulderRideSettings settings = roleId == null ? null
                    : TwCompanionConfig.resolveEffectiveForRole(roleId)
                    .getShoulderRide();
            if (mounts.isAttached(npcRef, eventPlayerRef, eventStore)) {
                return mounts.toggle(npcRef, eventPlayerRef, eventStore,
                        settings, ownerUuid);
            }
            return settings != null && settings.isConfigured()
                    && mounts.toggle(npcRef, eventPlayerRef, eventStore,
                    settings, ownerUuid);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isAvailable(@Nullable BondedCompanionPanelPresentation row) {
        return row != null && Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE));
    }

    private static boolean matchesLiveProfile(
            UUID ownerUuid, @Nullable Player player,
            BondedCompanionPanelPresentation row,
            @Nullable BondedCompanionProfileView profile) {
        return player != null && profile != null
                && ownerUuid.equals(profile.ownerUuid())
                && row.rosterId().equals(profile.rosterId())
                && row.profileId().equals(profile.profileId())
                && row.roleId().equals(profile.roleId())
                && profile.state() == BondedCompanionStateView.ACTIVE
                && profile.activeLease() != null
                && (player.getWorld() == null
                || profile.activeLease().worldKey().equals(
                player.getWorld().getName()));
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    interface PlayerResolver {
        @Nullable Player resolve(UUID ownerUuid, Ref<EntityStore> playerRef,
                                 Store<EntityStore> store);
    }

    @FunctionalInterface
    interface ProfileResolver {
        @Nullable BondedCompanionProfileView resolve(UUID ownerUuid,
                                                      String rosterId,
                                                      String profileId);
    }

    interface MountOperator {
        boolean isAttached(Ref<EntityStore> npcRef,
                           Ref<EntityStore> playerRef,
                           Store<EntityStore> store);

        boolean toggle(Ref<EntityStore> npcRef, Ref<EntityStore> playerRef,
                       Store<EntityStore> store,
                       TwCompanionShoulderRideSettings settings,
                       UUID ownerUuid);
    }

    static final class HytaleMountOperator implements MountOperator {
        @Override
        public boolean isAttached(Ref<EntityStore> npcRef,
                                  Ref<EntityStore> playerRef,
                                  Store<EntityStore> store) {
            var markerType = TameworkShoulderRideComponent.getComponentType();
            var mountedType = MountedComponent.getComponentType();
            if (markerType == null || mountedType == null
                    || store.getComponent(npcRef, markerType) == null) return false;
            MountedComponent mounted = store.getComponent(npcRef, mountedType);
            return mounted != null
                    && playerRef.equals(mounted.getMountedToEntity());
        }

        @Override
        public boolean toggle(Ref<EntityStore> npcRef,
                              Ref<EntityStore> playerRef,
                              Store<EntityStore> store,
                              TwCompanionShoulderRideSettings settings,
                              UUID ownerUuid) {
            var mountedType = MountedComponent.getComponentType();
            var markerType = TameworkShoulderRideComponent.getComponentType();
            if (mountedType == null || markerType == null) return false;
            MountedComponent mounted = store.getComponent(npcRef, mountedType);
            if (mounted != null) {
                if (!isAttached(npcRef, playerRef, store)) return false;
                return queueToggle(npcRef, store, settings, ownerUuid);
            }
            TameworkShoulderRideComponent marker =
                    store.getComponent(npcRef, markerType);
            if (marker != null) {
                return marker.hasCapturedState()
                        && ownerUuid.equals(marker.getOwnerUuid())
                        && queueToggle(npcRef, store, settings, ownerUuid);
            }
            var mountedByType = MountedByComponent.getComponentType();
            MountedByComponent mountedBy = mountedByType == null ? null
                    : store.getComponent(playerRef, mountedByType);
            if (mountedBy != null && !mountedBy.getPassengers().isEmpty()) {
                return false;
            }
            return queueToggle(npcRef, store, settings, ownerUuid);
        }

        private boolean queueToggle(Ref<EntityStore> npcRef,
                                    Store<EntityStore> store,
                                    TwCompanionShoulderRideSettings settings,
                                    UUID ownerUuid) {
            World world = store.getExternalData() == null ? null
                    : store.getExternalData().getWorld();
            UUIDComponent uuid = store.getComponent(npcRef,
                    UUIDComponent.getComponentType());
            if (world == null || uuid == null) return false;
            UUID npcUuid = uuid.getUuid();
            world.execute(() -> toggleOnWorld(world, npcUuid, ownerUuid,
                    settings));
            return true;
        }

        private void toggleOnWorld(World world, UUID npcUuid, UUID ownerUuid,
                                   TwCompanionShoulderRideSettings settings) {
            Ref<EntityStore> liveNpc = world.getEntityRef(npcUuid);
            Ref<EntityStore> livePlayer = world.getEntityRef(ownerUuid);
            if (liveNpc == null || !liveNpc.isValid() || livePlayer == null
                    || !livePlayer.isValid()) return;
            Store<EntityStore> liveStore = liveNpc.getStore();
            var mountedType = MountedComponent.getComponentType();
            var markerType = TameworkShoulderRideComponent.getComponentType();
            MountedComponent mounted = liveStore.getComponent(liveNpc,
                    mountedType);
            TameworkShoulderRideComponent marker = liveStore.getComponent(
                    liveNpc, markerType);
            if (mounted != null) {
                if (!ownsRide(marker, mounted, livePlayer, ownerUuid)) return;
                liveStore.tryRemoveComponent(liveNpc, mountedType);
                return;
            }
            if (settings == null || !settings.isConfigured()) return;
            if (marker != null) {
                if (marker.hasCapturedState()
                        && ownerUuid.equals(marker.getOwnerUuid())) {
                    mount(liveNpc, livePlayer, liveStore, mountedType, settings);
                }
                return;
            }
            NPCEntity npc = liveStore.getComponent(liveNpc,
                    NPCEntity.getComponentType());
            if (npc == null || npc.getRole() == null
                    || liveStore.getComponent(liveNpc,
                    DeathComponent.getComponentType()) != null) return;
            boolean wasInteractable = liveStore.getComponent(liveNpc,
                    Interactable.getComponentType()) != null;
            boolean wasIntangible = liveStore.getComponent(liveNpc,
                    Intangible.getComponentType()) != null;
            boolean wasInvulnerable = liveStore.getComponent(liveNpc,
                    Invulnerable.getComponentType()) != null;
            boolean wasFrozen = liveStore.getComponent(liveNpc,
                    Frozen.getComponentType()) != null;
            liveStore.putComponent(liveNpc, markerType,
                    new TameworkShoulderRideComponent(ownerUuid,
                            wasInteractable, wasIntangible,
                            wasInvulnerable, wasFrozen));
            liveStore.ensureAndGetComponent(liveNpc,
                    Frozen.getComponentType());
            liveStore.ensureAndGetComponent(liveNpc,
                    Intangible.getComponentType());
            liveStore.ensureAndGetComponent(liveNpc,
                    Invulnerable.getComponentType());
            liveStore.tryRemoveComponent(liveNpc,
                    Interactable.getComponentType());
            npc.playAnimation(liveNpc, AnimationSlot.Movement, null,
                    liveStore);
            mount(liveNpc, livePlayer, liveStore, mountedType, settings);
        }

        private boolean ownsRide(TameworkShoulderRideComponent marker,
                                 MountedComponent mounted,
                                 Ref<EntityStore> playerRef,
                                 UUID ownerUuid) {
            return marker != null && ownerUuid.equals(marker.getOwnerUuid())
                    && playerRef.equals(mounted.getMountedToEntity());
        }

        private void mount(Ref<EntityStore> npcRef,
                           Ref<EntityStore> playerRef,
                           Store<EntityStore> store,
                           ComponentType<EntityStore, MountedComponent> mountedType,
                           TwCompanionShoulderRideSettings settings) {
            TameworkShoulderRideComponent marker = store.getComponent(npcRef,
                    TameworkShoulderRideComponent.getComponentType());
            if (marker != null) {
                marker.setVerticalOffsets(settings.getOffsetY(),
                        settings.getCrouchOffsetY());
                store.putComponent(npcRef,
                        TameworkShoulderRideComponent.getComponentType(), marker);
            }
            double offsetY = settings.getOffsetY()
                    + (isCrouching(playerRef, store)
                    ? settings.getCrouchOffsetY() : 0D);
            store.putComponent(npcRef, mountedType,
                    HytaleMountedComponentAccess.createEntityMount(
                            playerRef,
                            (float) settings.getOffsetX(),
                            (float) offsetY,
                            (float) settings.getOffsetZ(),
                            MountController.Minecart));
        }

        private boolean isCrouching(Ref<EntityStore> playerRef,
                                    Store<EntityStore> store) {
            MovementStatesComponent component = store.getComponent(playerRef,
                    MovementStatesComponent.getComponentType());
            MovementStates states = component == null
                    ? null : component.getMovementStates();
            return states != null && (states.crouching
                    || states.forcedCrouching);
        }
    }
}
