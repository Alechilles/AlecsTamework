package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.SuccessfulActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.managed.ManagedActivityProfile;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small internal bridge from successful gameplay actions to the live feed. */
public final class SuccessfulActivityRuntime {
    private static final RuntimeState UNAVAILABLE = new RuntimeState(null, null);
    private static final AtomicReference<RuntimeState> CURRENT =
            new AtomicReference<>(UNAVAILABLE);

    private SuccessfulActivityRuntime() {
    }

    /** Installs the current composition's live publisher and managed resolver. */
    public static void install(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        CURRENT.set(new RuntimeState(
                Objects.requireNonNull(publisher, "publisher"),
                Objects.requireNonNull(managedActivities, "managedActivities")
        ));
    }

    /** Clears the publisher before the Tamework composition shuts down. */
    public static void clear() {
        CURRENT.set(UNAVAILABLE);
    }

    /** Publishes one successful owner-feed activity when the role is managed. */
    static void publishFeed(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        publish(
                operationId,
                roleId,
                ownerId,
                companionId,
                mapping -> mapping.feed()
        );
    }

    /** Publishes one successful manual-harvest activity when mapped. */
    static void publishHarvest(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable String harvestContext,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        if (harvestContext == null || harvestContext.isBlank()) {
            return;
        }
        publish(
                operationId,
                roleId,
                ownerId,
                companionId,
                mapping -> mapping.harvestContexts().get(harvestContext.trim())
        );
    }

    /** Publishes one settled non-empty breeding activity. */
    static void publishBreeding(
            @Nonnull UUID litterId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        publish(
                litterId,
                roleId,
                ownerId,
                companionId,
                mapping -> mapping.breedingSuccess()
        );
    }

    @Nullable
    static UUID resolveOwnerId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return null;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        return owner == null ? null : owner.getOwnerId();
    }

    @Nullable
    static UUID resolveCompanionId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var uuidType = UUIDComponent.getComponentType();
        if (uuidType != null) {
            UUIDComponent uuid = store.getComponent(npcRef, uuidType);
            if (uuid != null && uuid.getUuid() != null) {
                return uuid.getUuid();
            }
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc == null ? null : npc.getUuid();
    }

    private static void publish(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nonnull ActivityIdResolver activityIdResolver
    ) {
        if (roleId == null || roleId.isBlank()
                || ownerId == null || companionId == null) {
            return;
        }
        RuntimeState state = CURRENT.get();
        if (state.publisher == null || state.managedActivities == null) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution resolution =
                state.managedActivities.resolveRole(roleId.trim()).orElse(null);
        if (resolution == null) {
            return;
        }
        String activityId = activityIdResolver.resolve(
                resolution.profile().activities()
        );
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        SuccessfulActivityView activity = new SuccessfulActivityView(
                Objects.requireNonNull(operationId, "operationId"),
                0L,
                ownerId,
                companionId,
                resolution.roleId(),
                Set.of(resolution.family().groupId()),
                resolution.profile().profileId(),
                activityId,
                Map.of(),
                Instant.now()
        );
        try {
            state.publisher.publish(activity);
        } catch (RuntimeException | LinkageError ignored) {
            // Activity XP is ancillary to the completed gameplay action.
        }
    }

    @FunctionalInterface
    private interface ActivityIdResolver {
        @Nullable
        String resolve(@Nonnull ManagedActivityProfile.ActivityMapping mapping);
    }

    private record RuntimeState(
            @Nullable LiveActivityFeed.Publisher publisher,
            @Nullable ManagedActivityConfigRegistry managedActivities
    ) {
    }
}
