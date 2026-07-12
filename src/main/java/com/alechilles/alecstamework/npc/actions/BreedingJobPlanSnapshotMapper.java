package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan;
import com.alechilles.alecstamework.npc.breeding.BreedingFertilitySnapshot;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Converts the job-based immutable birth plan to and from its durable population journal form. */
final class BreedingJobPlanSnapshotMapper {
    private static final String CHILD_KEY_PREFIX = "child-";

    @Nonnull
    BreedingBirthPlanSnapshot snapshot(
            @Nonnull BreedingBirthPlan plan,
            @Nullable TwBreedingConfig config,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner) {
        List<BreedingBirthPlanSnapshot.PlannedChild> children = new ArrayList<>(
                plan.children().size()
        );
        NPCPlugin plugin = NPCPlugin.get();
        for (int index = 0; index < plan.children().size(); index++) {
            PlannedChild child = plan.children().get(index);
            BreedingOffspringProgressionService.OwnerSnapshot owner =
                    BreedingPlannedOwnerResolver.resolve(
                            config, child.roleId(), parentAOwner, parentBOwner
                    );
            LifecycleKey lifecycle = LifecycleKey.parse(child.lifecycleFamily());
            int roleIndex = plugin == null ? -1 : plugin.getIndex(child.roleId());
            children.add(new BreedingBirthPlanSnapshot.PlannedChild(
                    childKey(index),
                    child.roleId(),
                    roleIndex,
                    child.adultRoleId(),
                    child.gender(),
                    lifecycle.present(),
                    lifecycle.familyId(),
                    lifecycle.lineId(),
                    owner.ownerId(),
                    owner.ownerName(),
                    child.populationType()
            ));
        }
        BreedingFertilitySnapshot fertility = plan.fertilitySnapshot();
        return new BreedingBirthPlanSnapshot(
                fertility.parentAMultiplier(),
                fertility.parentBMultiplier(),
                fertility.expectedOffspring(),
                fertility.rolledChildCount(),
                children
        );
    }

    @Nullable
    BreedingBirthPlan restore(@Nonnull BreedingBirthPlanSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            List<PlannedChild> children = new ArrayList<>(snapshot.children().size());
            for (int index = 0; index < snapshot.children().size(); index++) {
                BreedingBirthPlanSnapshot.PlannedChild child = snapshot.children().get(index);
                if (parseChildIndex(child.childKey()) != index) {
                    return null;
                }
                children.add(new PlannedChild(
                        child.roleId(),
                        child.adultRoleId(),
                        child.gender(),
                        LifecycleKey.compose(child),
                        child.populationType()
                ));
            }
            BreedingFertilitySnapshot fertility = restoreFertility(snapshot);
            return new BreedingBirthPlan(fertility, children);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Returns only children not already durably committed by an earlier process. */
    @Nonnull
    List<PlannedChild> outstandingChildren(
            @Nonnull BreedingBirthPlan plan,
            @Nullable BreedingPopulationReplayState replayState) {
        if (replayState == null || replayState.birthPlan() == null) {
            return plan.children();
        }
        Set<String> pending = replayState.pendingChildKeys();
        List<BreedingBirthPlanSnapshot.PlannedChild> durableChildren =
                replayState.birthPlan().children();
        if (durableChildren.size() != plan.children().size()) {
            return List.of();
        }
        List<PlannedChild> outstanding = new ArrayList<>();
        for (int index = 0; index < plan.children().size(); index++) {
            if (pending.contains(durableChildren.get(index).childKey())) {
                outstanding.add(plan.children().get(index));
            }
        }
        return List.copyOf(outstanding);
    }

    /** Maps an admitted core subset back to the exact durable child records in plan order. */
    @Nonnull
    List<BreedingBirthPlanSnapshot.PlannedChild> durableChildren(
            @Nonnull BreedingBirthPlanSnapshot snapshot,
            @Nonnull List<PlannedChild> candidateChildren,
            @Nonnull List<BreedingBirthPlanSnapshot.PlannedChild> candidateSnapshots,
            @Nonnull List<PlannedChild> admittedChildren) {
        List<BreedingBirthPlanSnapshot.PlannedChild> selected = new ArrayList<>();
        int admittedIndex = 0;
        if (candidateChildren.size() != candidateSnapshots.size()) {
            throw new IllegalArgumentException("Candidate child snapshots must remain parallel");
        }
        for (int candidateIndex = 0;
             candidateIndex < candidateChildren.size() && admittedIndex < admittedChildren.size();
             candidateIndex++) {
            if (candidateChildren.get(candidateIndex).equals(admittedChildren.get(admittedIndex))) {
                selected.add(candidateSnapshots.get(candidateIndex));
                admittedIndex++;
            }
        }
        if (admittedIndex != admittedChildren.size()) {
            throw new IllegalArgumentException("Admitted children must preserve birth-plan order");
        }
        return List.copyOf(selected);
    }

    /** Returns durable child records that are still outstanding after replay filtering. */
    @Nonnull
    List<BreedingBirthPlanSnapshot.PlannedChild> outstandingSnapshots(
            @Nonnull BreedingBirthPlanSnapshot snapshot,
            @Nullable BreedingPopulationReplayState replayState) {
        if (replayState == null || replayState.birthPlan() == null) {
            return snapshot.children();
        }
        Set<String> pending = replayState.pendingChildKeys();
        return snapshot.children().stream()
                .filter(child -> pending.contains(child.childKey()))
                .toList();
    }

    /** Maps stable child keys back to the core plan without rerolling role or lifecycle state. */
    @Nonnull
    List<PlannedChild> coreChildrenForKeys(
            @Nonnull BreedingBirthPlan plan,
            @Nonnull List<String> childKeys) {
        List<PlannedChild> selected = new ArrayList<>(childKeys.size());
        int previous = -1;
        for (String key : childKeys) {
            int index = parseChildIndex(key);
            if (index <= previous || index >= plan.children().size()) {
                throw new IllegalArgumentException("Child keys must preserve birth-plan order");
            }
            selected.add(plan.children().get(index));
            previous = index;
        }
        return List.copyOf(selected);
    }

    @Nonnull
    static String childKey(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("child index must be nonnegative");
        }
        return CHILD_KEY_PREFIX + String.format(java.util.Locale.ROOT, "%04d", index);
    }

