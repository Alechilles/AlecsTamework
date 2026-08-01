package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Revalidates a bonded card's live flight capability before sending its
 * configured hook. The page snapshot is a hint only; this boundary never
 * mutates controller or presentation state itself.
 */
final class BondedCompanionFlightToggleActionService {
    private final LiveResolver resolver;
    private final HookDispatcher hooks;
    private final BiFunction<NPCEntity, TwCompanionFlightToggleSettings,
            Optional<Boolean>> modes;
    private final PlayerResolver players;
    private final ProfileResolver profiles;

    BondedCompanionFlightToggleActionService() {
        this(new HytaleLiveResolver(), new CommandNpcHookDispatchService()::dispatch,
                new BondedCompanionFlightModeReader()::read,
                BondedCompanionPanelActionRouter::resolvePlayerFromEvent,
                (owner, roster, profile) -> null);
    }

    BondedCompanionFlightToggleActionService(LiveResolver resolver,
                                             HookDispatcher hooks,
                                             BiFunction<NPCEntity,
                                                     TwCompanionFlightToggleSettings,
                                                     Optional<Boolean>> modes,
                                             PlayerResolver players,
                                             ProfileResolver profiles) {
        this.resolver = resolver;
        this.hooks = hooks;
        this.modes = modes;
        this.players = players;
        this.profiles = profiles;
    }

    boolean toggle(@Nonnull UUID ownerUuid,
                   @Nonnull Ref<EntityStore> eventPlayerRef,
                   @Nonnull Store<EntityStore> eventStore,
                   @Nullable String itemId,
                   @Nonnull BondedCompanionPanelPresentation row) {
        if (ownerUuid == null || eventPlayerRef == null || eventStore == null
                || row == null || !isFlightToggleAvailable(row)
                || row.status().state() != BondedCompanionStateView.ACTIVE) {
            return false;
        }
        Player player = players.resolve(ownerUuid, eventPlayerRef, eventStore);
        if (player == null) return false;
        BondedCompanionProfileView profile = profiles.resolve(ownerUuid,
                row.rosterId(), row.profileId());
        if (profile == null || !ownerUuid.equals(profile.ownerUuid())
                || !row.rosterId().equals(profile.rosterId())
                || !row.profileId().equals(profile.profileId())
                || !row.roleId().equals(profile.roleId())
                || profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null
                || player.getWorld() != null && !profile.activeLease().worldKey()
                .equals(player.getWorld().getName())) {
            return false;
        }
        UUID liveNpcUuid = parseUuid(row.attributes().get(
                BondedCompanionPresentationAttributes.LIVE_NPC_UUID));
        if (liveNpcUuid == null || !liveNpcUuid.equals(
                profile.activeLease().liveNpcUuid())) return false;
        try {
            Ref<EntityStore> npcRef = resolver.reference(player, liveNpcUuid);
            if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != eventStore) {
                return false;
            }
            NPCEntity npc = resolver.npc(npcRef, eventStore);
            if (npc == null) return false;
            String roleId = resolver.roleId(npcRef, eventStore);
            if (roleId == null) return false;
            TwCompanionFlightToggleSettings settings = resolver.settings(roleId);
            if (settings == null || !settings.isConfigured()) return false;
            Optional<Boolean> currentMode = modes.apply(npc, settings);
            if (currentMode == null || currentMode.isEmpty()) return false;
            return hooks.dispatch(settings.getHookId(), player, itemId, npcRef,
                    eventStore, null);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isFlightToggleAvailable(
            @Nullable BondedCompanionPanelPresentation row) {
        return row != null && "true".equalsIgnoreCase(row.attributes().get(
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE));
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    interface LiveResolver {
        @Nullable Ref<EntityStore> reference(Player player, UUID npcUuid);
        @Nullable NPCEntity npc(Ref<EntityStore> reference, Store<EntityStore> store);
        @Nullable String roleId(Ref<EntityStore> reference, Store<EntityStore> store);
        @Nullable TwCompanionFlightToggleSettings settings(String roleId);
    }

    @FunctionalInterface
    interface HookDispatcher {
        boolean dispatch(String hookId, Player player, String itemId,
                         Ref<EntityStore> npcRef, Store<EntityStore> store,
                         @Nullable org.joml.Vector3d targetPosition);
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

    static final class HytaleLiveResolver implements LiveResolver {
        @Override public Ref<EntityStore> reference(Player player, UUID npcUuid) {
            return player.getWorld() == null ? null
                    : player.getWorld().getEntityRef(npcUuid);
        }

        @Override public NPCEntity npc(Ref<EntityStore> reference,
                                       Store<EntityStore> store) {
            return NPCEntity.getComponentType() == null ? null
                    : store.getComponent(reference, NPCEntity.getComponentType());
        }

        @Override public String roleId(Ref<EntityStore> reference,
                                       Store<EntityStore> store) {
            return CompanionRoleIdResolver.resolveRoleId(reference, store);
        }

        @Override public TwCompanionFlightToggleSettings settings(String roleId) {
            return TwCompanionConfig.resolveEffectiveForRole(roleId).getFlightToggle();
        }
    }
}
