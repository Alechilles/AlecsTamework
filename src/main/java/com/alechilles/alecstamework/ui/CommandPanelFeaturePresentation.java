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
    private final boolean readOnly;

    /** A managed companion shown through a generic item has no generic mutation actions. */
    public static CommandPanelFeaturePresentation readOnlyManaged() {
        return new CommandPanelFeaturePresentation();
    }

    private CommandPanelFeaturePresentation() {
        roster = null;
        revival = null;
        bonded = null;
        readOnly = true;
    }

    public CommandPanelFeaturePresentation(
            @Nonnull CommandRosterStatusPresentation roster,
            @Nullable CommandReviveCostPresentation revival
    ) {
        this.roster = java.util.Objects.requireNonNull(
                roster, "Roster presentation is required.");
        this.revival = revival;
        this.bonded = null;
        this.readOnly = false;
    }

    private CommandPanelFeaturePresentation(
            BondedCompanionPanelPresentation bonded
    ) {
        this.roster = null;
        this.bonded = java.util.Objects.requireNonNull(bonded, "bonded");
        this.revival = bondedRevival(bonded);
        this.readOnly = false;
    }

    private CommandPanelFeaturePresentation(
            CommandReviveCostPresentation revival
    ) {
        this.roster = null;
        this.bonded = null;
        this.revival = java.util.Objects.requireNonNull(revival, "revival");
        this.readOnly = false;
    }

    /**
     * A dead ordinary companion whose revive costs items. The card keeps its normal actions; only
     * Revive opens the cost dialog instead of charging at once.
     */
    public static CommandPanelFeaturePresentation paidRevival(
            @Nonnull CommandReviveCostPresentation revival
    ) {
        return new CommandPanelFeaturePresentation(revival);
    }

    /** True for a {@link #paidRevival} row, which has no roster or bonded state. */
    public boolean ordinaryPaidRevival() {
        return !readOnly && roster == null && bonded == null && revival != null;
    }

    public static CommandPanelFeaturePresentation bonded(
            @Nonnull BondedCompanionPanelPresentation bonded
    ) {
        return new CommandPanelFeaturePresentation(bonded);
    }

    @Nullable public CommandRosterStatusPresentation roster() { return roster; }
    @Nullable public CommandReviveCostPresentation revival() { return revival; }
    @Nullable public BondedCompanionPanelPresentation bonded() { return bonded; }

    public boolean managesRosterRow() { return readOnly || roster != null || bonded != null; }

    public boolean managesPaidRevival() {
        return bonded != null
                ? bonded.status().state()
                        == com.alechilles.alecstamework.api
                        .BondedCompanionStateView.DEAD
                : roster != null ? roster.paidRevivalState() : ordinaryPaidRevival();
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
                quote.costs().stream().map(cost ->
                        new CommandReviveCostPresentation.CostLine(
                                cost.itemId(), cost.itemId(), null,
                                cost.ownedQuantity(), cost.requiredQuantity()))
                        .toList();
        return new CommandReviveCostPresentation(
                status, quote.cooldownRemainingSeconds() * 1_000L, costs,
                Long.toString(quote.policyRevision()), null, null);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandPanelFeaturePresentation that)) return false;
        return readOnly == that.readOnly && java.util.Objects.equals(roster, that.roster)
                && java.util.Objects.equals(revival, that.revival)
                && java.util.Objects.equals(bonded, that.bonded);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(roster, revival, bonded, readOnly);
    }
}
