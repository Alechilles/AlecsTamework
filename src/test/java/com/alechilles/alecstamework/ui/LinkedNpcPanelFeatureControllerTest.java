package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Regression coverage for feature-row command routing and paid revival. */
class LinkedNpcPanelFeatureControllerTest {
    private static final UUID NPC_UUID =
            UUID.fromString("70000000-0000-0000-0000-000000000001");

    @Test
    void durablePaidRowsNeverFallThroughToFreeRestoration() {
        AtomicInteger revivals = new AtomicInteger();
        LinkedNpcPanelFeatureController controller = controller(
                paidRow(null), revivals
        );
        controller.refresh();

        LinkedNpcPanelFeatureController.Outcome outcome = controller.handle(
                "__respawn__:" + NPC_UUID,
                ignored -> entry()
        );

        assertEquals(
                LinkedNpcPanelFeatureController.Outcome.REFRESH, outcome
        );
        assertEquals(0, revivals.get());
    }

    @Test
    void legacyRowsLeaveRespawnForTheExistingFreePath() {
        LinkedNpcPanelFeatureController controller = controller(
                null, new AtomicInteger()
        );
        controller.refresh();

        assertEquals(
                LinkedNpcPanelFeatureController.Outcome.NOT_HANDLED,
                controller.handle(
                        "__respawn__:" + NPC_UUID,
                        ignored -> entry()
                )
        );
    }

    @Test
    void readyPaidQuoteRequiresExplicitConfirmation() {
        AtomicInteger revivals = new AtomicInteger();
        CommandReviveCostPresentation quote =
                new CommandReviveCostPresentation(
                        PaidCommandRevivalQuote.Status.READY,
                        0L,
                        List.of(new CommandReviveCostPresentation.CostLine(
                                "Ingredient_Life_Essence",
                                "Life Essence",
                                null,
                                3,
                                2
                        )),
                        "revision-1",
                        null,
                        null
                );
        LinkedNpcPanelFeatureController controller = controller(
                paidRow(quote), revivals
        );
        controller.refresh();

        assertEquals(
                LinkedNpcPanelFeatureController.Outcome.REFRESH,
                controller.handle(
                        "__respawn__:" + NPC_UUID,
                        ignored -> entry()
                )
        );
        assertEquals(0, revivals.get());

        assertEquals(
                LinkedNpcPanelFeatureController.Outcome.REFRESH,
                controller.handle(
                        "__revive_confirm__",
                        ignored -> entry()
                )
        );
        assertEquals(1, revivals.get());
    }

    @Test
    void presentationRevisionChangesWhenTimedRowsChange() {
        CommandPanelFeaturePresentation first = paidRow(null);
        CommandPanelFeaturePresentation second =
                new CommandPanelFeaturePresentation(
                        new CommandRosterStatusPresentation(
                                "profile-1",
                                "hydragon:dragon_horn",
                                CommandTimedSummoningState.LOST,
                                2L,
                                null,
                                60_000L,
                                false,
                                0L,
                                0,
                                2,
                                null,
                                null
                        ),
                        null
                );
        java.util.concurrent.atomic.AtomicReference<
                CommandPanelFeaturePresentation> selected =
                new java.util.concurrent.atomic.AtomicReference<>(first);
        LinkedNpcPanelFeatureController controller =
                new LinkedNpcPanelFeatureController(
                        () -> Map.of(NPC_UUID, selected.get()),
                        ignored -> {
                        },
                        ignored -> {
                        },
                        ignored -> {
                        }
                );

        controller.refresh();
        long firstRevision = controller.revision();
        controller.refresh();
        assertEquals(firstRevision, controller.revision());

        selected.set(second);
        controller.refresh();
        assertNotEquals(firstRevision, controller.revision());
    }

    @Test
    void staleBondedSummonEventCannotActAfterCardBecomesActive() {
        AtomicInteger summons = new AtomicInteger();
        BondedCompanionPanelPresentation active =
                new BondedCompanionPanelPresentation(
                        "profile-1", "hydragon:dragons", 5L, "Nimbus",
                        "Miniwyvern", "Male", "Storm Miniwyvern",
                        Map.of(), Map.of(),
                        new BondedCompanionStatusPresentation(
                                com.alechilles.alecstamework.companion.bonded
                                        .BondedCompanionState.ACTIVE,
                                BondedCompanionStatusPresentation.Action.DISMISS,
                                true, null, 0L), null);
        LinkedNpcPanelFeatureController controller =
                new LinkedNpcPanelFeatureController(
                        () -> Map.of(NPC_UUID,
                                CommandPanelFeaturePresentation.bonded(active)),
                        ignored -> summons.incrementAndGet(),
                        ignored -> {}, ignored -> {});
        controller.refresh();

        controller.handle("__roster_summon__:" + NPC_UUID, ignored -> entry());

        assertEquals(0, summons.get());
    }

    private static LinkedNpcPanelFeatureController controller(
            CommandPanelFeaturePresentation row,
            AtomicInteger revivals
    ) {
        Map<UUID, CommandPanelFeaturePresentation> presentations =
                row == null ? Map.of() : Map.of(NPC_UUID, row);
        return new LinkedNpcPanelFeatureController(
                () -> presentations,
                ignored -> {
                },
                ignored -> {
                },
                ignored -> revivals.incrementAndGet()
        );
    }

    private static CommandPanelFeaturePresentation paidRow(
            CommandReviveCostPresentation quote
    ) {
        return new CommandPanelFeaturePresentation(
                new CommandRosterStatusPresentation(
                        "profile-1",
                        "hydragon:dragon_horn",
                        CommandTimedSummoningState.DEAD_REVIVABLE,
                        1L,
                        null,
                        60_000L,
                        false,
                        0L,
                        0,
                        2,
                        null,
                        null
                ),
                quote
        );
    }

    private static LinkedNpcEntry entry() {
        return new LinkedNpcEntry(
                NPC_UUID,
                "Dragon",
                0,
                100,
                0,
                100,
                "",
                0,
                100,
                0,
                100,
                false,
                false,
                true,
                false,
                false,
                false,
                0L,
                new LinkedNpcTraitIndicator[0]
        );
    }
}
