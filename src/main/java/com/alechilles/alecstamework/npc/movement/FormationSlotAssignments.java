package com.alechilles.alecstamework.npc.movement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Assigns formation slots without retaining live game state. Reports supply immutable-coordinate
 * snapshots, and {@link #rebalance(long)} swaps only pairs that have a large, recent travel saving.
 * This keeps a formation flexible when followers become crossed without making it shuffle every tick.
 */
public final class FormationSlotAssignments {
    private static final long MEMBER_EXPIRY_MILLIS = 2_000L;
    private static final long REPORT_EXPIRY_MILLIS = 500L;
    private static final long REBALANCE_CADENCE_MILLIS = 500L;
    private static final long SWAP_COOLDOWN_MILLIS = 2_000L;
    private static final int MAX_COMPARISONS_PER_REBALANCE = 128;

    private final HashMap<UUID, Member> byId = new HashMap<>();
    private final ArrayList<Member> members = new ArrayList<>();
    private final HashSet<Integer> occupiedSlots = new HashSet<>();
    private boolean hasRebalanced;
    private long lastRebalanceMillis;
    private int nextFreeSlot;
    private int nextRound;
    private int nextPairInRound;

    /**
     * Reserves a stable, unique slot for an ID. Existing IDs retain their slot; newly seen IDs use
     * their preferred free slot, or the lowest free slot when it is already occupied.
     */
    public int claim(@Nonnull UUID id, int preferredSlot, long nowMillis) {
        if (id == null) {
            return -1;
        }
        Member existing = byId.get(id);
        if (existing != null) {
            existing.lastSeenMillis = nowMillis;
            return existing.slot;
        }
        int slot = Math.max(0, preferredSlot);
        if (!occupiedSlots.add(slot)) {
            slot = lowestFreeSlot();
            occupiedSlots.add(slot);
        }
        if (slot == nextFreeSlot) {
            advanceNextFreeSlot();
        }
        Member member = new Member(id, slot, nowMillis);
        byId.put(id, member);
        members.add(member);
        resetPairSchedule();
        return slot;
    }

    /**
     * Copies the current movement snapshot for a claimed ID. Invalid data stays ineligible for
     * swapping, while the live ID is still kept active by this report.
     */
    public void report(@Nonnull UUID id, @Nonnull Vector3d position, @Nonnull Vector3d target,
                       double spacing, long nowMillis) {
        report(id, position, target, spacing, "", nowMillis);
    }

    /** Layouts share unique slot numbers but only exchange compatible target geometry. */
    public void report(@Nonnull UUID id, @Nonnull Vector3d position, @Nonnull Vector3d target,
                       double spacing, @Nonnull String profile, long nowMillis) {
        if (id == null) {
            return;
        }
        Member member = byId.get(id);
        if (member == null) {
            return;
        }
        member.lastSeenMillis = nowMillis;
        member.reported = isFinite(position) && isFinite(target) && Double.isFinite(spacing)
                && spacing > 0.0;
        if (!member.reported) {
            return;
        }
        member.positionX = position.x;
        member.positionY = position.y;
        member.positionZ = position.z;
        member.targetX = target.x;
        member.targetY = target.y;
        member.targetZ = target.z;
        member.spacing = spacing;
        member.profile = profile;
        member.reportMillis = nowMillis;
    }

    /**
     * Removes inactive IDs and inspects a bounded, rotating set of unique pairs. The returned
     * count is the number of candidate pairs inspected on this call, allowing callers to measure
     * the fixed rebalance cost.
     */
    public int rebalance(long nowMillis) {
        if (hasRebalanced && !hasElapsedAtLeast(nowMillis, lastRebalanceMillis, REBALANCE_CADENCE_MILLIS)) {
            return 0;
        }
        hasRebalanced = true;
        lastRebalanceMillis = nowMillis;
        expireMembers(nowMillis);
        int memberCount = members.size();
        if (memberCount < 2) {
            resetPairSchedule();
            return 0;
        }

        long totalPairs = (long) memberCount * (memberCount - 1L) / 2L;
        int comparisonLimit = (int) Math.min(MAX_COMPARISONS_PER_REBALANCE, totalPairs);
        int comparisons = 0;
        while (comparisons < comparisonLimit) {
            int firstIndex = firstIndexForScheduledPair(memberCount);
            int secondIndex = secondIndexForScheduledPair(memberCount);
            advancePairSchedule(memberCount);
            comparisons++;
            trySwap(members.get(firstIndex), members.get(secondIndex), nowMillis);
        }
        return comparisons;
    }

    /** Returns the number of currently reserved slots, including reservations awaiting a report. */
    public int size() {
        return members.size();
    }

    /** Returns the current slot for an ID, or {@code -1} if it has no reservation. */
    public int slot(@Nonnull UUID id) {
        Member member = id == null ? null : byId.get(id);
        return member == null ? -1 : member.slot;
    }

    private void expireMembers(long nowMillis) {
        boolean removed = false;
        for (int index = members.size() - 1; index >= 0; index--) {
            Member member = members.get(index);
            if (hasExceeded(nowMillis, member.lastSeenMillis, MEMBER_EXPIRY_MILLIS)) {
                byId.remove(member.id);
                members.remove(index);
                removed = true;
            }
        }
        if (removed) {
            // Keep the remaining footprint compact after departures. Invalidate old target
            // snapshots so a renumbered slot cannot trade using its former geometry.
            members.sort(java.util.Comparator.comparingInt(member -> member.slot));
            for (int i = 0; i < members.size(); i++) {
                Member member = members.get(i);
                if (member.slot != i) {
                    member.slot = i;
                    member.reported = false;
                }
            }
            rebuildOccupiedSlots();
            resetPairSchedule();
        }
    }

    private void trySwap(Member first, Member second, long nowMillis) {
        if (!java.util.Objects.equals(first.profile, second.profile) || first.spacing != second.spacing
                || !hasRecentReport(first, nowMillis) || !hasRecentReport(second, nowMillis)
                || (first.hasSwapped && !hasElapsedAtLeast(nowMillis, first.lastSwapMillis, SWAP_COOLDOWN_MILLIS))
                || (second.hasSwapped && !hasElapsedAtLeast(nowMillis, second.lastSwapMillis, SWAP_COOLDOWN_MILLIS))) {
            return;
        }
        double currentCost = distance(first.positionX, first.positionY, first.positionZ,
                first.targetX, first.targetY, first.targetZ)
                + distance(second.positionX, second.positionY, second.positionZ,
                second.targetX, second.targetY, second.targetZ);
        if (!Double.isFinite(currentCost) || currentCost <= 0.0) {
            return;
        }
        double swappedCost = distance(first.positionX, first.positionY, first.positionZ,
                second.targetX, second.targetY, second.targetZ)
                + distance(second.positionX, second.positionY, second.positionZ,
                first.targetX, first.targetY, first.targetZ);
        double improvement = currentCost - swappedCost;
        double spacing = Math.max(first.spacing, second.spacing);
        if (!Double.isFinite(swappedCost) || improvement < Math.max(0.5, spacing * 0.5)
                || improvement / currentCost < 0.20) {
            return;
        }
        swapSlotsAndTargets(first, second);
        first.lastSwapMillis = nowMillis;
        second.lastSwapMillis = nowMillis;
        first.hasSwapped = true;
        second.hasSwapped = true;
    }

    private static boolean hasRecentReport(Member member, long nowMillis) {
        return member.reported && hasElapsedAtMost(nowMillis, member.reportMillis, REPORT_EXPIRY_MILLIS);
    }

    private static void swapSlotsAndTargets(Member first, Member second) {
        int slot = first.slot;
        first.slot = second.slot;
        second.slot = slot;
        double targetX = first.targetX;
        double targetY = first.targetY;
        double targetZ = first.targetZ;
        long reportMillis = first.reportMillis;
        double spacing = first.spacing;
        first.targetX = second.targetX;
        first.targetY = second.targetY;
        first.targetZ = second.targetZ;
        first.reportMillis = second.reportMillis;
        first.spacing = second.spacing;
        second.targetX = targetX;
        second.targetY = targetY;
        second.targetZ = targetZ;
        second.reportMillis = reportMillis;
        second.spacing = spacing;
    }

    private int lowestFreeSlot() {
        return nextFreeSlot;
    }

    private void advanceNextFreeSlot() {
        while (occupiedSlots.contains(nextFreeSlot) && nextFreeSlot < Integer.MAX_VALUE) {
            nextFreeSlot++;
        }
    }

    private void rebuildOccupiedSlots() {
        occupiedSlots.clear();
        for (Member member : members) {
            occupiedSlots.add(member.slot);
        }
        nextFreeSlot = members.size();
    }

    /**
     * Returns one pair from a round-robin tournament. A round has disjoint pairs, and completing
     * all rounds visits every unordered pair exactly once. Odd-sized flocks add an implicit ghost
     * member, whose pair is skipped each round.
     */
    private int firstIndexForScheduledPair(int memberCount) {
        int effectiveMemberCount = memberCount + (memberCount & 1);
        int rotatingCount = effectiveMemberCount - 1;
        if ((memberCount & 1) == 0 && nextPairInRound == 0) {
            return rotatingCount;
        }
        int offset = (memberCount & 1) == 0 ? nextPairInRound : nextPairInRound + 1;
        return (nextRound + offset) % rotatingCount;
    }

    private int secondIndexForScheduledPair(int memberCount) {
        int effectiveMemberCount = memberCount + (memberCount & 1);
        int rotatingCount = effectiveMemberCount - 1;
        if ((memberCount & 1) == 0 && nextPairInRound == 0) {
            return nextRound;
        }
        int offset = (memberCount & 1) == 0 ? nextPairInRound : nextPairInRound + 1;
        return Math.floorMod(nextRound - offset, rotatingCount);
    }

    private void advancePairSchedule(int memberCount) {
        int pairsPerRound = memberCount / 2;
        nextPairInRound++;
        if (nextPairInRound >= pairsPerRound) {
            nextPairInRound = 0;
            int roundCount = memberCount + (memberCount & 1) - 1;
            nextRound = (nextRound + 1) % roundCount;
        }
    }

    private void resetPairSchedule() {
        nextRound = 0;
        nextPairInRound = 0;
    }

    private static boolean hasExceeded(long nowMillis, long earlierMillis, long durationMillis) {
        return nowMillis >= earlierMillis && nowMillis - earlierMillis > durationMillis;
    }

    private static boolean hasElapsedAtLeast(long nowMillis, long earlierMillis, long durationMillis) {
        return nowMillis >= earlierMillis && nowMillis - earlierMillis >= durationMillis;
    }

    private static boolean hasElapsedAtMost(long nowMillis, long earlierMillis, long durationMillis) {
        return nowMillis >= earlierMillis && nowMillis - earlierMillis <= durationMillis;
    }

    private static boolean isFinite(Vector3d vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static double distance(double firstX, double firstY, double firstZ,
                                   double secondX, double secondY, double secondZ) {
        return Math.sqrt((firstX - secondX) * (firstX - secondX)
                + (firstY - secondY) * (firstY - secondY)
                + (firstZ - secondZ) * (firstZ - secondZ));
    }

    private static final class Member {
        private final UUID id;
        private int slot;
        private long lastSeenMillis;
        private boolean reported;
        private long reportMillis;
        private double positionX;
        private double positionY;
        private double positionZ;
        private double targetX;
        private double targetY;
        private double targetZ;
        private double spacing;
        private String profile = "";
        private boolean hasSwapped;
        private long lastSwapMillis;

        private Member(UUID id, int slot, long lastSeenMillis) {
            this.id = id;
            this.slot = slot;
            this.lastSeenMillis = lastSeenMillis;
        }
    }
}
