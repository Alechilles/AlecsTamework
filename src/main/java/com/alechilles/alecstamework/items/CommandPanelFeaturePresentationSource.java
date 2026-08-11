package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalCostQuoteView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.PopulationGroupCountsView;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.alechilles.alecstamework.ui.CommandRosterStatusPresentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds row-scoped command feature presentation exclusively from canonical
 * roster projections and replacement public APIs.
 */
final class CommandPanelFeaturePresentationSource {
    private static final long QUOTE_REFRESH_INTERVAL_MS = 750L;
    private static final String QUERY_KEY = "command-panel-query";

    private final CommandRosterPanelRecordSource rosterSource;
    private final Supplier<CommandTimedSummoningApi> timedSummoning;
    private final Supplier<PaidCommandRevivalApi> paidRevival;
    private final Supplier<PopulationGroupApi> populationGroups;
    private final LongSupplier clock;
    private final ConcurrentHashMap<QuoteKey, QuoteCache> quoteCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<QuoteKey, Boolean> quotesInFlight =
            new ConcurrentHashMap<>();

    CommandPanelFeaturePresentationSource(
            @Nonnull CommandRosterPanelRecordSource rosterSource,
            @Nonnull CommandTimedSummoningApi timedSummoning,
            @Nonnull PaidCommandRevivalApi paidRevival,
            @Nonnull PopulationGroupApi populationGroups,
            @Nonnull LongSupplier clock
    ) {
        this(
                rosterSource,
                () -> timedSummoning,
                () -> paidRevival,
                () -> populationGroups,
                clock
        );
    }

    CommandPanelFeaturePresentationSource(
            @Nonnull CommandRosterPanelRecordSource rosterSource,
            @Nonnull Supplier<CommandTimedSummoningApi> timedSummoning,
            @Nonnull Supplier<PaidCommandRevivalApi> paidRevival,
            @Nonnull Supplier<PopulationGroupApi> populationGroups,
            @Nonnull LongSupplier clock
    ) {
        this.rosterSource = Objects.requireNonNull(
                rosterSource, "Roster source is required"
        );
        this.timedSummoning = Objects.requireNonNull(
                timedSummoning, "Timed summon API is required"
        );
        this.paidRevival = Objects.requireNonNull(
                paidRevival, "Paid revival API is required"
        );
        this.populationGroups = Objects.requireNonNull(
                populationGroups, "Population group API is required"
        );
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
        CommandTimedSummoningState state =
                fallbackState(member.lifecycleState());
        long revision = member.view().membership().membershipRevision();
        Long remainingMs = null;
        boolean unlimited = false;
        long cooldownRemainingMs = 0L;
        try {
            CommandTimedSummoningRequest identity =
                    new CommandTimedSummoningRequest(
                            ownerUuid,
                            familyId,
                            member.profileId(),
                            QUERY_KEY
                    );
            CommandTimedSummoningView timed =
                    currentTimedSummoning().get(identity).orElse(null);
            if (timed != null) {
                state = timed.state();
                revision = timed.revision();
                remainingMs = timed.remainingMs();
                unlimited = timed.unlimited();
                cooldownRemainingMs = remaining(
                        timed.cooldownUntilMs(), nowMs
                );
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The canonical lifecycle remains a safe read-only fallback.
        }
        long configuredDurationMs = TwCompanionConfig
                .resolveEffectiveForRole(member.roleId())
                .getSummon()
                .getActiveDurationMs();
        Capacity capacity = capacity(
                ownerUuid, ownershipWorldName, member.roleId()
        );
        return new CommandRosterStatusPresentation(
                member.profileId(),
                familyId,
                state,
                revision,
                remainingMs,
                configuredDurationMs,
                unlimited || configuredDurationMs == 0L,
                cooldownRemainingMs,
                capacity.activeCount(),
                capacity.activeLimit(),
                capacity.blockingGroupId(),
                capacity.blockingReason()
        );
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
            PopulationGroupApi groups = currentPopulationGroups();
            for (PopulationGroupDefinitionView definition
                    : groups.resolveForRole(roleId)) {
                String world = definition.scope()
                        == PopulationGroupScope.PER_WORLD
                        ? normalize(ownershipWorldName)
                        : null;
                if (definition.scope() == PopulationGroupScope.PER_WORLD
                        && world == null) {
                    continue;
                }
                PopulationGroupCountsView counts =
                        groups.getCounts(
                                ownerUuid, definition.groupId(), world
                        ).orElse(null);
                if (counts == null || counts.maxActive() <= 0L) {
                    continue;
                }
                long active = saturatedAdd(
                        counts.committedActive(), counts.pendingActive()
                );
                long headroom = counts.maxActive() - active;
                if (headroom < smallestHeadroom) {
                    smallestHeadroom = headroom;
                    selectedActive = active;
                    selectedLimit = counts.maxActive();
                    selectedGroup = definition.groupId();
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

    private static CommandTimedSummoningState fallbackState(
            LifecycleState state
    ) {
        return switch (state) {
            case ACTIVE -> CommandTimedSummoningState.ACTIVE;
            case UNLOADED -> CommandTimedSummoningState.UNLOADED;
            case ROSTER_STORED ->
                    CommandTimedSummoningState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandTimedSummoningState.DEAD_REVIVABLE;
            case LOST -> CommandTimedSummoningState.LOST;
            case UNRESOLVED -> CommandTimedSummoningState.UNAVAILABLE;
            default -> CommandTimedSummoningState.UNLOADED;
        };
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

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static int saturatedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private CommandTimedSummoningApi currentTimedSummoning() {
        return resolve(
                timedSummoning, CommandTimedSummoningApi.unavailable()
        );
    }

    private PaidCommandRevivalApi currentPaidRevival() {
        return resolve(paidRevival, PaidCommandRevivalApi.unavailable());
    }

    private PopulationGroupApi currentPopulationGroups() {
        return resolve(
                populationGroups, PopulationGroupApi.unavailable()
        );
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
