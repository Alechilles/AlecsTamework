package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.deterministicId;

/** Creates the deterministic import identity and logical count manifest. */
final class PublicImportManifestFactory {
    static final int IMPORTER_VERSION = 1;

    @Nonnull
    PublicImportManifest create(
            @Nonnull PublicImportPlan plan,
            @Nonnull LegacySourceFingerprint fingerprint,
            int sourceSchemaVersion,
            @Nonnull String sourceFileName,
            long completedAtMs
    ) {
        if (plan == null || fingerprint == null || sourceFileName == null
                || sourceFileName.isBlank()) {
            throw new IllegalArgumentException("Plan, fingerprint, and source name required");
        }
        String importKey = fingerprint.snapshotSha256()
                + ":" + sourceSchemaVersion + ":" + IMPORTER_VERSION;
        return new PublicImportManifest(
                deterministicId(fingerprint.snapshotSha256(), "import:" + importKey),
                fingerprint.snapshotSha256(),
                sourceSchemaVersion,
                IMPORTER_VERSION,
                sourceFileName + "@" + fingerprint.snapshotSha256(),
                counts(plan).toString(),
                completedAtMs
        );
    }

    private JsonObject counts(PublicImportPlan plan) {
        JsonObject counts = new JsonObject();
        counts.addProperty("profiles", plan.profiles().size());
        counts.addProperty("aliases", plan.aliases().size());
        counts.addProperty("toolLinks", plan.toolLinks().size());
        counts.addProperty("snapshots", plan.snapshots().size());
        counts.addProperty("extensionData", plan.extensionData().size());
        counts.addProperty("coopSlots", plan.coopSlots().size());
        counts.addProperty("coopResidencies", plan.coopResidencies().size());
        counts.addProperty("lifecycles", plan.lifecycles().size());
        counts.addProperty("incidents", plan.incidents().size());
        return counts;
    }
}
