package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.persistence.TameworkDormantSnapshotFactsReader;
import com.alechilles.alecstamework.items.persistence.TameworkRestorationSnapshotResolver;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Composes all restored public feature evidence authors over one read lane,
 * policy snapshot source, and Hytale world-thread evidence source.
 */
public final class ReplacementFeatureEvidenceAuthors {
    private final CommandRosterEvidenceAuthor commandRosters;
    private final TimedSummonEvidenceAuthor timedSummoning;
    private final CompanionProvisioningEvidenceAuthor provisioning;
    private final PaidRevivalEvidenceAuthor paidRevival;

    /**
     * Production constructor used by restored feature composition.
     *
     * <p>The live source is the only remaining Hytale-specific input seam; it
     * must schedule each freeze on the current owner's world thread.</p>
     */
    public ReplacementFeatureEvidenceAuthors(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull ReplacementFeatureLiveEvidenceSource live
    ) {
        this(
                new PublicPersistenceFeatureEvidenceQueries(queries),
                populationGroups,
                new TameworkFeaturePolicySource(),
                live,
                new TameworkDormantSnapshotFactsReader(),
                new TameworkRestorationSnapshotResolver()
        );
    }

    ReplacementFeatureEvidenceAuthors(
            ReplacementFeatureEvidenceQueries queries,
            PopulationGroupConfigRegistry populationGroups,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live,
            TameworkDormantSnapshotFactsReader facts,
            TameworkRestorationSnapshotResolver snapshots
    ) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(populationGroups, "populationGroups");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(snapshots, "snapshots");
        commandRosters = new CommandRosterEvidenceAuthor(queries, live);
        timedSummoning = new TimedSummonEvidenceAuthor(
                queries, populationGroups, live
        );
        provisioning = new CompanionProvisioningEvidenceAuthor(
                new CompanionProvisioningCreationAuthor(
                        queries, populationGroups, policies, live
                ),
                new ProvisionedCompanionTransitionAuthor(
                        queries,
                        populationGroups,
                        policies,
                        live,
                        snapshots
                )
        );
        paidRevival = new PaidRevivalEvidenceAuthor(
                new PaidRevivalQuoteAuthor(
                        queries, policies, live, facts
                ),
                new PaidRevivalRequestAuthor(
                        queries,
                        populationGroups,
                        policies,
                        live,
                        facts,
                        snapshots
                )
        );
    }

    @Nonnull
    public CommandRosterEvidenceAuthor commandRosters() {
        return commandRosters;
    }

    @Nonnull
    public TimedSummonEvidenceAuthor timedSummoning() {
        return timedSummoning;
    }

    @Nonnull
    public CompanionProvisioningEvidenceAuthor provisioning() {
        return provisioning;
    }

    @Nonnull
    public PaidRevivalEvidenceAuthor paidRevival() {
        return paidRevival;
    }
}
