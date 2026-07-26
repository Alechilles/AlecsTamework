package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds bonded status/actions from the same immutable profiles as card data. */
final class BondedCompanionPanelFeaturePresentationSource {
    private static final String CALLER = "tamework:bonded-panel-query";
    private final Supplier<BondedCompanionApi> api;
    private final java.util.function.LongSupplier clock;

    BondedCompanionPanelFeaturePresentationSource(
            Supplier<BondedCompanionApi> api,
            java.util.function.LongSupplier clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Map<UUID, CommandPanelFeaturePresentation> snapshot(
            UUID owner, @Nullable String worldKey,
            BondedCompanionPanelRecordSource.PanelSnapshot snapshot) {
        if (owner == null || snapshot == null || snapshot.records().isEmpty()) {
            return Map.of();
        }
        BondedCompanionApi current = currentApi();
        String readiness = current.availability().available()
                ? null : current.availability().reason();
        LinkedHashMap<UUID, CommandPanelFeaturePresentation> result =
                new LinkedHashMap<>();
        for (var record : snapshot.records()) {
            BondedCompanionReviveQuote quote = quote(
                    current, owner, worldKey, record.profile());
            BondedCompanionPanelPresentation row = presentation(
                    record.profile(), clock.getAsLong(), quote, readiness, worldKey);
            result.put(record.presentationUuid(),
                    CommandPanelFeaturePresentation.bonded(row));
        }
        return Map.copyOf(result);
    }

    static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote) {
        return presentation(profile, nowMs, quote, null,
                profile.state() == BondedCompanionState.STORED ? "world" : null);
    }

    private static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote, String readinessReason,
            String worldKey) {
        Map<String, String> source = profile.snapshotPresentationData();
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        LinkedHashMap<String, String> extensions = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key.startsWith("extension:")) {
                extensions.put(key.substring("extension:".length()), value);
            } else if (!"roleId".equals(key) && !"rolePresentation".equals(key)) {
                attributes.put(key, value);
            }
        });
        long cooldown = remaining(profile.summonCooldownUntilMs(), nowMs);
        BondedCompanionStatusPresentation.Action action = switch (profile.state()) {
            case STORED -> BondedCompanionStatusPresentation.Action.SUMMON;
            case ACTIVE -> BondedCompanionStatusPresentation.Action.DISMISS;
            case DEAD -> BondedCompanionStatusPresentation.Action.REVIVE;
        };
        boolean enabled = readinessReason == null && switch (profile.state()) {
            case STORED -> profile.summonAvailable() && cooldown == 0L
                    && worldKey != null && !worldKey.isBlank();
            case ACTIVE -> profile.storeAvailable();
            case DEAD -> profile.reviveAvailable() && quote != null
                    && quote.enabled() && quote.affordable()
                    && quote.cooldownRemainingSeconds() == 0L;
        };
        String reason = enabled ? null : unavailableReason(
                profile, quote, readinessReason, worldKey, cooldown);
        return new BondedCompanionPanelPresentation(
                profile.profileId(), profile.rosterId(), profile.revision(),
                profile.displayName(), profile.species(), profile.gender(),
                source.get("rolePresentation"), attributes, extensions,
                new BondedCompanionStatusPresentation(
                        profile.state(), action, enabled, reason, cooldown), quote);
    }

    private BondedCompanionReviveQuote quote(
            BondedCompanionApi current, UUID owner, String worldKey,
            BondedCompanionProfileView profile) {
        if (profile.state() != BondedCompanionState.DEAD) return profile.reviveQuote();
        if (profile.reviveQuote() != null) return profile.reviveQuote();
        try {
            BondedCompanionResult<BondedCompanionReviveQuote> result =
                    current.quoteRevive(action(owner, worldKey, profile, "quote")).join();
            return result != null && result.successful() ? result.value() : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static BondedCompanionActionRequest action(
            UUID owner, String worldKey, BondedCompanionProfileView profile,
            String operation) {
        return new BondedCompanionActionRequest(
                CALLER, operation + ":" + profile.profileId() + ":" + profile.revision(),
                owner, profile.rosterId(), profile.profileId(),
                profile.revision(), worldKey);
    }

    private static String unavailableReason(BondedCompanionProfileView profile,
            BondedCompanionReviveQuote quote, String readiness, String world,
            long cooldown) {
        if (readiness != null) return readiness;
        if (profile.state() == BondedCompanionState.STORED && cooldown > 0L)
            return "Summon cooldown is still active.";
        if (profile.state() == BondedCompanionState.STORED
                && (world == null || world.isBlank()))
            return "A destination world is required to summon.";
        if (profile.state() == BondedCompanionState.DEAD && quote == null)
            return "Revive quote is unavailable.";
        if (quote != null && quote.cooldownRemainingSeconds() > 0L)
            return "Revive cooldown is still active.";
        if (quote != null && !quote.affordable()) return "Revive cost is not available.";
        return "Bonded roster policy or capacity does not permit this action.";
    }

    private static long remaining(long until, long now) {
        if (until == 0L || until <= now) return 0L;
        try { return Math.subtractExact(until, now); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private BondedCompanionApi currentApi() {
        try {
            BondedCompanionApi current = api.get();
            return current == null ? BondedCompanionApi.unavailable() : current;
        } catch (RuntimeException | LinkageError ignored) {
            return BondedCompanionApi.unavailable();
        }
    }
}
