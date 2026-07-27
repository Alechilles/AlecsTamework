package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Performs bonded panel mutations using only the rendered profile/revision fence. */
final class BondedCompanionPanelActionService {
    static final String CALLER = "tamework:bonded-panel";
    private final Supplier<BondedCompanionApi> api;

    BondedCompanionPanelActionService(@Nonnull Supplier<BondedCompanionApi> api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Nonnull
    // Compatibility entry point for synchronous tests and non-runtime callers.
    // The panel router always uses performAsync so the world thread never waits.
    Outcome perform(@Nonnull Action action, @Nonnull UUID ownerUuid,
                    @Nullable String worldKey,
                    @Nonnull BondedCompanionPanelPresentation row) {
        return perform(action, ownerUuid, worldKey, null, row);
    }

    @Nonnull
    Outcome perform(@Nonnull Action action, @Nonnull UUID ownerUuid,
                    @Nullable String worldKey,
                    @Nullable BondedCompanionActionContext context,
                    @Nonnull BondedCompanionPanelPresentation row) {
        return performAsync(action, ownerUuid, worldKey, context, row)
                .toCompletableFuture().join();
    }

    @Nonnull
    CompletionStage<Outcome> performAsync(
            @Nonnull Action action,
            @Nonnull UUID ownerUuid,
            @Nullable String worldKey,
            @Nullable BondedCompanionActionContext context,
            @Nonnull BondedCompanionPanelPresentation row
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(row, "row");
        if (row.status().action() != mapped(action)
                || !row.status().actionEnabled()) {
            return CompletableFuture.completedFuture(
                    Outcome.denied(row.status().unavailableReason()));
        }
        BondedCompanionActionRequest request = new BondedCompanionActionRequest(
                CALLER,
                operationKey(action.name(), row.profileId(), row.revision()),
                ownerUuid, row.rosterId(), row.profileId(), row.revision(),
                worldKey, context);
        try {
            CompletionStage<? extends BondedCompanionResult<?>> result =
                    switch (action) {
                case SUMMON -> currentApi().summon(request);
                case STORE -> currentApi().store(request);
                case REVIVE -> currentApi().revive(new BondedCompanionReviveRequest(
                        request, row.reviveQuote() == null
                                ? 0L : row.reviveQuote().policyRevision()));
            };
            if (result == null) {
                return CompletableFuture.completedFuture(
                        Outcome.failed("Bonded action returned no result."));
            }
            return result.handle((resolved, failure) -> {
                if (failure != null) return Outcome.failed("Bonded action failed.");
                return resolved != null && resolved.successful()
                        ? Outcome.success() : Outcome.failed(resolved == null
                        ? "Bonded action returned no result." : resolved.reason());
            });
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    Outcome.failed("Bonded action failed."));
        }
    }

    private BondedCompanionApi currentApi() {
        try {
            BondedCompanionApi current = api.get();
            return current == null ? BondedCompanionApi.unavailable() : current;
        } catch (RuntimeException | LinkageError ignored) {
            return BondedCompanionApi.unavailable();
        }
    }

    private static com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation.Action
    mapped(Action action) {
        return switch (action) {
            case SUMMON -> com.alechilles.alecstamework.ui
                    .BondedCompanionStatusPresentation.Action.SUMMON;
            case STORE -> com.alechilles.alecstamework.ui
                    .BondedCompanionStatusPresentation.Action.DISMISS;
            case REVIVE -> com.alechilles.alecstamework.ui
                    .BondedCompanionStatusPresentation.Action.REVIVE;
        };
    }

    static String operationKey(
            String operation, String profileId, long revision) {
        return operation.toLowerCase(java.util.Locale.ROOT) + ":"
                + profileId + ":" + revision;
    }

    enum Action { SUMMON, STORE, REVIVE }

    record Outcome(boolean applied, @Nullable String reason) {
        static Outcome success() { return new Outcome(true, null); }
        static Outcome denied(String reason) { return new Outcome(false,
                reason == null ? "Bonded action is unavailable." : reason); }
        static Outcome failed(String reason) { return new Outcome(false,
                reason == null ? "Bonded action failed." : reason); }
    }
}
