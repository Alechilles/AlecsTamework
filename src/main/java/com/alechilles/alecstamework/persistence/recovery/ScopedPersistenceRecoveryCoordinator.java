package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEvent;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEventKind;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentSink;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceReadbackStrategy;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/** Coalesces bounded recovery triggers and clears fences only through registered evidence verifiers. */
public final class ScopedPersistenceRecoveryCoordinator implements AutoCloseable {
    static final long INITIAL_RETRY_DELAY_MS = 1_000L;
    static final long MAX_RETRY_DELAY_MS = 300_000L;

    private final PersistenceIncidentRepository incidents;
    private final PersistenceQuarantineRepository quarantineRepository;
    private final PersistenceQuarantineRegistry quarantines;
    private final PersistenceFeatureCircuitRegistry circuits;
    private final PersistenceWriteQueue writeQueue;
    private final ScheduledExecutorService executor;
    private final PersistenceIncidentSink incidentSink;
    private final Map<PersistenceDomain, ScopedPersistenceRecoveryVerifier> verifiers =
            new EnumMap<>(PersistenceDomain.class);
    private final ConcurrentHashMap<String, CompletableFuture<RecoveryResult>> active =
            new ConcurrentHashMap<>();

    public ScopedPersistenceRecoveryCoordinator(
            @Nonnull PersistenceIncidentRepository incidents,
            @Nonnull PersistenceQuarantineRepository quarantineRepository,
            @Nonnull PersistenceQuarantineRegistry quarantines,
            @Nonnull PersistenceFeatureCircuitRegistry circuits,
            @Nonnull PersistenceWriteQueue writeQueue) {
        this(incidents, quarantineRepository, quarantines, circuits, writeQueue,
                Executors.newSingleThreadScheduledExecutor(daemonFactory()),
                PersistenceIncidentSink.NO_OP);
    }

    public ScopedPersistenceRecoveryCoordinator(
            @Nonnull PersistenceIncidentRepository incidents,
            @Nonnull PersistenceQuarantineRepository quarantineRepository,
            @Nonnull PersistenceQuarantineRegistry quarantines,
            @Nonnull PersistenceFeatureCircuitRegistry circuits,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull PersistenceIncidentSink incidentSink) {
        this(incidents, quarantineRepository, quarantines, circuits, writeQueue,
                Executors.newSingleThreadScheduledExecutor(daemonFactory()), incidentSink);
    }

    ScopedPersistenceRecoveryCoordinator(
            PersistenceIncidentRepository incidents,
            PersistenceQuarantineRepository quarantineRepository,
            PersistenceQuarantineRegistry quarantines,
            PersistenceFeatureCircuitRegistry circuits,
            PersistenceWriteQueue writeQueue,
            ScheduledExecutorService executor,
            PersistenceIncidentSink incidentSink) {
        this.incidents = incidents;
        this.quarantineRepository = quarantineRepository;
        this.quarantines = quarantines;
        this.circuits = circuits;
        this.writeQueue = writeQueue;
        this.executor = executor;
        this.incidentSink = incidentSink;
    }

    public synchronized void register(@Nonnull ScopedPersistenceRecoveryVerifier verifier) {
        ScopedPersistenceRecoveryVerifier previous = verifiers.putIfAbsent(verifier.domain(), verifier);
        if (previous != null) {
            throw new IllegalArgumentException("Verifier already registered for " + verifier.domain());
        }
    }

    @Nonnull
    public CompletableFuture<RecoveryResult> request(@Nonnull String incidentId,
                                                      @Nonnull ScopedRecoveryTrigger trigger) {
        return active.computeIfAbsent(incidentId, ignored -> {
            CompletableFuture<RecoveryResult> result = new CompletableFuture<>();
            executor.execute(() -> runAndComplete(incidentId, trigger, result));
            return result;
        });
    }

    public int requestMatching(@Nonnull PersistenceScope scope,
                               @Nonnull ScopedRecoveryTrigger trigger) {
        int submitted = 0;
        for (PersistenceQuarantineRecord record : quarantines.snapshot()) {
            if (record.scope().lookupKey().equals(scope.lookupKey())) {
                request(record.incidentId(), trigger);
                submitted++;
            }
        }
        return submitted;
    }

