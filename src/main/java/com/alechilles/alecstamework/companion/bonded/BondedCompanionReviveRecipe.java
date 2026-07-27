package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.util.List;
import java.util.Objects;

/** Canonical immutable authority for one ordered bonded-revival recipe. */
final class BondedCompanionReviveRecipe {
    private BondedCompanionReviveRecipe() {
    }

    static List<BondedCompanionReviveCost> copyOf(
            List<BondedCompanionReviveCost> costs
    ) {
        List<BondedCompanionReviveCost> recipe = List.copyOf(
                Objects.requireNonNull(costs, "costs")
        );
        if (recipe.isEmpty()) {
            throw new IllegalArgumentException("costs must not be empty");
        }
        return recipe;
    }

    static boolean matches(
            BondedCompanionPolicy.RevivePrice price,
            List<BondedCompanionReviveCost> paid
    ) {
        return price != null && price.costs().equals(paid);
    }

    /** Length-prefixes every item ID so distinct ordered recipes cannot alias. */
    static String fingerprint(List<BondedCompanionReviveCost> costs) {
        StringBuilder encoded = new StringBuilder();
        for (BondedCompanionReviveCost cost : costs) {
            encoded.append(cost.itemId().length()).append(':')
                    .append(cost.itemId()).append(':')
                    .append(cost.quantity()).append(';');
        }
        return encoded.toString();
    }
}
