package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalCostQuoteView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.alechilles.alecstamework.ui.CommandRosterStatusPresentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds row-scoped command feature presentation for command-family roster members from their
 * companion index records: state, summon timer and cooldown, and the tightest deployed group limit
 * of the member's role. Paid revival quotes still come from the revival API.
 */
final class CommandPanelFeaturePresentationSource {
    private static final long QUOTE_REFRESH_INTERVAL_MS = 750L;

    private final CommandRosterPanelRecordSource rosterSource;
    private final Supplier<PaidCommandRevivalApi> paidRevival;
    private final Function<UUID, List<CompanionRecord>> ownedRecords;
    private final Supplier<CompanionAdmission.Rules> admissionRules;
    private final LongSupplier clock;
    private final ConcurrentHashMap<QuoteKey, QuoteCache> quoteCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<QuoteKey, Boolean> quotesInFlight =
            new ConcurrentHashMap<>();

    /**
     * @param paidRevival    the paid revival API; null or failing reads as unavailable
     * @param ownedRecords   every non-released record of an owner, for the deployed group counts
     * @param admissionRules the current population rules; a null or failing read shows no limit
     */
    CommandPanelFeaturePresentationSource(
            @Nonnull CommandRosterPanelRecordSource rosterSource,
            @Nonnull Supplier<PaidCommandRevivalApi> paidRevival,
            @Nonnull Function<UUID, List<CompanionRecord>> ownedRecords,
            @Nonnull Supplier<CompanionAdmission.Rules> admissionRules,
            @Nonnull LongSupplier clock
    ) {
        this.rosterSource = Objects.requireNonNull(
                rosterSource, "Roster source is required"
        );
        this.paidRevival = Objects.requireNonNull(
                paidRevival, "Paid revival API is required"
        );
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
                ownerUuid, ownershipWorldName, familyId, members
        );
    }

    /**
     * Builds feature rows from the exact roster member set used to create the
     * accompanying command-panel cards.
     */
    @Nonnull
    Map<UUID, CommandPanelFeaturePresentation> snapshotForMembers(
            @Nullable UUID ownerUuid,
            @Nullable String ownershipWorldName,
            @Nullable String familyId,
            @Nullable List<CommandRosterPanelRecordSource.PanelMember> members
    ) {
        if (ownerUuid == null || familyId == null || familyId.isBlank()) {
            return Map.of();
        }
        if (members == null || members.isEmpty()) {
            return Map.of();
        }
        long nowMs = clock.getAsLong();
        LinkedHashMap<UUID, CommandPanelFeaturePresentation> result =
                new LinkedHashMap<>();
        for (CommandRosterPanelRecordSource.PanelMember member : members) {
            CommandRosterStatusPresentation roster = roster(
                    ownerUuid, ownershipWorldName, familyId, member, nowMs
            );
            CommandReviveCostPresentation revival =
                    roster.paidRevivalState()
                            ? revival(ownerUuid, familyId, member, nowMs)
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
            long nowMs
    ) {
        CompanionRecord record = member.record();
        boolean summoned = record.location().kind() == LocationKind.LIVE;
        Long remainingMs = summoned && record.summonedUntilMs() != 0L
                ? remaining(record.summonedUntilMs(), nowMs) : null;
        long configuredDurationMs = configuredDurationMs(member.roleId());
        Capacity capacity = capacity(
                ownerUuid, ownershipWorldName, member.roleId()
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

    @Nullable
    private CommandReviveCostPresentation revival(
            UUID ownerUuid,
            String familyId,
            CommandRosterPanelRecordSource.PanelMember member,
            long nowMs
    ) {
        QuoteKey key = new QuoteKey(
                ownerUuid, familyId, member.profileId()
        );
        QuoteCache cached = quoteCache.get(key);
        if (cached == null || cached.stale(nowMs)) {
            requestQuote(key, nowMs);
            cached = quoteCache.get(key);
        }
        return cached == null ? null : presentation(cached.quote());
    }

    private void requestQuote(QuoteKey key, long requestedAtMs) {
        if (quotesInFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        PaidCommandRevivalQuoteRequest request =
                new PaidCommandRevivalQuoteRequest(
                        key.ownerUuid(), key.profileId(), key.familyId()
                );
        try {
            var stage = currentPaidRevival().quote(request);
            if (stage == null) {
                quotesInFlight.remove(key);
                return;
            }
            stage.whenComplete((quote, failure) -> {
                try {
                    if (failure == null && matches(key, quote)) {
                        quoteCache.put(
                                key,
                                new QuoteCache(
                                        quote,
                                        Math.max(
                                                requestedAtMs,
                                                clock.getAsLong()
                                        )
                                )
                        );
                    }
                } finally {
                    quotesInFlight.remove(key);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            quotesInFlight.remove(key);
        }
    }

    private boolean matches(QuoteKey key, PaidCommandRevivalQuote quote) {
        return quote != null
                && key.ownerUuid().equals(quote.ownerUuid())
                && key.familyId().equals(quote.commandFamilyId())
                && key.profileId().equals(quote.profileId());
    }

    private CommandReviveCostPresentation presentation(
            PaidCommandRevivalQuote quote
    ) {
        List<CommandReviveCostPresentation.CostLine> costs =
                quote.costs().stream()
                        .map(CommandPanelFeaturePresentationSource::cost)
                        .toList();
        return new CommandReviveCostPresentation(
                quote.status(),
                quote.cooldownRemainingMs(),
                costs,
                quote.configRevision(),
                quote.messageKey(),
                quote.reason()
        );
    }

    private static CommandReviveCostPresentation.CostLine cost(
            PaidCommandRevivalCostQuoteView cost
    ) {
        String localizedName = cost.localizedName() == null
                ? cost.itemId()
                : cost.localizedName();
        return new CommandReviveCostPresentation.CostLine(
                cost.itemId(),
                localizedName,
                cost.iconAssetId(),
                cost.ownedQuantity(),
                cost.requiredQuantity()
        );
    }

    /**
     * The member role's deployed group with the least headroom, counted as {@link
     * CompanionAdmission} counts it: the owner's LIVE records in that group, in the player's world
     * for a per-world group. Groups without a deployed limit are skipped.
     */
    private Capacity capacity(
            UUID ownerUuid,
            String ownershipWorldName,
            String roleId
    ) {
        long selectedActive = 0L;
        long selectedLimit = 0L;
        long smallestHeadroom = Long.MAX_VALUE;
        String selectedGroup = null;
        try {
            CompanionAdmission.Rules rules = admissionRules.get();
            if (rules == null) {
                return Capacity.unlimited();
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
                            && inGroup(rules, record, group.groupId())) {
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
            return Capacity.unlimited();
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

    private static boolean inGroup(CompanionAdmission.Rules rules, CompanionRecord record, String groupId) {
        for (PopulationGroupPolicy policy : rules.groupsForRole().apply(record.roleId())) {
            if (policy.groupId().equals(groupId)) {
                return true;
            }
        }
        return false;
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

    private PaidCommandRevivalApi currentPaidRevival() {
        return resolve(paidRevival, PaidCommandRevivalApi.unavailable());
    }

    private static <T> T resolve(Supplier<T> source, T unavailable) {
        try {
            T resolved = source.get();
            return resolved == null ? unavailable : resolved;
        } catch (RuntimeException | LinkageError ignored) {
            return unavailable;
        }
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

    private record QuoteKey(
            @Nonnull UUID ownerUuid,
            @Nonnull String familyId,
            @Nonnull String profileId
    ) {
        QuoteKey {
            Objects.requireNonNull(ownerUuid, "Owner is required");
            familyId = Objects.requireNonNull(
                    normalize(familyId), "Family is required"
            );
            profileId = Objects.requireNonNull(
                    normalize(profileId), "Profile is required"
            );
        }
    }

    private record QuoteCache(
            @Nonnull PaidCommandRevivalQuote quote,
            long observedAtMs
    ) {
        QuoteCache {
            Objects.requireNonNull(quote, "Quote is required");
        }

        private boolean stale(long nowMs) {
            return quote.status()
                    == PaidCommandRevivalQuote.Status.UNAVAILABLE
                    || nowMs < observedAtMs
                    || nowMs - observedAtMs >= QUOTE_REFRESH_INTERVAL_MS;
        }
    }

    private record Capacity(
            int activeCount,
            int activeLimit,
            @Nullable String blockingGroupId,
            @Nullable String blockingReason
    ) {
        private static Capacity unlimited() {
            return new Capacity(0, 0, null, null);
        }
    }
}