    public void scheduleOpenIncidentsAfterStartup() {
        executor.schedule(() -> {
            try {
                for (PersistenceIncident incident : incidents.listOpen(500)) {
                    request(incident.incidentId(), ScopedRecoveryTrigger.STARTUP);
                }
            } catch (Exception ignored) {
                // A storage-level failure is handled by the global recovery path.
            }
        }, INITIAL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void runAndComplete(String incidentId, ScopedRecoveryTrigger trigger,
                                CompletableFuture<RecoveryResult> future) {
        RecoveryResult result;
        try {
            result = recover(incidentId, trigger);
        } catch (Exception failure) {
            result = new RecoveryResult(RecoveryStatus.FAILED,
                    "scoped_recovery_failed", null, failure);
        }
        future.complete(result);
        active.remove(incidentId, future);
        if (shouldRetry(trigger, result)) scheduleRetry(incidentId, result.attempts());
    }

    private RecoveryResult recover(String incidentId, ScopedRecoveryTrigger trigger) throws Exception {
        if (trigger != ScopedRecoveryTrigger.OPERATOR_REQUEST
                && !circuits.isEnabled(PersistenceDomain.AUTOMATIC_SCOPED_RECOVERY)) {
            return new RecoveryResult(RecoveryStatus.FEATURE_PAUSED,
                    "automatic_scoped_recovery_paused", null, null);
        }
        Optional<PersistenceIncident> found = incidents.findById(incidentId);
        if (found.isEmpty()) return RecoveryResult.notFound();
        PersistenceIncident incident = found.orElseThrow();
        List<PersistenceQuarantineRecord> fences =
                quarantineRepository.listActiveForIncident(incidentId);
        if (fences.isEmpty()) {
            return new RecoveryResult(RecoveryStatus.NOT_RECOVERABLE,
                    "incident_has_no_active_quarantine", incident.recoveryAttempts(), null);
        }
        ScopedPersistenceRecoveryVerifier verifier = verifier(incident.domain());
        if (verifier == null) {
            return new RecoveryResult(RecoveryStatus.OPERATOR_ONLY,
                    "domain_recovery_verifier_unavailable", incident.recoveryAttempts(), null);
        }
        if (!begin(incident, fences)) {
            return new RecoveryResult(RecoveryStatus.COALESCED,
                    "incident_recovery_state_changed", incident.recoveryAttempts(), null);
        }
        ScopedRecoveryVerification verification;
        try {
            verification = verifier.verify(new ScopedRecoveryContext(incident, fences, trigger));
        } catch (Exception failure) {
            verification = new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.FAILED, "verifier_failed", Map.of(), null, failure);
        }
        if (!verification.resolution().isResolved()) {
            retain(incidentId, verification);
            record(incident, fences, PersistenceIncidentEventKind.RECOVERY_FAILED,
                    verification.resolutionCode());
            return new RecoveryResult(RecoveryStatus.RETAINED,
                    verification.resolutionCode(), incident.recoveryAttempts() + 1L,
                    verification.failure());
        }
        try {
            if (verification.indexPublisher() != null) verification.indexPublisher().publish();
        } catch (Exception publicationFailure) {
            ScopedRecoveryVerification failed = new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.FAILED, "runtime_index_publication_failed",
                    Map.of(), null, publicationFailure);
            retain(incidentId, failed);
            record(incident, fences, PersistenceIncidentEventKind.RECOVERY_FAILED,
                    failed.resolutionCode());
            return new RecoveryResult(RecoveryStatus.RETAINED, failed.resolutionCode(),
                    incident.recoveryAttempts() + 1L, publicationFailure);
        }
        clear(incident, fences, verifier, verification);
        record(incident, fences, PersistenceIncidentEventKind.QUARANTINE_CLEARED,
                verification.resolutionCode());
        record(incident, fences, PersistenceIncidentEventKind.RECOVERY_COMPLETED,
                verification.resolutionCode());
        return new RecoveryResult(RecoveryStatus.RESOLVED,
                verification.resolutionCode(), incident.recoveryAttempts() + 1L, null);
    }

    private boolean begin(PersistenceIncident incident,
                          List<PersistenceQuarantineRecord> fences) throws Exception {
        long now = System.currentTimeMillis();
        PersistenceWriteQueue.WriteOutcome<Boolean> outcome = submit(
                metadata("scoped_recovery_begin", incident, fences), connection -> {
                    boolean began = incidents.beginRecovery(
                            connection, incident.incidentId(), incident.recoveryAttempts(), now);
                    if (began) quarantineRepository.markVerifying(connection, fences, now);
                    return began;
                });
        return outcome.isCommitted() && Boolean.TRUE.equals(outcome.value());
    }

    private void retain(String incidentId, ScopedRecoveryVerification verification) throws Exception {
        long now = System.currentTimeMillis();
        PersistenceIncident incident = incidents.findById(incidentId).orElseThrow();
        List<PersistenceQuarantineRecord> fences = quarantineRepository.listActiveForIncident(incidentId);
        PersistenceWriteQueue.WriteOutcome<Void> outcome = submit(
                metadata("scoped_recovery_retain", incident, fences), connection -> {
                    incidents.retainOpen(connection, incidentId, verification.resolutionCode(),
                            verification.failure(), now);
                    quarantineRepository.retainActive(connection, incidentId, now);
                    return null;
                });
        requireCommit(outcome, "scoped_recovery_retain_failed");
        reloadFences();
    }

