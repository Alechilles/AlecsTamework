package com.alechilles.alecstamework.companion.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.components.TameworkBondedReviveEscrowComponent.Phase;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void paidOrAlreadyRefundedEscrowIsDiscardedWithoutGivingItems() {
        for (Phase phase : List.of(Phase.COMMITTED, Phase.REFUNDED)) {
            List<Stack> given = new ArrayList<>();

            EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(phase,
                    List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity,
                    inventoryWithRoom(100, given));

            assertTrue(given.isEmpty(), phase.name());
            assertEquals(0, settlement.returned(), phase.name());
            assertEquals(5, settlement.discarded(), phase.name());
            assertTrue(settlement.overflow().isEmpty(), phase.name());
        }
    }

    @Test
    void unfinishedEscrowGoesBackToTheInventory() {
        for (Phase phase : List.of(Phase.STAGED, Phase.RESERVED, Phase.REFUNDING, Phase.QUARANTINED)) {
            List<Stack> given = new ArrayList<>();

            EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(phase,
                    List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity,
                    inventoryWithRoom(100, given));

            assertEquals(List.of(new Stack("Gem", 3), new Stack("Bone", 2)), given, phase.name());
            assertEquals(5, settlement.returned(), phase.name());
            assertEquals(0, settlement.discarded(), phase.name());
            assertTrue(settlement.overflow().isEmpty(), phase.name());
        }
    }

    @Test
    void whatDoesNotFitIsLeftToDrop() {
        List<Stack> given = new ArrayList<>();

        EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(Phase.RESERVED,
                List.of(new Stack("Gem", 3), new Stack("Bone", 4), new Stack("Hide", 2)), Stack::quantity,
                inventoryWithRoom(5, given));

        assertEquals(List.of(new Stack("Gem", 3), new Stack("Bone", 2)), given);
        assertEquals(5, settlement.returned());
        assertEquals(List.of(new Stack("Bone", 2), new Stack("Hide", 2)), settlement.overflow());
    }

    @Test
    void aStackWhoseHandOutFailsIsDroppedWhole() {
        EscrowRefund.Settlement<Stack> settlement = EscrowRefund.settle(Phase.STAGED,
                List.of(new Stack("Gem", 3), new Stack("Bone", 2)), Stack::quantity, stack -> {
                    if (stack.item().equals("Gem")) {
                        throw new IllegalStateException("inventory unavailable");
                    }
                    return null;
                });

        assertEquals(2, settlement.returned());
        assertEquals(List.of(new Stack("Gem", 3)), settlement.overflow());
    }
}
