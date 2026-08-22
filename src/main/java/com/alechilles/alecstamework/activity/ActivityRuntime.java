package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.CareCreditOutcomeView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local owner of Activity API V2 managed producers. */
public final class ActivityRuntime {
    private static final RuntimeState UNAVAILABLE = new RuntimeState(null, null);
    private static final AtomicReference<RuntimeState> CURRENT =
            new AtomicReference<>(UNAVAILABLE);

    private ActivityRuntime() {
    }

    /** Installs the current replacement API publisher and managed resolver. */
    public static void install(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        CURRENT.set(new RuntimeState(
                new ManagedActivityPublisher(publisher, managedActivities),
                new CompanionCareCreditService()
        ));
    }

    /** Installs a deterministic care gate for focused producer tests. */
    static void installForTests(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities,
            @Nonnull CompanionCareCreditService careCredits
    ) {
        CURRENT.set(new RuntimeState(
                new ManagedActivityPublisher(publisher, managedActivities),
                Objects.requireNonNull(careCredits, "careCredits")
        ));
    }

    /** Clears the runtime before the feed and replacement API close. */
    public static void clear() {
        CURRENT.set(UNAVAILABLE);
    }

    /** Resolves the stable owner ID from the current world-thread entity. */
    @Nullable
    public static UUID resolveOwnerId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var ownerType = com.alechilles.alecstamework.npc.components
                .TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return null;
        }
        var owner = store.getComponent(npcRef, ownerType);
        return owner == null ? null : owner.getOwnerId();
    }

    /** Resolves the stable companion ID from the current world-thread entity. */
    @Nullable
    public static UUID resolveCompanionId(
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

    /** Returns whether the shared per-companion care credit can be consumed. */
    public static boolean tryAcquireCareCredit(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        RuntimeState state = CURRENT.get();
        return state.careCredits != null
                && state.careCredits.tryAcquire(npcRef, store);
    }

    /** Publishes one feed activity after the feed mutation succeeds. */
    public static void publishFeed(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable AwardResult award,
            boolean careCredit
    ) {
        RuntimeState state = CURRENT.get();
        if (state.publisher == null) {
            return;
        }
        state.publisher.publishFeed(
                operationId,
                roleId,
                ownerId,
                companionId,
                Map.of(),
                transition(award),
                careCredit && companionId != null
                        ? new CareCreditOutcomeView(companionId, ownerId)
                        : null
        );
    }

    /** Compatibility convenience for producers without an XP outcome. */
    public static void publishFeed(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        publishFeed(operationId, roleId, ownerId, companionId, null, false);
    }

    /** Publishes one harvest activity after an output or state mutation succeeds. */
    public static void publishHarvest(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable String harvestContext,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable Map<String, Integer> itemQuantities,
            @Nullable AwardResult award
    ) {
        RuntimeState state = CURRENT.get();
        if (state.publisher != null) {
            state.publisher.publishHarvest(
                    operationId,
                    roleId,
                    harvestContext,
                    ownerId,
                    companionId,
                    itemQuantities,
                    transition(award)
            );
        }
    }

    /** Publishes one settled breeding activity with exact offspring receipts. */
    public static void publishBreeding(
            @Nonnull UUID litterId,
            @Nullable String parentARoleId,
            @Nullable UUID parentAOwnerId,
            @Nullable UUID parentACompanionId,
            @Nullable String parentBRoleId,
            @Nullable UUID parentBOwnerId,
            @Nullable UUID parentBCompanionId,
            @Nonnull List<UUID> offspringIds
    ) {
        RuntimeState state = CURRENT.get();
        if (state.publisher != null) {
            state.publisher.publishBreeding(
                    litterId,
                    parentARoleId,
                    parentAOwnerId,
                    parentACompanionId,
                    parentBRoleId,
                    parentBOwnerId,
                    parentBCompanionId,
                    offspringIds
            );
        }
    }

    @Nullable
    private static CompanionXpTransition transition(@Nullable AwardResult award) {
        return award == null ? null : award.transition();
    }

    private record RuntimeState(
            @Nullable ManagedActivityPublisher publisher,
            @Nullable CompanionCareCreditService careCredits
    ) {
    }
}
