package com.alechilles.alecstamework.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Feature-specific presentation attached to a panel row without expanding the
 * legacy {@link LinkedNpcEntry} view model.
 */
public final class CommandPanelFeaturePresentation {
    @Nullable private final CommandRosterStatusPresentation roster;
    @Nullable private final CommandReviveCostPresentation revival;
    @Nullable private final BondedCompanionPanelPresentation bonded;

    public CommandPanelFeaturePresentation(
            @Nonnull CommandRosterStatusPresentation roster,
            @Nullable CommandReviveCostPresentation revival
    ) {
        this.roster = java.util.Objects.requireNonNull(
                roster, "Roster presentation is required.");
        this.revival = revival;
        this.bonded = null;
    }

    private CommandPanelFeaturePresentation(
            BondedCompanionPanelPresentation bonded
    ) {
        this.roster = null;
        this.bonded = java.util.Objects.requireNonNull(bonded, "bonded");
        this.revival = bondedRevival(bonded);
    }

    public static CommandPanelFeaturePresentation bonded(
            @Nonnull BondedCompanionPanelPresentation bonded
    ) {
        return new CommandPanelFeaturePresentation(bonded);
    }

    @Nullable public CommandRosterStatusPresentation roster() { return roster; }
    @Nullable public CommandReviveCostPresentation revival() { return revival; }
    @Nullable public BondedCompanionPanelPresentation bonded() { return bonded; }

    public boolean managesRosterRow() { return roster != null || bonded != null; }

    public boolean managesPaidRevival() {
        return bonded != null
                ? bonded.status().state()
                        == com.alechilles.alecstamework.companion.bonded.BondedCompanionState.DEAD
                : roster != null && roster.paidRevivalState();
    }

    private static CommandReviveCostPresentation bondedRevival(
            BondedCompanionPanelPresentation bonded) {
        var quote = bonded.reviveQuote();
        if (quote == null) return null;
        com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status status;
        if (!quote.enabled()) {
            status = com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.DISABLED;
        } else if (quote.cooldownRemainingSeconds() > 0L) {
            status = com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.COOLDOWN;
        } else if (!quote.affordable()) {
            status = com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.INSUFFICIENT_COST;
        } else {
            status = com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.READY;
        }
        java.util.List<CommandReviveCostPresentation.CostLine> costs =
                quote.itemId() == null ? java.util.List.of() : java.util.List.of(
                        new CommandReviveCostPresentation.CostLine(
                                quote.itemId(), quote.itemId(), null,
                                quote.affordable() ? quote.quantity() : 0,
                                quote.quantity()));
        return new CommandReviveCostPresentation(
                status, quote.cooldownRemainingSeconds() * 1_000L, costs,
                Long.toString(quote.policyRevision()), null, null);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandPanelFeaturePresentation that)) return false;
        return java.util.Objects.equals(roster, that.roster)
                && java.util.Objects.equals(revival, that.revival)
                && java.util.Objects.equals(bonded, that.bonded);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(roster, revival, bonded);
    }
}