    private void clear(PersistenceIncident incident,
                       List<PersistenceQuarantineRecord> fences,
                       ScopedPersistenceRecoveryVerifier verifier,
                       ScopedRecoveryVerification verification) throws Exception {
        long now = System.currentTimeMillis();
        PersistenceWriteQueue.WriteOutcome<Void> outcome = submit(
                metadata("scoped_recovery_resolve", incident, fences), connection -> {
                    quarantineRepository.clearVerified(connection, fences,
                            verification.evidenceHashes(), verifier.verifierId(), now);
                    incidents.resolve(connection, incident.incidentId(),
                            verification.resolutionCode(), now);
                    return null;
                });
        requireCommit(outcome, "scoped_recovery_resolution_failed");
        reloadFences();
    }

    private <T> PersistenceWriteQueue.WriteOutcome<T> submit(
            PersistenceOperationMetadata metadata,
            PersistenceWriteQueue.SqlWork<T> work) throws Exception {
        PersistenceWriteQueue.WriteSubmission<T> submission =
                writeQueue.submitTracked(metadata, work, null);
        return submission.completion().get(30L, TimeUnit.SECONDS);
    }

    private PersistenceOperationMetadata metadata(String taskName,
                                                   PersistenceIncident incident,
                                                   List<PersistenceQuarantineRecord> fences) {
        List<PersistenceScope> scopes = new ArrayList<>();
        for (PersistenceQuarantineRecord fence : fences) scopes.add(fence.scope());
        return PersistenceOperationMetadata.builder(taskName, PersistenceDomain.AUTOMATIC_SCOPED_RECOVERY)
                .operationId(incident.operationId())
                .atomicGroupId("recovery-" + incident.incidentId())
                .phase(PersistenceOperationPhase.RECOVERY)
                .scopes(scopes)
                .readbackStrategy(PersistenceReadbackStrategy.CUSTOM_VERIFIER)
                .durableFenceAvailable(true)
                .canonicalStateReadable(true)
                .build();
    }

    private synchronized ScopedPersistenceRecoveryVerifier verifier(PersistenceDomain domain) {
        return verifiers.get(domain);
    }

    private void reloadFences() throws Exception {
        quarantines.reload(quarantineRepository.listActive());
    }

    private void record(PersistenceIncident incident, List<PersistenceQuarantineRecord> fences,
                        PersistenceIncidentEventKind kind, String result) {
        try {
            List<PersistenceScope> scopes = new ArrayList<>();
            for (PersistenceQuarantineRecord fence : fences) scopes.add(fence.scope());
            incidentSink.record(PersistenceIncidentEvent.from(incident, kind, scopes, result));
        } catch (Throwable ignored) {
            // Diagnostics cannot alter recovery.
        }
    }

    private void requireCommit(PersistenceWriteQueue.WriteOutcome<?> outcome, String reason) {
        if (!outcome.isCommitted()) {
            throw new IllegalStateException(reason, outcome.failure());
        }
    }

    private boolean shouldRetry(ScopedRecoveryTrigger trigger, RecoveryResult result) {
        return trigger != ScopedRecoveryTrigger.OPERATOR_REQUEST
                && (result.status() == RecoveryStatus.RETAINED
                    || result.status() == RecoveryStatus.FAILED)
                && result.attempts() != null
                && result.attempts() < 10L;
    }

    private void scheduleRetry(String incidentId, long attempts) {
        long shift = Math.max(0L, Math.min(18L, attempts - 1L));
        long delay = Math.min(MAX_RETRY_DELAY_MS, INITIAL_RETRY_DELAY_MS << shift);
        executor.schedule(() -> request(incidentId, ScopedRecoveryTrigger.BOUNDED_RETRY),
                delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "tamework-scoped-persistence-recovery");
            thread.setDaemon(true);
            return thread;
        };
    }

    public enum RecoveryStatus {
        RESOLVED,
        RETAINED,
        COALESCED,
        OPERATOR_ONLY,
        FEATURE_PAUSED,
        NOT_FOUND,
        NOT_RECOVERABLE,
        FAILED
    }

    public record RecoveryResult(@Nonnull RecoveryStatus status,
                                 @Nonnull String reason,
                                 Long attempts,
                                 Throwable failure) {
        private static RecoveryResult notFound() {
            return new RecoveryResult(RecoveryStatus.NOT_FOUND, "incident_not_found", null, null);
        }
    }
}
