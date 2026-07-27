package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds bonded status/actions from the same immutable profiles as card data. */
final class BondedCompanionPanelFeaturePresentationSource {
    private final java.util.function.LongSupplier clock;

    BondedCompanionPanelFeaturePresentationSource(
            java.util.function.LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Map<UUID, CommandPanelFeaturePresentation> snapshot(
            UUID owner, @Nullable String worldKey,
            BondedCompanionPanelRecordSource.PanelSnapshot snapshot) {
        return snapshot(owner, worldKey, snapshot, ignored -> null);
    }

    Map<UUID, CommandPanelFeaturePresentation> snapshot(
            UUID owner, @Nullable String worldKey,
            BondedCompanionPanelRecordSource.PanelSnapshot snapshot,
            java.util.function.Function<BondedCompanionProfileView,
                    BondedCompanionActionContext> contexts) {
        if (owner == null || snapshot == null || snapshot.records().isEmpty()) {
            return Map.of();
        }
        BondedCompanionActionBlockReason snapshotBlock = snapshot.trusted()
                ? null : snapshot.state()
                == BondedCompanionPanelSnapshotCache.State.FAILED
                ? BondedCompanionActionBlockReason.REFRESH_FAILED
                : BondedCompanionActionBlockReason.REFRESHING;
        LinkedHashMap<UUID, CommandPanelFeaturePresentation> result =
                new LinkedHashMap<>();
        for (var record : snapshot.records()) {
            BondedCompanionActionContext context = contexts.apply(
                    record.profile());
            QuoteResolution resolved = quote(
                    owner, record.profile(), context);
            BondedCompanionPanelPresentation row = presentation(
                    record.profile(), clock.getAsLong(), resolved.quote(),
                    context, snapshotBlock, resolved.inventoryTrusted(),
                    worldKey);
            result.put(record.presentationUuid(),
                    CommandPanelFeaturePresentation.bonded(row));
        }
        return Map.copyOf(result);
    }

    static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote) {
        BondedCompanionActionContext context = new BondedCompanionActionContext(
                new BondedCompanionPlacement(
                        "world", 0D, 0D, 0D, 0F, 0F, 0F), null);
        return presentation(profile, nowMs, quote, context, null,
                true, profile.state() == BondedCompanionStateView.ACTIVE
                        ? profile.activeLease().worldKey() : "world");
    }

