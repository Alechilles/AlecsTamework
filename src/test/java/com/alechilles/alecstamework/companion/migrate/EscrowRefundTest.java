package com.alechilles.alecstamework.companion.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.components.TameworkBondedReviveEscrowComponent.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class EscrowRefundTest {
    /** A stand-in stack: only the quantity matters to the settlement. */
    private record Stack(String item, int quantity) {
    }

    /** An inventory with room for {@code room} more items. */
    private static UnaryOperator<Stack> inventoryWithRoom(int room, List<Stack> given) {
        int[] left = {room};
        return stack -> {
            int fits = Math.min(left[0], stack.quantity());
            left[0] -= fits;
            if (fits > 0) {
                given.add(new Stack(stack.item(), fits));
            }
            return fits == stack.quantity() ? null : new Stack(stack.item(), stack.quantity() - fits);
        };
    }

    /** A ground that takes every drop and records it. */
    private static Predicate<List<Stack>> ground(List<Stack> dropped) {
        return overflow -> {
            dropped.addAll(overflow);
            return true;
        };
    }

    @Test
    void paidOrAlreadyRefundedEscrowIsDiscardedWithoutGivingItems() {
        for (Phase phase : List.of(Phase.COMMITTED, Phase.REFUNDED)) {
            List<Stack> given = new ArrayList<>();
            List<Stack> dropped = new ArrayList<>();

            EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(phase,
                    List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity,
                    inventoryWithRoom(100, given), ground(dropped));

            assertTrue(given.isEmpty(), phase.name());
            assertTrue(dropped.isEmpty(), phase.name());
            assertEquals(new EscrowRefund.Settlement<Stack>(0, 0, 5, List.of()), settlement, phase.name());
        }
    }

    @Test
    void unfinishedEscrowGoesBackToTheInventory() {
        for (Phase phase : List.of(Phase.STAGED, Phase.RESERVED, Phase.REFUNDING, Phase.QUARANTINED)) {
            List<Stack> given = new ArrayList<>();
            List<Stack> dropped = new ArrayList<>();

            EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(phase,
                    List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity,
                    inventoryWithRoom(100, given), ground(dropped));

            assertEquals(List.of(new Stack("Gem", 3), new Stack("Bone", 2)), given, phase.name());
            assertTrue(dropped.isEmpty(), phase.name());
            assertEquals(new EscrowRefund.Settlement<Stack>(5, 0, 0, List.of()), settlement, phase.name());
        }
    }

    @Test
    void whatDoesNotFitIsDropped() {
        List<Stack> given = new ArrayList<>();
        List<Stack> dropped = new ArrayList<>();

        EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(Phase.RESERVED,
                List.of(new Stack("Gem", 3), new Stack("Bone", 4), new Stack("Hide", 2)), Stack::quantity,
                inventoryWithRoom(5, given), ground(dropped));

        assertEquals(List.of(new Stack("Gem", 3), new Stack("Bone", 2)), given);
        assertEquals(List.of(new Stack("Bone", 2), new Stack("Hide", 2)), dropped);
        assertEquals(new EscrowRefund.Settlement<Stack>(5, 4, 0, List.of()), settlement);
    }

    @Test
    void aStackWhoseHandOutFailsIsDroppedWhole() {
        List<Stack> dropped = new ArrayList<>();

        EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(Phase.STAGED,
                List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity, stack -> {
                    if (stack.item().equals("Gem")) {
                        throw new IllegalStateException("inventory unavailable");
                    }
                    return null;
                }, ground(dropped));

        assertEquals(List.of(new Stack("Gem", 3)), dropped);
        assertEquals(new EscrowRefund.Settlement<Stack>(2, 3, 0, List.of()), settlement);
    }

    @Test
    void overflowThatCannotBeDroppedIsKeptForTheNextJoin() {
        List<Predicate<List<Stack>>> failingDrops = List.of(
                overflow -> false,
                overflow -> {
                    throw new IllegalStateException("no position");
                });
        for (Predicate<List<Stack>> drop : failingDrops) {
            List<Stack> given = new ArrayList<>();

            EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(Phase.RESERVED,
                    List.of(new Stack("Gem", 3), new Stack("Bone", 4)), Stack::quantity,
                    inventoryWithRoom(5, given), drop);

            // What fit stays given and is not counted again; only the remainder waits.
            assertEquals(List.of(new Stack("Gem", 3), new Stack("Bone", 2)), given);
            assertEquals(new EscrowRefund.Settlement<Stack>(5, 0, 0, List.of(new Stack("Bone", 2))), settlement);
        }
    }
}
