package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Classifies failures, opens an immediate denial, and persists incidents/quarantines atomically. */
public final class PersistenceIncidentReporter {
    private final String bootId;
    private final PersistenceFailureClassifier classifier;
    private final PersistenceIncidentRepository incidents;
    private final PersistenceQuarantineRepository quarantineRepository;
    private final PersistenceQuarantineRegistry quarantineRegistry;
    private final PersistenceStorageHealthService storageHealth;
    private final PersistenceWriteQueue writeQueue;

    public PersistenceIncidentReporter(@Nonnull String bootId,
                                       @Nonnull PersistenceFailureClassifier classifier,
                                       @Nonnull PersistenceIncidentRepository incidents,
                                       @Nonnull PersistenceQuarantineRepository quarantineRepository,
                                       @Nonnull PersistenceQuarantineRegistry quarantineRegistry,
                                       @Nonnull PersistenceStorageHealthService storageHealth,
                                       @Nonnull PersistenceWriteQueue writeQueue) {
        this.bootId = requireText(bootId);
        this.classifier = classifier;
        this.incidents = incidents;
        this.quarantineRepository = quarantineRepository;
        this.quarantineRegistry = quarantineRegistry;
        this.storageHealth = storageHealth;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public ReportSubmission report(@Nonnull PersistenceFailureContext context) {
        PersistenceFailureClassification classification = classifier.classify(context);
        String fingerprint = fingerprint(context, classification);
        String proposedIncidentId = UUID.randomUUID().toString();
        String incidentId = resolveExistingIncident(fingerprint, classification.scopes())
                .orElse(proposedIncidentId);
        if (classification.storageAuthorityLost()) {
            storageHealth.enterReadOnly(context.reasonCode(), incidentId);
            return new ReportSubmission(incidentId, classification,
                    CompletableFuture.completedFuture(false));
        }

        long now = System.currentTimeMillis();
        PersistenceIncident incident = incident(context, classification, incidentId, fingerprint, now);
        List<PersistenceQuarantineRecord> quarantines = buildQuarantines(
                context, classification, incidentId, now);
        quarantines.forEach(quarantineRegistry::openImmediate);

        PersistenceWriteQueue.WriteSubmission<Void> write = writeQueue.submitTracked(
                "persistence_incident_open",
                connection -> {
                    incidents.upsertOpen(connection, incident, classification.scopes());
                    for (PersistenceQuarantineRecord quarantine : quarantines) {
                        quarantineRepository.insertActive(connection, quarantine);
                    }
                    return null;
                },
                ignored -> reloadQuarantinesAfterCommit(incidentId)
        );
        CompletableFuture<Boolean> durable = write.completion().thenApply(outcome -> {
            if (outcome.isCommitted()) return true;
            storageHealth.enterReadOnly("persistence_quarantine_durable_write_failed", incidentId);
            return false;
        });
        return new ReportSubmission(incidentId, classification, durable);
    }

    private Optional<String> resolveExistingIncident(String fingerprint, List<PersistenceScope> scopes) {
        try {
            return incidents.findEquivalentOpenIncidentId(fingerprint, scopes);
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    private PersistenceIncident incident(PersistenceFailureContext context,
                                         PersistenceFailureClassification classification,
                                         String incidentId, String fingerprint, long now) {
        Throwable failure = context.failure();
        return new PersistenceIncident(
                incidentId, fingerprint, PersistenceIncidentStatus.OPEN, severity(classification),
                classification.failureClass(), classification.disposition(), context.domain(), context.phase(),
                context.reasonCode(), context.operationId(), bootId, now, now, 0L, 1L, 0L,
                failure == null ? null : failure.getClass().getName(),
                failure == null ? null : failure.getMessage(),
                evidenceJson(context), null, UUID.randomUUID().toString()
        );
    }

    private List<PersistenceQuarantineRecord> buildQuarantines(PersistenceFailureContext context,
                                                               PersistenceFailureClassification classification,
                                                               String incidentId, long now) {
        if (classification.disposition() != PersistenceDisposition.SCOPED_QUARANTINE) return List.of();
        List<PersistenceQuarantineRecord> records = new ArrayList<>();
        for (PersistenceScope scope : classification.scopes()) {
            records.add(new PersistenceQuarantineRecord(
                    UUID.randomUUID().toString(), incidentId, scope, context.domain(), context.reasonCode(),
                    PersistenceQuarantineState.ACTIVE, evidenceHash(context, scope), 0L, now, now, 0L, null
            ));
        }
        return List.copyOf(records);
    }

    private void reloadQuarantinesAfterCommit(String incidentId) {
        try {
            quarantineRegistry.reload(quarantineRepository.listActive());
        } catch (Exception failure) {
            storageHealth.enterReadOnly("persistence_quarantine_index_reload_failed", incidentId);
        }
    }

    private PersistenceIncidentSeverity severity(PersistenceFailureClassification classification) {
        if (classification.storageAuthorityLost()) return PersistenceIncidentSeverity.CRITICAL;
        if (classification.disposition() == PersistenceDisposition.SCOPED_QUARANTINE) {
            return PersistenceIncidentSeverity.ERROR;
        }
        return PersistenceIncidentSeverity.WARNING;
    }

    private String fingerprint(PersistenceFailureContext context,
                               PersistenceFailureClassification classification) {
        return "persistence:" + context.domain().name().toLowerCase()
                + ":" + context.phase().name().toLowerCase()
                + ":" + context.reasonCode()
                + ":" + classification.failureClass().name().toLowerCase()
                + ":" + classification.disposition().name().toLowerCase();
    }

    private String evidenceJson(PersistenceFailureContext context) {
        return "{\"transactionOutcome\":\"" + context.transactionOutcome().name()
                + "\",\"durableFenceAvailable\":" + context.durableFenceAvailable()
                + ",\"canonicalStateReadable\":" + context.canonicalStateReadable()
                + ",\"liveMutationMayBeVisible\":" + context.liveMutationMayBeVisible() + "}";
    }

    private String evidenceHash(PersistenceFailureContext context, PersistenceScope scope) {
        String material = context.reasonCode() + "\n" + context.transactionOutcome().name()
                + "\n" + scope.type().name() + "\n" + scope.key();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("bootId");
        return value.trim();
    }

    public record ReportSubmission(@Nonnull String incidentId,
                                   @Nonnull PersistenceFailureClassification classification,
                                   @Nonnull CompletableFuture<Boolean> durableCompletion) {
    }
}
