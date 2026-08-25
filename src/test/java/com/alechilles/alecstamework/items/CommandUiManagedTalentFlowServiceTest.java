package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiTalentFlowView;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Observable generic and bonded managed talent-flow behavior. */
class CommandUiManagedTalentFlowServiceTest {
    @Test
    void genericFlowMapsPageDataAndRefreshesAfterPurchaseAndReset() {
        AtomicBoolean purchased = new AtomicBoolean();
        CommandUiManagedTalentFlowService service =
                new CommandUiManagedTalentFlowService();
        CommandUiSessionImpl session = session(
                CommandUiSessionImpl.Mode.GENERIC);
        UUID rowId = UUID.randomUUID();
        var context = CommandUiManagedTalentFlowService.Context.generic(
                rowId, () -> true,
                () -> snapshot(purchased.get(), null),
                talentId -> {
                    purchased.set(true);
                    return CommandUiManagedTalentFlowService.Mutation.applied(
                            "Talent unlocked.");
                },
                () -> {
                    purchased.set(false);
                    return CommandUiManagedTalentFlowService.Mutation.applied(
                            "Talent points refunded.");
                });

        CommandUiTalentFlowView opened = flow(service.open(session, context));

        assertEquals(rowId, opened.rowId());
        assertEquals(4, opened.level());
        assertEquals(2, opened.availablePoints());
        assertEquals("ranching", opened.nodes().getFirst().branchName());
        assertEquals(1, opened.nodes().getFirst().pointCost());
        assertEquals(List.of("starter"),
                opened.nodes().getFirst().requiredTalentIds());
        assertNotNull(opened.nodes().getFirst().purchaseAction());

        CommandUiTalentFlowView afterPurchase = flow(session.invoke(
                opened.nodes().getFirst().purchaseAction().handle()));
        assertEquals("purchased", afterPurchase.nodes().getFirst().state());
        assertNull(afterPurchase.nodes().getFirst().purchaseAction());
        assertNotNull(afterPurchase.resetAction());

        CommandUiActionResult confirmation = session.invoke(
                afterPurchase.resetAction().handle())
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                confirmation.status());
        CommandUiTalentFlowView afterReset = flow(session.invoke(
                confirmation.confirmationHandle()));
        assertEquals("available", afterReset.nodes().getFirst().state());
        session.close();
    }

    @Test
    void bondedFlowKeepsProfileIdentityAndReportsRevisionLoss() {
        CommandUiManagedTalentFlowService service =
                new CommandUiManagedTalentFlowService();
        CommandUiSessionImpl session = session(
                CommandUiSessionImpl.Mode.BONDED);
        UUID rowId = UUID.randomUUID();
        var context = CommandUiManagedTalentFlowService.Context.bonded(
                rowId, "profile-7", () -> true,
                () -> snapshot(false, "12"),
                ignored -> CommandUiManagedTalentFlowService.Mutation.stale(
                        "profile revision changed"),
                () -> CommandUiManagedTalentFlowService.Mutation.stale(
                        "profile revision changed"));
        CommandUiTalentFlowView opened = flow(service.open(session, context));

        assertEquals("profile-7", opened.profileId());
        assertEquals("bonded", opened.metadata().get("route"));
        assertEquals("12", opened.metadata().get("revision"));
        assertEquals(CommandUiActionStatus.STALE,
                session.invoke(opened.nodes().getFirst().purchaseAction()
                                .handle())
                        .toCompletableFuture().join().status());
        session.close();
    }

    @Test
    void lostAuthorityRejectsPurchaseWithoutMutation() {
        AtomicBoolean authority = new AtomicBoolean(true);
        AtomicBoolean mutated = new AtomicBoolean();
        CommandUiManagedTalentFlowService service =
                new CommandUiManagedTalentFlowService();
        CommandUiSessionImpl session = session(
                CommandUiSessionImpl.Mode.GENERIC);
        var context = CommandUiManagedTalentFlowService.Context.generic(
                UUID.randomUUID(), authority::get,
                () -> snapshot(false, null),
                ignored -> {
                    mutated.set(true);
                    return CommandUiManagedTalentFlowService.Mutation.applied(
                            "Talent unlocked.");
                },
                () -> CommandUiManagedTalentFlowService.Mutation.applied(
                        "Talent points refunded."));
        CommandUiTalentFlowView opened = flow(service.open(session, context));

        authority.set(false);
        CommandUiActionResult result = session.invoke(
                opened.nodes().getFirst().purchaseAction().handle())
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.DENIED, result.status());
        assertEquals(false, mutated.get());
        session.close();
    }

    private static CommandUiManagedTalentFlowService.Snapshot snapshot(
            boolean purchased,
            String revision
    ) {
        String state = purchased ? "purchased" : "available";
        var node = new TameworkCompanionTalentsPage.TreeNodeEntry(
                "alpaca-care", "ranching", 2, state, "Alpaca Care",
                "Improves alpaca care.", "Ready", 1, 3,
                List.of("starter"), List.of("Starter"), "+10% care",
                !purchased);
        var page = new TameworkCompanionTalentsPage.PageData(
                "Paca", "Level 4", "2 points", "Choose a talent",
                purchased, List.of(node));
        return new CommandUiManagedTalentFlowService.Snapshot(
                page, 4, purchased ? 1 : 2,
                revision == null ? Map.of() : Map.of("revision", revision));
    }

    private static CommandUiTalentFlowView flow(
            java.util.concurrent.CompletionStage<CommandUiActionResult> stage
    ) {
        CommandUiActionResult result = stage.toCompletableFuture().join();
        return (CommandUiTalentFlowView) result.flowView();
    }

    private static CommandUiSessionImpl session(
            CommandUiSessionImpl.Mode mode
    ) {
        UUID id = UUID.randomUUID();
        return new CommandUiSessionImpl(id,
                new CommandUiSnapshot(id, 1L, 1L, null,
                        List.of(), List.of(),
                        new CommandUiPanelState("linked")),
                new CommandUiActionGateway(),
                CommandUiWorldDispatcher.direct(), mode);
    }
}