    static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote,
            BondedCompanionActionContext context, String worldKey) {
        return presentation(profile, nowMs, quote, context, null, worldKey);
    }

    private static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote,
            BondedCompanionActionContext context, String readinessReason,
            String worldKey) {
        BondedCompanionActionBlockReason readiness = readinessReason == null
                ? null
                : BondedCompanionActionBlockReason.AUTHORITY_UNAVAILABLE;
        return presentation(profile, nowMs, quote, context, readiness,
                true, worldKey);
    }

    private static BondedCompanionPanelPresentation presentation(
            BondedCompanionProfileView profile, long nowMs,
            BondedCompanionReviveQuote quote,
            BondedCompanionActionContext context,
            BondedCompanionActionBlockReason snapshotBlock,
            boolean inventoryTrusted,
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
        BondedCompanionActionBlockReason block = snapshotBlock == null
                ? unavailableReason(profile, quote, context, worldKey,
                cooldown, inventoryTrusted) : snapshotBlock;
        boolean enabled = block == null && switch (profile.state()) {
            case STORED -> profile.summonAvailable() && cooldown == 0L
                    && validPlacement(context, worldKey);
            case ACTIVE -> profile.storeAvailable()
                    && profile.activeLease() != null
                    && profile.activeLease().worldKey().equals(worldKey);
            case DEAD -> profile.reviveAvailable() && quote != null
                    && quote.enabled() && quote.affordable()
                    && quote.cooldownRemainingSeconds() == 0L;
        };
        if (!enabled && block == null) {
            block = BondedCompanionActionBlockReason.GENERIC_FAILURE;
        }
        return new BondedCompanionPanelPresentation(
                profile.profileId(), profile.rosterId(), profile.roleId(),
                profile.revision(),
                profile.displayName(), profile.species(), profile.gender(),
                source.get("rolePresentation"), attributes, extensions,
                new BondedCompanionStatusPresentation(
                        profile.state(), action, enabled, block, null,
                        cooldown), quote);
    }

    private QuoteResolution quote(
            UUID owner,
            BondedCompanionProfileView profile,
            BondedCompanionActionContext context) {
        BondedCompanionReviveQuote template = profile.reviveQuote();
        if (profile.state() != BondedCompanionStateView.DEAD) {
            return new QuoteResolution(template, true);
        }
        if (template == null || context == null
                || context.inventory() == null) {
            return new QuoteResolution(template, false);
        }
        try {
            List<BondedCompanionReviveCost> costs = template.costs().stream()
                    .map(line -> new BondedCompanionReviveCost(
                            line.itemId(), line.requiredQuantity())).toList();
            String key = BondedCompanionPanelActionService.operationKey(
                    "revive", profile.profileId(), profile.revision());
            String operationId = BondedCompanionPaymentOperationId.create(
                    BondedCompanionPanelActionService.CALLER, key, owner,
                    profile.rosterId(), profile.profileId(), profile.revision());
            List<Integer> owned = context.inventory().availableQuantities(
                    operationId, costs);
            if (owned == null || owned.size() != costs.size()) {
                return new QuoteResolution(template, false);
            }
            ArrayList<BondedCompanionReviveQuote.CostLine> lines =
                    new ArrayList<>(costs.size());
            for (int index = 0; index < costs.size(); index++) {
                lines.add(new BondedCompanionReviveQuote.CostLine(
                        costs.get(index).itemId(),
                        costs.get(index).quantity(),
                        Math.max(0, owned.get(index))));
            }
            return new QuoteResolution(new BondedCompanionReviveQuote(
                    template.profileId(), template.enabled(), lines,
                    template.cooldownRemainingSeconds(),
                    template.policyRevision()), true);
        } catch (RuntimeException | LinkageError ignored) {
            return new QuoteResolution(template, false);
        }
    }

    static BondedCompanionActionRequest action(
            UUID owner, String worldKey, BondedCompanionProfileView profile,
            String operation) {
        return action(owner, worldKey, profile, operation, null);
    }

    static BondedCompanionActionRequest action(
            UUID owner, String worldKey, BondedCompanionProfileView profile,
            String operation, BondedCompanionActionContext context) {
        return new BondedCompanionActionRequest(
                BondedCompanionPanelActionService.CALLER,
                BondedCompanionPanelActionService.operationKey(
                        operation, profile.profileId(), profile.revision()),
                owner, profile.rosterId(), profile.profileId(),
                profile.revision(), worldKey, context);
    }

    private static BondedCompanionActionBlockReason unavailableReason(
            BondedCompanionProfileView profile,
            BondedCompanionReviveQuote quote,
            BondedCompanionActionContext context, String readiness,
            String world, long cooldown) {
        return unavailableReason(profile, quote, context, world, cooldown,
                true);
    }

    private static BondedCompanionActionBlockReason unavailableReason(
            BondedCompanionProfileView profile,
            BondedCompanionReviveQuote quote,
            BondedCompanionActionContext context,
            String world,
            long cooldown,
            boolean inventoryTrusted) {
        if (profile.state() == BondedCompanionStateView.STORED && cooldown > 0L)
            return BondedCompanionActionBlockReason.COOLDOWN_ACTIVE;
        if (profile.state() == BondedCompanionStateView.STORED
                && !validPlacement(context, world))
            return BondedCompanionActionBlockReason.PLACEMENT_UNAVAILABLE;
        if (profile.state() == BondedCompanionStateView.STORED
                && !profile.summonAvailable())
            return BondedCompanionActionBlockReason.POLICY_DENIED;
        if (profile.state() == BondedCompanionStateView.ACTIVE
                && profile.activeLease() != null
                && !profile.activeLease().worldKey().equals(world))
            return BondedCompanionActionBlockReason.WORLD_UNAVAILABLE;
        if (profile.state() == BondedCompanionStateView.ACTIVE
                && (!profile.storeAvailable() || profile.activeLease() == null))
            return BondedCompanionActionBlockReason.INVALID_STATE;
        if (profile.state() == BondedCompanionStateView.DEAD && quote == null)
            return BondedCompanionActionBlockReason.POLICY_DENIED;
        if (quote != null && !quote.enabled())
            return BondedCompanionActionBlockReason.FEATURE_DISABLED;
        if (quote != null && quote.cooldownRemainingSeconds() > 0L)
            return BondedCompanionActionBlockReason.COOLDOWN_ACTIVE;
        if (profile.state() == BondedCompanionStateView.DEAD
                && (!profile.reviveAvailable() || !inventoryTrusted
                || !quote.affordable()))
            return BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE;
        return null;
    }

    private static boolean validPlacement(
            BondedCompanionActionContext context, String worldKey) {
        return context != null && context.summonPlacement() != null
                && worldKey != null
                && worldKey.equals(context.summonPlacement().worldKey());
    }

    private static long remaining(long until, long now) {
        if (until == 0L || until <= now) return 0L;
        try { return Math.subtractExact(until, now); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private record QuoteResolution(
            @Nullable BondedCompanionReviveQuote quote,
            boolean inventoryTrusted) {}
}
