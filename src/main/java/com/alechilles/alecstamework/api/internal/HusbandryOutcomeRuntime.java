package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the live husbandry provider and builds authoritative action context. */
public final class HusbandryOutcomeRuntime {
    private static final HusbandryOutcomeApi UNAVAILABLE = HusbandryOutcomeApi.unavailable();
    private static final AtomicReference<HusbandryOutcomeApi> CURRENT =
            new AtomicReference<>(UNAVAILABLE);

    private HusbandryOutcomeRuntime() {
    }

    /** Installs one live API facade for authoritative Tamework actions. */
    static void install(@Nonnull HusbandryOutcomeApi api) {
        CURRENT.set(api);
    }

    /** Clears the live facade only when it is still the supplied API instance. */
    static void clear(@Nullable HusbandryOutcomeApi expected) {
        if (expected != null) {
            CURRENT.compareAndSet(expected, UNAVAILABLE);
        }
    }

    /** Resolves one already-built context with fail-closed provider handling. */
    @Nonnull
    public static HusbandryOutcomeModifiers resolve(
            @Nullable HusbandryOutcomeContext context
    ) {
        if (context == null) {
            return HusbandryOutcomeModifiers.identity();
        }
        try {
            HusbandryOutcomeModifiers result = CURRENT.get().resolve(context);
            return result == null ? HusbandryOutcomeModifiers.identity() : result;
        } catch (Throwable ignored) {
            return HusbandryOutcomeModifiers.identity();
        }
    }

    /** Resolves modifiers for one role-backed world-thread action. */
    @Nonnull
    public static HusbandryOutcomeModifiers resolve(
            @Nonnull HusbandryOutcomeKind kind,
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nullable Role role,
            @Nullable String productId
    ) {
        return resolve(kind, npcRef, store,
                role == null ? null : role.getRoleName(), productId);
    }

    /** Resolves modifiers for one role-id-backed world-thread action. */
    @Nonnull
    public static HusbandryOutcomeModifiers resolve(
            @Nonnull HusbandryOutcomeKind kind,
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable String productId
    ) {
        try {
            return resolve(buildContext(kind, npcRef, store, roleId, productId));
        } catch (Throwable ignored) {
            return HusbandryOutcomeModifiers.identity();
        }
    }

    @Nonnull
    private static HusbandryOutcomeContext buildContext(
            @Nonnull HusbandryOutcomeKind kind,
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nullable String fallbackRoleId,
            @Nullable String productId
    ) {
        String roleId = resolveRoleId(npcRef, store, fallbackRoleId);
        ManagedActivityConfigRegistry.RoleResolution managed = resolveManagedRole(roleId);
        String profileId = managed == null ? null : managed.profile().profileId();
        Set<String> groupIds = managed == null || managed.family() == null
                ? Set.of()
                : Set.of(managed.family().groupId());
        return new HusbandryOutcomeContext(
                kind,
                ActivityRuntime.resolveOwnerId(npcRef, store),
                ActivityRuntime.resolveCompanionId(npcRef, store),
                roleId,
                profileId,
                groupIds,
                productId
        );
    }

    @Nullable
    private static ManagedActivityConfigRegistry.RoleResolution resolveManagedRole(
            @Nullable String roleId
    ) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || roleId == null || roleId.isBlank()) {
            return null;
        }
        ManagedActivityConfigRegistry registry = plugin.getManagedActivityConfigRegistry();
        return registry == null ? null : registry.resolveRole(roleId).orElse(null);
    }

    @Nullable
    private static String resolveRoleId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nullable String fallbackRoleId
    ) {
        if (npcRef != null && npcRef.isValid() && store != null) {
            var npcType = NPCEntity.getComponentType();
            NPCEntity npc = npcType == null ? null : store.getComponent(npcRef, npcType);
            if (npc != null && npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
                return npc.getRoleName();
            }
        }
        return fallbackRoleId == null || fallbackRoleId.isBlank()
                ? null : fallbackRoleId.trim();
    }
}
