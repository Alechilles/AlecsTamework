package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** Revalidates a normal linked-panel flight-toggle request before dispatching its hook. */
final class CommandLinkedFlightToggleActionService {
    private final LiveResolver resolver;
    private final HookDispatcher hooks;
    private final BiFunction<NPCEntity, TwCompanionFlightToggleSettings,
            Optional<Boolean>> modes;

    CommandLinkedFlightToggleActionService() {
        this(new HytaleLiveResolver(), new CommandNpcHookDispatchService()::dispatch,
                new BondedCompanionFlightModeReader()::read);
    }

    CommandLinkedFlightToggleActionService(
            LiveResolver resolver,
            HookDispatcher hooks,
            BiFunction<NPCEntity, TwCompanionFlightToggleSettings,
                    Optional<Boolean>> modes
    ) {
        this.resolver = resolver;
        this.hooks = hooks;
        this.modes = modes;
    }

    boolean toggle(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                   Store<EntityStore> eventStore, String itemId, UUID npcUuid) {
        if (ownerUuid == null || eventPlayerRef == null || eventStore == null
                || itemId == null || itemId.isBlank() || npcUuid == null) return false;
        try {
            Player player = resolver.player(ownerUuid, eventPlayerRef, eventStore);
            if (player == null || !ownerUuid.equals(player.getUuid())) return false;
            Ref<EntityStore> npcRef = resolver.reference(player, npcUuid);
            if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != eventStore
                    || !resolver.linked(npcRef, ownerUuid, itemId, eventStore)) return false;
            NPCEntity npc = resolver.npc(npcRef, eventStore);
            String roleId = resolver.roleId(npcRef, eventStore);
            if (npc == null || roleId == null) return false;
            TwCompanionFlightToggleSettings settings = resolver.settings(roleId);
            if (settings == null || !settings.isConfigured()) return false;
            Optional<Boolean> mode = modes.apply(npc, settings);
            if (mode == null || mode.isEmpty()) return false;
            return hooks.dispatch(settings.getHookId(), player, itemId, npcRef,
                    eventStore, null);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    interface LiveResolver {
        @Nullable Player player(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                                Store<EntityStore> eventStore);
        @Nullable Ref<EntityStore> reference(Player player, UUID npcUuid);
        boolean linked(Ref<EntityStore> npcRef, UUID ownerUuid, String itemId,
                       Store<EntityStore> store);
        @Nullable NPCEntity npc(Ref<EntityStore> npcRef, Store<EntityStore> store);
        @Nullable String roleId(Ref<EntityStore> npcRef, Store<EntityStore> store);
        @Nullable TwCompanionFlightToggleSettings settings(String roleId);
    }

    @FunctionalInterface
    interface HookDispatcher {
        boolean dispatch(String hookId, Player player, String itemId,
                         Ref<EntityStore> npcRef, Store<EntityStore> store,
                         @Nullable org.joml.Vector3d targetPosition);
    }

    static final class HytaleLiveResolver implements LiveResolver {
        private final CommandLinkPolicyService links = new CommandLinkPolicyService();

        @Override public Player player(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                                       Store<EntityStore> eventStore) {
            return BondedCompanionPanelActionRouter.resolvePlayerFromEvent(
                    ownerUuid, eventPlayerRef, eventStore);
        }

        @Override public Ref<EntityStore> reference(Player player, UUID npcUuid) {
            return player.getWorld() == null ? null
                    : player.getWorld().getEntityRef(npcUuid);
        }

        @Override public boolean linked(Ref<EntityStore> npcRef, UUID ownerUuid,
                                        String itemId, Store<EntityStore> store) {
            return links.isLinkedToTool(npcRef, ownerUuid, itemId, store);
        }

        @Override public NPCEntity npc(Ref<EntityStore> npcRef, Store<EntityStore> store) {
            return NPCEntity.getComponentType() == null ? null
                    : store.getComponent(npcRef, NPCEntity.getComponentType());
        }

        @Override public String roleId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
            return CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }

        @Override public TwCompanionFlightToggleSettings settings(String roleId) {
            return TwCompanionConfig.resolveEffectiveForRole(roleId).getFlightToggle();
        }
    }
}
