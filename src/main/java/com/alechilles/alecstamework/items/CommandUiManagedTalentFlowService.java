package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiTalentFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiTalentNodeView;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds lazy detached talent flows from the standard talent-page model. */
final class CommandUiManagedTalentFlowService {
    @Nonnull
    CompletionStage<CommandUiActionResult> open(
            @Nonnull CommandUiSessionImpl session,
            @Nonnull Context context
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        if (!context.allows()) {
            return completed(CommandUiActionResult.denied(
                    "current talent authority denied the action"));
        }
        try {
            Snapshot snapshot = context.snapshot();
            return snapshot == null
                    ? completed(CommandUiActionResult.notFound(
                            "companion talent state is unavailable"))
                    : completed(CommandUiActionResult.presented(
                            build(session, context, snapshot)));
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "talent flow could not be opened"));
        }
    }

    @Nonnull
    private CommandUiTalentFlowView build(
            CommandUiSessionImpl session,
            Context context,
            Snapshot snapshot
    ) {
        session.beginManagedFlow();
        TameworkCompanionTalentsPage.PageData page = snapshot.pageData();
        List<CommandUiTalentNodeView> nodes = new ArrayList<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry
                : page.entries()) {
            if (entry == null) continue;
            CommandUiActionView purchase = entry.canPurchase()
                    ? action(session, context, "PURCHASE_TALENT",
                            "Unlock", entry.id(), false) : null;
            nodes.add(new CommandUiTalentNodeView(
                    entry.id(), entry.branchName(), entry.tier(), entry.state(),
                    entry.displayName(), entry.description(), entry.status(),
                    entry.pointCost(), entry.minLevel(),
                    entry.requiredTalentIds(), entry.requiredTalentNames(),
                    entry.effectSummary(), purchase));
        }
        CommandUiActionView reset = page.canReset()
                ? action(session, context, "RESET_TALENTS",
                        "Reset talents", null, true) : null;
        Map<String, String> metadata = new LinkedHashMap<>(
                snapshot.metadata());
        metadata.put("route", context.route() ==
                CommandUiActionGateway.Route.BONDED ? "bonded" : "generic");
        return new CommandUiTalentFlowView(
                context.rowId(), context.profileId(), page.companionName(),
                snapshot.level(), snapshot.availablePoints(),
                page.levelSummary(), page.pointsSummary(), page.statusText(),
                reset, nodes, metadata);
    }

    @Nonnull
    private CommandUiActionView action(
            CommandUiSessionImpl session,
            Context context,
            String kind,
            String label,
            @Nullable String talentId,
            boolean confirmation
    ) {
        CommandUiAction action = new CommandUiAction(
                kind, null, talentId, confirmation);
        var handle = session.issueManaged(
                context.route(), action, ignored -> context.allows(),
                (bound, ignored) -> execute(session, context, bound),
                CommandUiActionGateway.InputPolicy.NONE, 0, confirmation);
        return new CommandUiActionView(kind, label, true, null,
                confirmation, handle);
    }

    @Nonnull
    private CompletionStage<CommandUiActionResult> execute(
            CommandUiSessionImpl session,
            Context context,
            CommandUiAction action
    ) {
        Mutation mutation;
        try {
            mutation = switch (action.builtInKind()) {
                case PURCHASE_TALENT -> context.purchase(action.value());
                case RESET_TALENTS -> context.reset();
                default -> Mutation.denied("talent action is not supported");
            };
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "talent action failed"));
        }
        if (mutation == null) {
            return completed(CommandUiActionResult.failed(
                    "talent action returned no result"));
        }
        if (mutation.outcome() != Outcome.APPLIED) {
            return completed(result(mutation));
        }
        if (!context.allows()) {
            return completed(CommandUiActionResult.denied(
                    "current talent authority was lost"));
        }
        Snapshot snapshot = context.snapshot();
        return snapshot == null
                ? completed(CommandUiActionResult.notFound(
                        "updated talent state is unavailable"))
                : completed(CommandUiActionResult.updated(
                        mutation.message(), build(session, context, snapshot)));
    }

    private static CommandUiActionResult result(Mutation mutation) {
        return switch (mutation.outcome()) {
            case APPLIED -> CommandUiActionResult.applied(mutation.message());
            case STALE -> CommandUiActionResult.stale(mutation.message());
            case NOT_FOUND -> CommandUiActionResult.notFound(mutation.message());
            case DENIED -> CommandUiActionResult.denied(mutation.message());
            case CONFLICT -> CommandUiActionResult.conflict(mutation.message());
            case UNAVAILABLE -> CommandUiActionResult.unavailable(
                    mutation.message());
            case FAILED -> CommandUiActionResult.failed(mutation.message());
            case PENDING -> new CommandUiActionResult(
                    CommandUiActionStatus.ACCEPTED, mutation.message(),
                    null, null, Map.of(), null, false);
        };
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> completed(
            CommandUiActionResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    record Snapshot(
            @Nonnull TameworkCompanionTalentsPage.PageData pageData,
            int level,
            int availablePoints,
            @Nullable Map<String, String> metadata
    ) {
        Snapshot {
            Objects.requireNonNull(pageData, "pageData");
            level = Math.max(0, level);
            availablePoints = Math.max(0, availablePoints);
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }
    }

    record Context(
            @Nonnull CommandUiActionGateway.Route route,
            @Nonnull UUID rowId,
            @Nullable String profileId,
            @Nonnull BooleanSupplier authority,
            @Nonnull Supplier<Snapshot> snapshotSupplier,
            @Nonnull Function<String, Mutation> purchaseOperation,
            @Nonnull Supplier<Mutation> resetOperation
    ) {
        Context {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(rowId, "rowId");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
            Objects.requireNonNull(purchaseOperation, "purchaseOperation");
            Objects.requireNonNull(resetOperation, "resetOperation");
            profileId = profileId == null || profileId.isBlank()
                    ? null : profileId.trim();
        }

        static Context generic(
                UUID rowId,
                BooleanSupplier authority,
                Supplier<Snapshot> snapshotSupplier,
                Function<String, Mutation> purchaseOperation,
                Supplier<Mutation> resetOperation
        ) {
            return new Context(CommandUiActionGateway.Route.GENERIC, rowId,
                    null, authority, snapshotSupplier, purchaseOperation,
                    resetOperation);
        }

        static Context bonded(
                UUID rowId,
                String profileId,
                BooleanSupplier authority,
                Supplier<Snapshot> snapshotSupplier,
                Function<String, Mutation> purchaseOperation,
                Supplier<Mutation> resetOperation
        ) {
            return new Context(CommandUiActionGateway.Route.BONDED, rowId,
                    profileId, authority, snapshotSupplier, purchaseOperation,
                    resetOperation);
        }

        boolean allows() {
            try {
                return authority.getAsBoolean();
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        @Nullable Snapshot snapshot() { return snapshotSupplier.get(); }
        @Nullable Mutation purchase(String talentId) {
            return purchaseOperation.apply(talentId);
        }
        @Nullable Mutation reset() { return resetOperation.get(); }
    }

    record Mutation(@Nonnull Outcome outcome, @Nonnull String message) {
        Mutation {
            Objects.requireNonNull(outcome, "outcome");
            message = message == null ? "" : message.trim();
        }

        static Mutation applied(String message) {
            return new Mutation(Outcome.APPLIED, message);
        }
        static Mutation stale(String message) {
            return new Mutation(Outcome.STALE, message);
        }
        static Mutation notFound(String message) {
            return new Mutation(Outcome.NOT_FOUND, message);
        }
        static Mutation denied(String message) {
            return new Mutation(Outcome.DENIED, message);
        }
        static Mutation conflict(String message) {
            return new Mutation(Outcome.CONFLICT, message);
        }
        static Mutation unavailable(String message) {
            return new Mutation(Outcome.UNAVAILABLE, message);
        }
        static Mutation pending(String message) {
            return new Mutation(Outcome.PENDING, message);
        }
        static Mutation failed(String message) {
            return new Mutation(Outcome.FAILED, message);
        }
    }

    enum Outcome {
        APPLIED,
        STALE,
        NOT_FOUND,
        DENIED,
        CONFLICT,
        UNAVAILABLE,
        PENDING,
        FAILED
    }
}