    private static int parseChildIndex(String childKey) {
        if (childKey == null || !childKey.startsWith(CHILD_KEY_PREFIX)) {
            throw new IllegalArgumentException("Invalid breeding child key");
        }
        String suffix = childKey.substring(CHILD_KEY_PREFIX.length());
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("Invalid breeding child key");
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                throw new IllegalArgumentException("Invalid breeding child key");
            }
        }
        return Integer.parseInt(suffix);
    }

    private static BreedingFertilitySnapshot restoreFertility(
            BreedingBirthPlanSnapshot snapshot) {
        double expected = snapshot.expectedOffspring();
        int guaranteed = (int) Math.floor(expected);
        double fractional = expected - guaranteed;
        double sampledRoll;
        if (snapshot.offspringCount() > guaranteed) {
            sampledRoll = fractional <= 0.0 ? 0.0 : fractional * 0.5;
        } else if (fractional <= 0.0) {
            sampledRoll = 0.5;
        } else {
            sampledRoll = fractional + ((1.0 - fractional) * 0.5);
        }
        return new BreedingFertilitySnapshot(
                snapshot.parentAMultiplier(),
                snapshot.parentBMultiplier(),
                expected,
                Math.min(Math.nextDown(1.0), sampledRoll),
                snapshot.offspringCount()
        );
    }

    private record LifecycleKey(boolean present, String familyId, String lineId) {
        private static LifecycleKey parse(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return new LifecycleKey(false, null, null);
            }
            String normalized = value.trim();
            int separator = normalized.indexOf(':');
            if (separator < 0) {
                return new LifecycleKey(true, normalized, null);
            }
            String family = normalized.substring(0, separator).trim();
            String line = normalized.substring(separator + 1).trim();
            return new LifecycleKey(
                    true,
                    family.isEmpty() ? null : family,
                    line.isEmpty() ? null : line
            );
        }

        @Nullable
        private static String compose(BreedingBirthPlanSnapshot.PlannedChild child) {
            if (!child.lifecycleFamilyPresent()) {
                return null;
            }
            if (child.lifecycleFamilyId() == null) {
                return child.lifecycleLineId();
            }
            return child.lifecycleLineId() == null
                    ? child.lifecycleFamilyId()
                    : child.lifecycleFamilyId() + ":" + child.lifecycleLineId();
        }
    }
}
