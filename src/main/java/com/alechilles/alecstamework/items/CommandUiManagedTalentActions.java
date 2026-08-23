package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Adapts generic and bonded talent authority to managed command UI flows. */
final class CommandUiManagedTalentActions {
    private final CommandTalentPageService genericTalents;
    private final BondedCompanionTalentPageService bondedTalents;
    private final CommandUiManagedTalentFlowService flows;

    CommandUiManagedTalentActions(
            @Nullable CommandTalentPageService genericTalents,
            @Nullable BondedCompanionTalentPageService bondedTalents
    ) {
        this.genericTalents = genericTalents;
        this.bondedTalents = bondedTalents;
        this.flows = new CommandUiManagedTalentFlowService();
    }

    boolean supportsGeneric() {
        return genericTalents != null;
    }

    boolean supportsBonded() {
        return bondedTalents != null;
    }

    void addGenericAction(
            CommandUiActionCatalog catalog,
            UUID rowId,
            LinkedNpcEntry entry,
            String toolId,
            BooleanSupplier authority,
            Supplier<Player> playerSupplier
    ) {
        if (!supportsGeneric() || !entry.linked()
                || !entry.isTalentsActionVisible()
                || !entry.isTalentsActionEnabled()) return;
        catalog.addRow(rowId, "OPEN_TALENTS", "Talents", genericBinding(
                rowId, entry.npcUuid(), toolId, authority, playerSupplier));
    }

    void addBondedAction(
            CommandUiActionCatalog catalog,
            UUID rowId,
            UUID ownerUuid,
            BondedCompanionPanelPresentation presentation,
            boolean available,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver
    ) {
        if (!supportsBonded() || !available) return;
        catalog.addBondedRow(rowId, "OPEN_TALENTS", "Talents",
                bondedBinding(rowId, ownerUuid, presentation, resolver));
    }

    @Nonnull
    CommandSelectionPageService.GenericUiActionBinding genericBinding(
            @Nonnull UUID rowId,
            @Nonnull UUID npcId,
            @Nonnull String toolId,
            @Nonnull BooleanSupplier authority,
            @Nonnull Supplier<Player> playerSupplier
    ) {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(playerSupplier, "playerSupplier");
        return new CommandSelectionPageService.GenericUiActionBinding(
                new CommandUiAction("OPEN_TALENTS", npcId, null, false),
                authority, unavailable(), false, null,
                CommandUiActionGateway.InputPolicy.NONE, 0,
                session -> flows.open(session, genericContext(
                        rowId, npcId, toolId, authority, playerSupplier)));
    }

    @Nonnull
    CommandSelectionPageService.BondedUiActionBinding bondedBinding(
            @Nonnull UUID rowId,
            @Nonnull UUID ownerUuid,
            @Nonnull BondedCompanionPanelPresentation presentation,
            @Nonnull BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver
    ) {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(resolver, "resolver");
        String rosterId = presentation.rosterId();
        String profileId = presentation.profileId();
        return new CommandSelectionPageService.BondedUiActionBinding(
                new CommandUiAction("OPEN_TALENTS"), ownerUuid, rosterId,
                profileId, resolver, false,
                session -> openBonded(session, rowId, ownerUuid, rosterId,
                        profileId, resolver));
    }

    private CommandUiManagedTalentFlowService.Context genericContext(
            UUID rowId,
            UUID npcId,
            String toolId,
            BooleanSupplier authority,
            Supplier<Player> playerSupplier
    ) {
        return CommandUiManagedTalentFlowService.Context.generic(
                rowId, authority,
                () -> genericSnapshot(playerSupplier.get(), toolId, npcId),
                talentId -> genericMutation(genericTalents.purchaseManaged(
                        playerSupplier.get(), toolId, npcId, talentId)),
                () -> genericMutation(genericTalents.resetManaged(
                        playerSupplier.get(), toolId, npcId)));
    }

    @Nullable
    private CommandUiManagedTalentFlowService.Snapshot genericSnapshot(
            @Nullable Player player,
            String toolId,
            UUID npcId
    ) {
        CommandTalentPageService.ManagedSnapshot snapshot =
                genericTalents.managedSnapshot(player, toolId, npcId);
        return snapshot == null ? null
                : new CommandUiManagedTalentFlowService.Snapshot(
                        snapshot.pageData(), snapshot.level(),
                        snapshot.availablePoints(), Map.of());
    }

    private static CommandUiManagedTalentFlowService.Mutation genericMutation(
            CommandTalentPageService.ManagedMutation result
    ) {
        if (result.applied()) {
            return CommandUiManagedTalentFlowService.Mutation.applied(
                    result.message());
        }
        if (result.notFound()) {
            return CommandUiManagedTalentFlowService.Mutation.notFound(
                    result.message());
        }
        return CommandUiManagedTalentFlowService.Mutation.conflict(
                result.message());
    }

