package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.alechilles.alecstamework.ui.CommandRosterStatusPresentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds row-scoped command feature presentation for command-family roster members from their
 * companion index records: state, summon timer and cooldown, and the tightest deployed group limit
 * of the member's role. A dead member's revive row shows its role's {@code Command.Revive.Costs}
 * against the items the player holds and the record's revive cooldown; {@link
 * CommandCompanionRestorationService} checks and charges the same terms when the player confirms.
 */
final class CommandPanelFeaturePresentationSource {
    private static final String REVIVE_TERMS_REVISION = "index";

    private final CommandRosterPanelRecordSource rosterSource;
    private final Function<String, ReviveTerms> reviveTerms;
    private final Function<UUID, List<CompanionRecord>> ownedRecords;
    private final Supplier<CompanionAdmission.Rules> admissionRules;
    private final LongSupplier clock;

    /**
     * @param reviveTerms    a role's revive terms; a failing read shows revive as unavailable
     * @param ownedRecords   every non-released record of an owner, for the deployed group counts
     * @param admissionRules the current population rules; a null or failing read shows no limit
     */
    CommandPanelFeaturePresentationSource(
            @Nonnull CommandRosterPanelRecordSource rosterSource,
            @Nonnull Function<String, ReviveTerms> reviveTerms,
            @Nonnull Function<UUID, List<CompanionRecord>> ownedRecords,
            @Nonnull Supplier<CompanionAdmission.Rules> admissionRules,
            @Nonnull LongSupplier clock
    ) {
        this.rosterSource = Objects.requireNonNull(
                rosterSource, "Roster source is required"
        );
        this.reviveTerms = Objects.requireNonNull(reviveTerms, "Revive terms are required");
        this.ownedRecords = Objects.requireNonNull(ownedRecords, "Owned records are required");
        this.admissionRules = Objects.requireNonNull(admissionRules, "Admission rules are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Nonnull
    Map<UUID, CommandPanelFeaturePresentation> snapshot(
            @Nullable UUID ownerUuid,
            @Nullable String ownershipWorldName,
            @Nullable TwCommandItemConfig config
    ) {
        String familyId = familyId(config);
        if (ownerUuid == null || familyId == null) {
            return Map.of();
        }
        List<CommandRosterPanelRecordSource.PanelMember> members =
                rosterSource.membersFor(ownerUuid, familyId);
        return snapshotForMembers(
                ownerUuid, ownershipWorldName, familyId, members, null
        );
    }

    /**
     * Builds feature rows from the exact roster member set used to create the
     * accompanying command-panel cards.
     *
     * @param heldItems how many of an item id the viewing player holds, for the revive cost
     *                  lines; null shows every cost as not held
     */
    @Nonnull
    Map<UUID, CommandPanelFeaturePresentation> snapshotForMembers(
            @Nullable UUID ownerUuid,
            @Nullable String ownershipWorldName,
            @Nullable String familyId,
            @Nullable List<CommandRosterPanelRecordSource.PanelMember> members,
            @Nullable ToIntFunction<String> heldItems
    ) {
        if (ownerUuid == null || familyId == null || familyId.isBlank()) {
            return Map.of();
        }
        if (members == null || members.isEmpty()) {
            return Map.of();
        }
        long nowMs = clock.getAsLong();
        int deployedMembers = 0;
        for (CommandRosterPanelRecordSource.PanelMember member : members) {
            if (member.record().isDeployed()) {
                deployedMembers++;
            }
        }
        LinkedHashMap<UUID, CommandPanelFeaturePresentation> result =
                new LinkedHashMap<>();
        for (CommandRosterPanelRecordSource.PanelMember member : members) {
            CommandRosterStatusPresentation roster = roster(
                    ownerUuid, ownershipWorldName, familyId, member, deployedMembers, nowMs
            );
            CommandReviveCostPresentation revival =
                    roster.paidRevivalState()
                            ? revival(member, heldItems, nowMs)
                            : null;
            result.put(
                    member.presentationUuid(),
                    new CommandPanelFeaturePresentation(roster, revival)
            );
        }
        return Map.copyOf(result);
    }

    @Nullable
    CommandRosterPanelRecordSource.PanelMember resolveMember(
            @Nullable UUID ownerUuid,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        String familyId = familyId(config);
        if (ownerUuid == null || familyId == null
                || presentationUuid == null) {
            return null;
        }
        for (CommandRosterPanelRecordSource.PanelMember member
                : rosterSource.membersFor(ownerUuid, familyId)) {
            if (presentationUuid.equals(member.presentationUuid())) {
                return member;
            }
        }
        return null;
    }

    @Nullable
    CommandPanelFeaturePresentation presentation(
            @Nullable UUID ownerUuid,
            @Nullable String ownershipWorldName,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        if (presentationUuid == null) {
            return null;
        }
        return snapshot(ownerUuid, ownershipWorldName, config)
                .get(presentationUuid);
    }

    private CommandRosterStatusPresentation roster(
            UUID ownerUuid,
            String ownershipWorldName,
            String familyId,
            CommandRosterPanelRecordSource.PanelMember member,
            int deployedMembers,
            long nowMs
    ) {
        CompanionRecord record = member.record();
        boolean summoned = record.location().kind() == LocationKind.LIVE;
        Long remainingMs = summoned && record.summonedUntilMs() != 0L
                ? remaining(record.summonedUntilMs(), nowMs) : null;
        long configuredDurationMs = configuredDurationMs(member.roleId());
        Capacity capacity = capacity(
                ownerUuid, ownershipWorldName, member.roleId(), deployedMembers
        );
        return new CommandRosterStatusPresentation(
                member.profileId(),
                familyId,
                state(member),
                record.revision(),
                remainingMs,
                configuredDurationMs,
                remainingMs == null && (summoned || configuredDurationMs == 0L),
                remaining(record.summonCooldownUntilMs(), nowMs),
                capacity.activeCount(),
                capacity.activeLimit(),
                capacity.blockingGroupId(),
                capacity.blockingReason()
        );
    }

    /**
     * LIVE with a loaded body is summoned and LIVE without one is unloaded; STORED is stored and
     * DEAD is dead. A member in an item or a coop has no roster actions.
     */
    static CommandTimedSummoningState state(
            CommandRosterPanelRecordSource.PanelMember member
    ) {
        return switch (member.record().location().kind()) {
            case LIVE -> member.bodyLoaded()
                    ? CommandTimedSummoningState.ACTIVE
                    : CommandTimedSummoningState.UNLOADED;
            case STORED -> CommandTimedSummoningState.ROSTER_STORED;
            case DEAD -> CommandTimedSummoningState.DEAD_REVIVABLE;
            case LOST -> CommandTimedSummoningState.LOST;
            case ITEM, COOP, RELEASED -> CommandTimedSummoningState.UNAVAILABLE;
        };
    }

    /** The role's timed summon duration; 0 when summons are untimed or the config cannot be read. */
    private static long configuredDurationMs(String roleId) {
        try {
            return RosterSummons.Policy.forRole(roleId).durationMs();
        } catch (RuntimeException | LinkageError ignored) {
            return 0L;
        }
    }

    /**
     * The revive row of a dead or lost member. Recovering a lost member is free and has no
     * cooldown. A dead member follows the checks of {@link CommandCompanionRestorationService}:
     * revive turned on for its role, the record's wall-clock cooldown, then the item cost.
     * Null when the role's terms cannot be read.
     */
    @Nullable
    private CommandReviveCostPresentation revival(
            CommandRosterPanelRecordSource.PanelMember member,
            @Nullable ToIntFunction<String> heldItems,
            long nowMs
    ) {
        CompanionRecord record = member.record();
        if (record.location().kind() != LocationKind.DEAD) {
            return revival(PaidCommandRevivalQuote.Status.READY, 0L, List.of());
        }
        ReviveTerms terms;
        try {
            terms = reviveTerms.apply(member.roleId());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        if (terms == null) {
            return null;
        }
        List<CommandReviveCostPresentation.CostLine> costs = new ArrayList<>(terms.costs().size());
        boolean affordable = true;
        for (TwItemCostComponent cost : terms.costs()) {
            int held = heldItems == null ? 0 : Math.max(0, heldItems.applyAsInt(cost.getItemId()));
            affordable &= held >= cost.getQuantity();
            costs.add(new CommandReviveCostPresentation.CostLine(
                    cost.getItemId(), cost.getItemId(), null, held, cost.getQuantity()));
        }
        long cooldownMs = remaining(record.reviveAvailableAtMs(), nowMs);
        PaidCommandRevivalQuote.Status status = !terms.enabled()
                ? PaidCommandRevivalQuote.Status.DISABLED
                : cooldownMs > 0L
                ? PaidCommandRevivalQuote.Status.COOLDOWN
                : affordable
                ? PaidCommandRevivalQuote.Status.READY
                : PaidCommandRevivalQuote.Status.INSUFFICIENT_COST;
        return revival(status, cooldownMs, costs);
    }

    private static CommandReviveCostPresentation revival(
            PaidCommandRevivalQuote.Status status,
            long cooldownMs,
            List<CommandReviveCostPresentation.CostLine> costs
    ) {
        return new CommandReviveCostPresentation(
                status, cooldownMs, costs, REVIVE_TERMS_REVISION, null, null);
    }

    /**
     * The member role's deployed group with the least headroom, counted as {@link
     * CompanionAdmission} counts it: the owner's LIVE records in that group, in the player's world
     * for a per-world group. Groups without a deployed limit are skipped. When no limit applies,
     * the count is this roster's deployed members, so the row never reads zero beside a summoned
     * member.
     */
    private Capacity capacity(
            UUID ownerUuid,
            String ownershipWorldName,
            String roleId,
            int deployedMembers
    ) {
        long selectedActive = 0L;
        long selectedLimit = 0L;
        long smallestHeadroom = Long.MAX_VALUE;
        String selectedGroup = null;
        try {
            CompanionAdmission.Rules rules = admissionRules.get();
            if (rules == null) {
                return Capacity.unlimited(deployedMembers);
            }
            List<CompanionRecord> owned = ownedRecords.apply(ownerUuid);
            String world = normalize(ownershipWorldName);
            for (PopulationGroupPolicy group : rules.groupsForRole().apply(roleId)) {
                boolean perWorld = group.scope() == PopulationGroupScope.PER_WORLD;
                if (group.maxActivePerOwner() <= 0 || perWorld && world == null) {
                    continue;
                }
                long active = 0L;
                for (CompanionRecord record : owned) {
                    if (record.isDeployed() && record.countsAsOwned()
                            && (!perWorld || world.equals(CompanionAdmission.scopeWorld(record)))
                            && CompanionAdmission.inGroup(rules, record, group.groupId())) {
                        active++;
                    }
                }
                long headroom = group.maxActivePerOwner() - active;
                if (headroom < smallestHeadroom) {
                    smallestHeadroom = headroom;
                    selectedActive = active;
                    selectedLimit = group.maxActivePerOwner();
                    selectedGroup = group.groupId();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            return Capacity.unlimited(deployedMembers);
        }
        if (selectedGroup == null) {
            return Capacity.unlimited(deployedMembers);
        }
        return new Capacity(
                saturatedInt(selectedActive),
                saturatedInt(selectedLimit),
                selectedGroup,
                smallestHeadroom <= 0L
                        ? "active-cap-reached"
                        : null
        );
    }

    private static long remaining(long untilMs, long nowMs) {
        if (untilMs == 0L || untilMs <= nowMs) {
            return 0L;
        }
        try {
            return Math.subtractExact(untilMs, nowMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static int saturatedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    @Nullable
    private static String familyId(TwCommandItemConfig config) {
        if (config == null || !config.usesOwnerCommandFamilyRoster()) {
            return null;
        }
        return normalize(config.getCommandFamilyId());
    }

    @Nullable
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Whether revive is turned on for a role, and the items one revive costs. */
    record ReviveTerms(boolean enabled, @Nonnull List<TwItemCostComponent> costs) {
        ReviveTerms {
            costs = List.copyOf(costs);
        }

        /** The live terms, as {@link CommandCompanionRestorationService} reads them. */
        @Nonnull
        static ReviveTerms forRole(@Nullable String roleId) {
            return new ReviveTerms(
                    CompanionRevivePolicy.featureEnabled(roleId),
                    List.of(TwCompanionConfig.resolveEffectiveForRole(roleId).getRevive().getCosts()));
        }
    }

    private record Capacity(
            int activeCount,
            int activeLimit,
            @Nullable String blockingGroupId,
            @Nullable String blockingReason
    ) {
        private static Capacity unlimited(int activeCount) {
            return new Capacity(activeCount, 0, null, null);
        }
    }
}
