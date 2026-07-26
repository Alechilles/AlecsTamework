package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Performs bonded panel mutations using only the rendered profile/revision fence. */
final class BondedCompanionPanelActionService {
    private static final String CALLER = "tamework:bonded-panel";
    private final Supplier<BondedCompanionApi> api;

    BondedCompanionPanelActionService(@Nonnull Supplier<BondedCompanionApi> api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Nonnull
    Outcome perform(@Nonnull Action action, @Nonnull UUID ownerUuid,
                    @Nullable String worldKey,
                    @Nonnull BondedCompanionPanelPresentation row) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(row, "row");
        if (row.status().action() != mapped(action)
                || !row.status().actionEnabled()) {
            return Outcome.denied(row.status().unavailableReason());
        }
        BondedCompanionActionRequest request = new BondedCompanionActionRequest(
                CALLER,
                action.name().toLowerCase(java.util.Locale.ROOT) + ":"
                        + row.profileId() + ":" + row.revision(),
                ownerUuid, row.rosterId(), row.profileId(), row.revision(), worldKey);
        try {
            BondedCompanionResult<?> result = switch (action) {
                case SUMMON -> currentApi().summon(request).join();
                case STORE -> currentApi().store(request).join();
                case REVIVE -> currentApi().revive(new BondedCompanionReviveRequest(
                        request, row.reviveQuote() == null
                                ? 0L : row.reviveQuote().policyRevision())).join();
            };
            return result != null && result.successful()
                    ? Outcome.success() : Outcome.failed(result == null
                            ? "Bonded action returned no result." : result.reason());
        } catch (RuntimeException | LinkageError failure) {
            return Outcome.failed("Bonded action failed.");
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

    enum Action { SUMMON, STORE, REVIVE }

    record Outcome(boolean applied, @Nullable String reason) {
        static Outcome success() { return new Outcome(true, null); }
        static Outcome denied(String reason) { return new Outcome(false,
                reason == null ? "Bonded action is unavailable." : reason); }
        static Outcome failed(String reason) { return new Outcome(false,
                reason == null ? "Bonded action failed." : reason); }
    }
}