    private java.util.concurrent.CompletionStage<CommandUiActionResult>
    openBonded(
            CommandUiSessionImpl session,
            UUID rowId,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver
    ) {
        LiveBonded current = resolveBonded(
                ownerUuid, rosterId, profileId, resolver);
        if (current == null) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.notFound(
                            "bonded companion talent state is unavailable"));
        }
        BondedCompanionTalentPageService.ManagedTarget target =
                bondedTalents.managedTarget(
                        current.player(), current.presentation());
        if (target == null) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.notFound(
                            "bonded companion has no usable talent state"));
        }
        BooleanSupplier authority = () -> resolveBonded(
                ownerUuid, rosterId, profileId, resolver) != null;
        return flows.open(session, CommandUiManagedTalentFlowService.Context
                .bonded(rowId, profileId, authority,
                        () -> bondedSnapshot(ownerUuid, rosterId, profileId,
                                resolver, target),
                        talentId -> bondedMutation(bondedTalents.purchaseManaged(
                                currentPlayer(ownerUuid, rosterId, profileId,
                                        resolver), target, talentId)),
                        () -> bondedMutation(bondedTalents.resetManaged(
                                currentPlayer(ownerUuid, rosterId, profileId,
                                        resolver), target))));
    }

    @Nullable
    private CommandUiManagedTalentFlowService.Snapshot bondedSnapshot(
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver,
            BondedCompanionTalentPageService.ManagedTarget target
    ) {
        Player player = currentPlayer(ownerUuid, rosterId, profileId, resolver);
        if (player == null) return null;
        BondedCompanionTalentPageService.ManagedSnapshot snapshot =
                bondedTalents.managedSnapshot(player, target);
        if (snapshot == null) return null;
        return new CommandUiManagedTalentFlowService.Snapshot(
                snapshot.pageData(), snapshot.level(),
                snapshot.availablePoints(),
                Map.of("revision", Long.toString(snapshot.revision())));
    }

    private static CommandUiManagedTalentFlowService.Mutation bondedMutation(
            BondedCompanionTalentPageService.ManagedMutation result
    ) {
        if (result.applied()) {
            return CommandUiManagedTalentFlowService.Mutation.applied(
                    result.message());
        }
        if (result.pending()) {
            return CommandUiManagedTalentFlowService.Mutation.pending(
                    result.message());
        }
        return switch (result.code()) {
            case REVISION_CONFLICT -> CommandUiManagedTalentFlowService
                    .Mutation.stale(result.message());
            case NOT_FOUND -> CommandUiManagedTalentFlowService.Mutation
                    .notFound(result.message());
            case NOT_OWNER, POLICY_DENIED -> CommandUiManagedTalentFlowService
                    .Mutation.denied(result.message());
            case UNAVAILABLE, WORLD_UNAVAILABLE ->
                    CommandUiManagedTalentFlowService.Mutation.unavailable(
                            result.message());
            case INVALID_STATE, VALIDATION_FAILED ->
                    CommandUiManagedTalentFlowService.Mutation.conflict(
                            result.message());
            case INTERNAL_FAILURE -> CommandUiManagedTalentFlowService
                    .Mutation.failed(result.message());
            case SUCCESS -> CommandUiManagedTalentFlowService.Mutation
                    .conflict(result.message());
        };
    }

    @Nullable
    private static Player currentPlayer(
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver
    ) {
        LiveBonded current = resolveBonded(
                ownerUuid, rosterId, profileId, resolver);
        return current == null ? null : current.player();
    }

    @Nullable
    private static LiveBonded resolveBonded(
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver resolver
    ) {
        try {
            BondedCompanionPanelActionRouter.CurrentUiContext current =
                    resolver.resolve(ownerUuid, rosterId, profileId);
            if (current == null || current.feature() == null
                    || current.feature().bonded() == null) return null;
            Ref<EntityStore> ref = current.playerRef();
            Store<EntityStore> store = current.store();
            Player player = ref == null || store == null || !ref.isValid()
                    ? null : store.getComponent(ref, Player.getComponentType());
            CommandPanelFeaturePresentation feature = current.feature();
            BondedCompanionPanelPresentation presentation = feature.bonded();
            return player == null ? null
                    : new LiveBonded(player, presentation);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static Supplier<java.util.concurrent.CompletionStage<
            CommandUiActionResult>> unavailable() {
        return () -> CompletableFuture.completedFuture(
                CommandUiActionResult.unavailable(
                        "managed talent flow is unavailable"));
    }

    private record LiveBonded(
            Player player,
            BondedCompanionPanelPresentation presentation
    ) {
    }
}
