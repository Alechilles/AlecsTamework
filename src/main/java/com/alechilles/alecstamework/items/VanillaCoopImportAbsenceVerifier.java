package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Verifies exact source absence against the complete immutable pre-import source multiset. */
public final class VanillaCoopImportAbsenceVerifier {
    public enum Status {
        VERIFIED,
        BLOCKED
    }

    public record Result(@Nonnull Status status,
                         @Nullable NeutralizationProof proof,
                         @Nullable String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            if ((status == Status.VERIFIED) != (proof != null)) {
                throw new IllegalArgumentException("verified absence must carry a proof");
            }
        }
    }

    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final String bootId;

    public VanillaCoopImportAbsenceVerifier() {
        this(new VanillaCoopImportEvidenceCodec(), UUID.randomUUID().toString());
    }

    VanillaCoopImportAbsenceVerifier(@Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
                                     @Nonnull String bootId) {
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        if (bootId == null || bootId.isBlank()) {
            throw new IllegalArgumentException("bootId must not be blank");
        }
        this.bootId = bootId.trim();
    }

    /**
     * Proves the current list is an exact subset of the immutable audit and the target is absent.
     */
    @Nonnull
    public Result verify(@Nonnull SessionRecord session,
                         @Nonnull List<SourceRecord> originalSources,
                         @Nonnull SourceRecord target,
                         @Nonnull VanillaCoopImportAdapter.AuditResult currentAudit,
                         long verifiedAtMs) {
        Objects.requireNonNull(session, "session");
        originalSources = List.copyOf(originalSources);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(currentAudit, "currentAudit");
        if (verifiedAtMs == 0L || !currentAudit.readable()
                || !session.envelope().layoutId().equals(currentAudit.layoutId())) {
            return blocked("current_supported_audit_and_signed_timestamp_required");
        }
        if (!session.envelope().sessionId().equals(target.sessionId())
                || target.dispositionCommandId() == null) {
            return blocked("target_source_not_bound_to_session");
        }
        try {
            LinkedHashMap<String, Integer> original = originalMultiset(originalSources);
            SourcePlan targetPlan = evidenceCodec.decodeSourcePlan(target.evidence());
            if (targetPlan.multiplicity() != 1) {
                return blocked("grouped_source_cannot_be_absence_verified");
            }
            LinkedHashMap<String, Integer> current = currentMultiset(currentAudit);
            for (Map.Entry<String, Integer> entry : current.entrySet()) {
                if (entry.getValue() > original.getOrDefault(entry.getKey(), 0)) {
                    return blocked("current_vanilla_sources_are_not_an_exact_audit_subset");
                }
            }
            if (current.getOrDefault(targetPlan.stablePayload(), 0) != 0) {
                return blocked("target_source_still_present");
            }
            String proofJson = proofJson(session, target, original, current, verifiedAtMs);
            return new Result(
                    Status.VERIFIED,
                    new NeutralizationProof(
                            session.envelope().sessionId(),
                            target.evidence().sourceId(),
                            session.envelope().auditFingerprint(),
                            target.evidence().sourceFingerprint(),
                            target.evidence().sourcePayloadHash(),
                            target.evidence().sourceSlot(),
                            target.evidence().sourceOrder(),
                            target.evidence().persistentUuid(),
                            target.dispositionCommandId(),
                            proofJson,
                            VanillaCoopImportEvidenceCodec.sha256(proofJson),
                            1,
                            verifiedAtMs
                    ),
                    null
            );
        } catch (RuntimeException exception) {
            return blocked("absence_evidence_invalid:" + detail(exception));
        }
    }

    /**
     * Returns whether a persisted proof was produced by this verifier instance (this runtime boot).
     * Older proofs remain valid audit evidence, but cannot authorize current-boot finalization.
     */
    boolean isCurrentBootProof(@Nonnull SourceRecord source) {
        Objects.requireNonNull(source, "source");
        if (source.neutralizationState() != NeutralizationState.VERIFIED_ABSENT
                || source.absenceProofVersion() != 1
                || source.absenceProofJson() == null
                || source.absenceProofHash() == null
                || !VanillaCoopImportEvidenceCodec.sha256(source.absenceProofJson())
                .equals(source.absenceProofHash())) {
            return false;
        }
        try {
            JsonObject proof = JsonParser.parseString(source.absenceProofJson()).getAsJsonObject();
            return proof.get("version").getAsInt() == 1
                    && bootId.equals(proof.get("verificationBootId").getAsString())
                    && source.sessionId().equals(proof.get("sessionId").getAsString())
                    && proof.has("auditFingerprint")
                    && source.evidence().sourceId().equals(proof.get("sourceId").getAsString())
                    && source.evidence().sourceFingerprint().equals(
                    proof.get("sourceFingerprint").getAsString())
                    && source.evidence().sourcePayloadHash().equals(
                    proof.get("sourcePayloadHash").getAsString());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private LinkedHashMap<String, Integer> originalMultiset(List<SourceRecord> sources) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (SourceRecord source : sources) {
            SourcePlan plan = evidenceCodec.decodeSourcePlan(source.evidence());
            counts.merge(plan.stablePayload(), plan.multiplicity(), Integer::sum);
        }
        return sorted(counts);
    }

    private LinkedHashMap<String, Integer> currentMultiset(
            VanillaCoopImportAdapter.AuditResult audit) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String payload : evidenceCodec.sortedStablePayloads(audit)) {
            counts.merge(payload, 1, Integer::sum);
        }
        return sorted(counts);
    }

    private LinkedHashMap<String, Integer> sorted(Map<String, Integer> source) {
        LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private String proofJson(SessionRecord session,
                             SourceRecord target,
                             Map<String, Integer> original,
                             Map<String, Integer> current,
                             long verifiedAtMs) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("verificationBootId", bootId);
        root.addProperty("layoutId", session.envelope().layoutId());
        root.addProperty("sessionId", session.envelope().sessionId());
        root.addProperty("auditFingerprint", session.envelope().auditFingerprint());
        root.addProperty("sourceId", target.evidence().sourceId());
        root.addProperty("sourceFingerprint", target.evidence().sourceFingerprint());
        root.addProperty("sourcePayloadHash", target.evidence().sourcePayloadHash());
        root.addProperty("verifiedAtMs", verifiedAtMs);
        root.add("originalPayloadCounts", counts(original));
        root.add("currentPayloadCounts", counts(current));
        root.addProperty("currentResidentCount",
                current.values().stream().mapToInt(Integer::intValue).sum());
        return root.toString();
    }

    private JsonArray counts(Map<String, Integer> source) {
        JsonArray values = new JsonArray();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            JsonObject value = new JsonObject();
            value.addProperty("payloadHash", VanillaCoopImportEvidenceCodec.sha256(entry.getKey()));
            value.addProperty("count", entry.getValue());
            values.add(value);
        }
        return values;
    }

    private Result blocked(String detail) {
        return new Result(Status.BLOCKED, null, detail);
    }

    private String detail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
