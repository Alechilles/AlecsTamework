package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityParticipantView;
import com.alechilles.alecstamework.api.CareCreditOutcomeView;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.api.NeedSatisfiedActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.managed.ManagedActivityProfile;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves managed profiles and builds the low-frequency typed activity
 * payloads used by the Activity API V2 runtime.
 */
public final class ManagedActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;
    private final ManagedActivityConfigRegistry managedActivities;

    public ManagedActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.managedActivities = Objects.requireNonNull(
                managedActivities, "managedActivities");
    }

    /** Publishes one committed feed activity. */
    public void publishFeed(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable Map<String, Integer> itemQuantities,
            @Nullable CompanionXpTransition xpTransition,
            @Nullable CareCreditOutcomeView careCredit
    ) {
        if (ownerId == null || companionId == null) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution resolution =
                resolveRole(roleId, companionId);
        if (resolution == null) {
            return;
        }
        publish(
                operationId,
                ActivityIds.FEED,
                resolution.profile(),
                groupIds(resolution),
                List.of(participant(resolution, ownerId, companionId)),
                resolution.profile().activities().feed(),
                itemQuantities,
                List.of(),
                xpTransition,
                careCredit
        );
    }

    /** Publishes one committed harvest activity and its item outcomes. */
    public void publishHarvest(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable String harvestContext,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable Map<String, Integer> itemQuantities,
            @Nullable CompanionXpTransition xpTransition
    ) {
        if (ownerId == null || companionId == null) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution resolution =
                resolveRole(roleId, companionId);
        if (resolution == null) {
            return;
        }
        ManagedActivityProfile profile = resolution.profile();
        String mappedActivity = resolveHarvestMapping(
                profile.activities(), harvestContext, itemQuantities);
        if (mappedActivity == null) {
            return;
        }
        publish(
                operationId,
                ActivityIds.HARVEST,
                profile,
                groupIds(resolution),
                List.of(participant(resolution, ownerId, companionId)),
                mappedActivity,
                itemQuantities,
                List.of(),
                xpTransition,
                null
        );
    }

    /** Publishes one committed breeding activity with settled offspring IDs. */
    public void publishBreeding(
            @Nonnull UUID litterId,
            @Nullable String parentARoleId,
            @Nullable UUID parentAOwnerId,
            @Nullable UUID parentACompanionId,
            @Nullable String parentBRoleId,
            @Nullable UUID parentBOwnerId,
            @Nullable UUID parentBCompanionId,
            @Nonnull List<UUID> offspringIds
    ) {
        if (offspringIds == null || offspringIds.isEmpty()) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution parentA =
                resolveRole(parentARoleId, parentACompanionId);
        ManagedActivityConfigRegistry.RoleResolution parentB =
                resolveRole(parentBRoleId, parentBCompanionId);
        if (parentA == null || parentB == null) {
            return;
        }
        publish(
                litterId,
                ActivityIds.BREED_SUCCESS,
                parentA.profile(),
                groupIds(parentA, parentB),
                List.of(
                        participant(parentA, parentAOwnerId, parentACompanionId),
                        participant(parentB, parentBOwnerId, parentBCompanionId)
                ),
                parentA.profile().activities().breedingSuccess(),
                Map.of(),
                offspringIds,
                null,
                null
        );
    }

    /** Publishes one committed autonomous food or water need change. */
    public void publishNeedSatisfied(
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
            @Nullable CompanionXpTransition xpTransition,
            @Nullable CareCreditOutcomeView careCredit
    ) {
        if (ownerId == null || companionId == null) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution resolution =
                resolveRole(roleId, companionId);
        if (resolution == null) {
            return;
        }
        String mappedActivityId = resolution.profile().activities().needSatisfied();
        try {
            publisher.publish(new NeedSatisfiedActivityView(
                    new ActivityHeader(
                            operationId,
                            ActivityIds.NEED_SATISFIED,
                            Instant.now()
                    ),
                    companionId,
                    ownerId,
                    resolution.profile().profileId(),
                    groupIds(resolution),
                    resolution.roleId(),
                    mappedActivityId,
                    needType,
                    resourceSource,
                    resourceId,
                    previousValue,
                    currentValue,
                    restoredAmount,
                    xpTransition == null ? null : xpTransition.toOutcomeView(),
                    careCredit
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the already committed need change.
        }
    }

    private void publish(
            UUID operationId,
            String actionId,
            ManagedActivityProfile profile,
            Set<String> groupIds,
            List<ActivityParticipantView> participants,
            String mappedActivityId,
            Map<String, Integer> itemQuantities,
            List<UUID> offspringIds,
            CompanionXpTransition xpTransition,
            CareCreditOutcomeView careCredit
    ) {
        if (operationId == null || profile == null
                || groupIds == null || groupIds.isEmpty()
                || participants == null || participants.isEmpty()
                || mappedActivityId == null || mappedActivityId.isBlank()) {
            return;
        }
        ManagedActivityView activity;
        try {
            activity = new ManagedActivityView(
                    new ActivityHeader(operationId, actionId, Instant.now()),
                    profile.profileId(),
                    groupIds,
                    participants,
                    mappedActivityId,
                    itemQuantities == null ? Map.of() : itemQuantities,
                    offspringIds == null ? List.of() : offspringIds,
                    xpTransition == null ? null : xpTransition.toOutcomeView(),
                    careCredit
            );
            publisher.publish(activity);
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the already committed action.
        }
    }

    @Nullable
    private ManagedActivityConfigRegistry.RoleResolution resolveRole(
            String roleId,
            UUID companionId
    ) {
        if (roleId == null || roleId.isBlank()
                || companionId == null) {
            return null;
        }
        return managedActivities.resolveRole(roleId.trim())
                .orElse(null);
    }

    @Nonnull
    private static ActivityParticipantView participant(
            @Nonnull ManagedActivityConfigRegistry.RoleResolution resolution,
            @Nullable UUID ownerId,
            @Nonnull UUID companionId
    ) {
        return new ActivityParticipantView(
                companionId,
                ownerId,
                resolution.profile().profileId(),
                resolution.roleId()
        );
    }

    @Nonnull
    private static Set<String> groupIds(
            @Nonnull ManagedActivityConfigRegistry.RoleResolution... resolutions
    ) {
        LinkedHashSet<String> groupIds = new LinkedHashSet<>();
        for (ManagedActivityConfigRegistry.RoleResolution resolution : resolutions) {
            if (resolution != null && resolution.family() != null) {
                groupIds.add(resolution.family().groupId());
            }
        }
        return Set.copyOf(groupIds);
    }

    @Nullable
    private static String resolveHarvestMapping(
            ManagedActivityProfile.ActivityMapping mapping,
            String harvestContext,
            Map<String, Integer> itemQuantities
    ) {
        if (harvestContext != null && !harvestContext.isBlank()) {
            String mapped = mapping.harvestContexts().get(harvestContext.trim());
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        if (itemQuantities == null) {
            return null;
        }
        return itemQuantities.keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(mapping.pendingOutputItems()::get)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
