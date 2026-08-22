package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.CareCreditOutcomeView;
import com.alechilles.alecstamework.api.CombatContributionView;
import com.alechilles.alecstamework.api.CombatParticipantView;
import com.alechilles.alecstamework.api.CompanionXpOutcomeView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.damage.CompanionCombatActivityPublisher;
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
    private static final RuntimeState UNAVAILABLE = new RuntimeState(
            null, null, null, null, null, null);
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
                new TameActivityPublisher(publisher, managedActivities),
                new LifecycleActivityPublisher(publisher, managedActivities),
                new AvatarFlightActivityPublisher(publisher),
                new CompanionCombatActivityPublisher(publisher),
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
                new TameActivityPublisher(publisher, managedActivities),
                new LifecycleActivityPublisher(publisher, managedActivities),
                new AvatarFlightActivityPublisher(publisher),
                new CompanionCombatActivityPublisher(publisher),
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
                careCredit && companionId != null && ownerId != null
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

    /** Publishes one committed autonomous need change. */
    public static void publishNeedSatisfied(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nonnull String needType,
            @Nonnull String resourceSource,
            @Nonnull String resourceId,
            double previousValue,
            double currentValue,
            double restoredAmount,
            @Nullable AwardResult award,
            boolean careCredit
    ) {
        RuntimeState state = CURRENT.get();
        if (state.publisher == null) {
            return;
        }
        state.publisher.publishNeedSatisfied(
                operationId,
                roleId,
                ownerId,
                companionId,
                needType,
                resourceSource,
                resourceId,
                previousValue,
                currentValue,
                restoredAmount,
                transition(award),
                careCredit && companionId != null && ownerId != null
                        ? new CareCreditOutcomeView(companionId, ownerId)
                        : null
        );
    }

    /** Publishes one committed wild-to-tamed acquisition. */
    public static void publishTame(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        RuntimeState state = CURRENT.get();
        if (state.tamePublisher != null) {
            state.tamePublisher.publish(
                    operationId, roleId, ownerId, companionId);
        }
    }

    /** Publishes one post-commit revival activity. */
    public static void publishRevival(
            @Nonnull UUID operationId,
            @Nullable UUID actorId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable String profileId,
            @Nullable String source,
            @Nullable String lifecycleState,
            @Nullable String paymentOutcome,
            boolean recovered,
            long occurredAtMs
    ) {
        publishRevival(
                operationId, actorId, ownerId, companionId, null, profileId,
                source, lifecycleState, paymentOutcome, recovered, occurredAtMs
        );
    }

    /** Publishes one post-commit revival activity with managed-role context. */
    public static void publishRevival(
            @Nonnull UUID operationId,
            @Nullable UUID actorId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable String roleId,
            @Nullable String profileId,
            @Nullable String source,
            @Nullable String lifecycleState,
            @Nullable String paymentOutcome,
            boolean recovered,
            long occurredAtMs
    ) {
        RuntimeState state = CURRENT.get();
        if (state.lifecyclePublisher != null) {
            state.lifecyclePublisher.publishRevival(
                    operationId, actorId, ownerId, companionId, roleId, profileId,
                    source, lifecycleState, paymentOutcome, recovered,
                    occurredAtMs);
        }
    }

    /** Publishes one post-commit summoning lifecycle activity. */
    public static void publishSummoning(
            @Nonnull UUID operationId,
            @Nonnull String actionId,
            @Nullable UUID ownerId,
            @Nullable String profileId,
            @Nullable String commandFamilyId,
            @Nullable UUID companionId,
            @Nullable String lifecycleSource,
            @Nullable Long expiresAtMs,
            long occurredAtMs
    ) {
        RuntimeState state = CURRENT.get();
        if (state.lifecyclePublisher != null) {
            state.lifecyclePublisher.publishSummoning(
                    operationId, actionId, ownerId, profileId,
                    commandFamilyId, companionId, lifecycleSource,
                    expiresAtMs, occurredAtMs);
        }
    }

    /** Returns cached avatar-flight interest before optional identity reads. */
    public static boolean hasAvatarFlightInterest(@Nonnull String actionId) {
        AvatarFlightActivityPublisher publisher =
                CURRENT.get().avatarFlightPublisher;
        return publisher != null && publisher.hasInterest(actionId);
    }

    /** Publishes one accepted avatar-flight action. */
    public static void publishAvatarFlight(
            @Nonnull String actionId,
            @Nullable UUID playerId,
            @Nullable String flightConfigId,
            @Nullable String abilitySlot,
            @Nullable String rootInteractionId
    ) {
        AvatarFlightActivityPublisher publisher =
                CURRENT.get().avatarFlightPublisher;
        if (publisher != null) {
            publisher.publish(
                    actionId, playerId, flightConfigId,
                    abilitySlot, rootInteractionId);
        }
    }

    /** Returns cached combat interest before optional entity reads. */
    public static boolean hasCombatInterest(@Nonnull String actionId) {
        CompanionCombatActivityPublisher publisher =
                CURRENT.get().combatPublisher;
        return publisher != null && publisher.hasInterest(actionId);
    }

    /** Publishes one final combat damage packet. */
    public static void publishCombatDamage(
            @Nonnull UUID operationId,
            @Nonnull CombatParticipantView source,
            @Nonnull CombatParticipantView target,
            double finalDamage,
            @Nonnull String damageType,
            @Nullable CompanionXpOutcomeView sourceXpOutcome,
            @Nullable CompanionXpOutcomeView targetXpOutcome,
            long occurredAtMs
    ) {
        CompanionCombatActivityPublisher publisher =
                CURRENT.get().combatPublisher;
        if (publisher != null) {
            publisher.publishDamage(
                    operationId, source, target, finalDamage, damageType,
                    sourceXpOutcome, targetXpOutcome, occurredAtMs);
        }
    }

    /** Publishes one confirmed combat defeat. */
    public static void publishCombatDefeat(
            @Nonnull UUID operationId,
            @Nonnull CombatParticipantView target,
            @Nullable CombatContributionView finalBlowCredit,
            @Nonnull List<CombatContributionView> contributors,
            @Nullable UUID ownerCredit,
            long occurredAtMs
    ) {
        CompanionCombatActivityPublisher publisher =
                CURRENT.get().combatPublisher;
        if (publisher != null) {
            publisher.publishDefeat(
                    operationId, target, finalBlowCredit, contributors,
                    ownerCredit, occurredAtMs);
        }
    }

    @Nullable
    private static CompanionXpTransition transition(@Nullable AwardResult award) {
        return award == null ? null : award.transition();
    }

    private record RuntimeState(
            @Nullable ManagedActivityPublisher publisher,
            @Nullable TameActivityPublisher tamePublisher,
            @Nullable LifecycleActivityPublisher lifecyclePublisher,
            @Nullable AvatarFlightActivityPublisher avatarFlightPublisher,
            @Nullable CompanionCombatActivityPublisher combatPublisher,
            @Nullable CompanionCareCreditService careCredits
    ) {
    }
}
