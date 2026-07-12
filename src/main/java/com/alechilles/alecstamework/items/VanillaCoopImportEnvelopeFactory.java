package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Builds the canonical coop-level audit envelope and its deterministic session identity.
 *
 * <p>Property insertion order and source ordering are compatibility-sensitive: the resulting JSON
 * is hashed and acts as the exact operator approval and replay identity.</p>
 */
final class VanillaCoopImportEnvelopeFactory {
    private final VanillaCoopImportEvidenceCodec evidenceCodec;

    VanillaCoopImportEnvelopeFactory(@Nonnull VanillaCoopImportEvidenceCodec evidenceCodec) {
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
    }

    @Nonnull
    SessionEnvelope create(@Nonnull ManagedCoopAuthorityKey authorityKey,
                           @Nonnull String coopId,
                           int maximumResidents,
                           @Nonnull VanillaCoopImportAdapter.AuditResult audit,
                           @Nonnull List<SourceEvidence> sources,
                           int importGeneration,
                           long auditedAtMs) {
        Objects.requireNonNull(authorityKey, "authorityKey");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(sources, "sources");
        if (importGeneration < 1) {
            throw new IllegalArgumentException("importGeneration must be positive");
        }
        String producePayload = evidenceCodec.copyProducePayload(
                audit.coop().rawProduceStorage());
        String auditEnvelope = auditEnvelope(
                authorityKey, coopId, maximumResidents, importGeneration,
                audit, sources, producePayload);
        String auditFingerprint = VanillaCoopImportEvidenceCodec.sha256(auditEnvelope);
        String sessionId = "managed-coop-import:" + VanillaCoopImportEvidenceCodec.sha256(
                token(authorityKey.authorityId()) + token(auditFingerprint));
        return new SessionEnvelope(
                sessionId,
                authorityKey,
                coopId,
                VanillaCoopImportEvidenceCodec.AUDIT_VERSION,
                auditFingerprint,
                auditEnvelope,
                VanillaCoopImportEvidenceCodec.sha256(auditEnvelope),
                audit.layoutId(),
                audit.coop().coopAssetId(),
                audit.coop().residentListClassName(),
                producePayload,
                VanillaCoopImportEvidenceCodec.sha256(producePayload),
                VanillaCoopImportEvidenceCodec.sha256("begin:" + sessionId),
                auditedAtMs
        );
    }

    private String auditEnvelope(ManagedCoopAuthorityKey authorityKey,
                                 String coopId,
                                 int maximumResidents,
                                 int importGeneration,
                                 VanillaCoopImportAdapter.AuditResult audit,
                                 List<SourceEvidence> sources,
                                 String producePayload) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VanillaCoopImportEvidenceCodec.AUDIT_VERSION);
        root.addProperty("layoutId", audit.layoutId());
        root.addProperty("authorityId", authorityKey.authorityId());
        root.addProperty("worldName", authorityKey.worldName());
        root.addProperty("coopId", coopId);
        root.addProperty("x", authorityKey.x());
        root.addProperty("y", authorityKey.y());
        root.addProperty("z", authorityKey.z());
        root.addProperty("maximumResidents", maximumResidents);
        root.addProperty(ManagedCoopImportGeneration.ENVELOPE_FIELD, importGeneration);
        if (audit.coop().coopAssetId() != null) {
            root.addProperty("coopAssetId", audit.coop().coopAssetId());
        }
        root.addProperty("residentListClass", audit.coop().residentListClassName());
        root.addProperty("rawResidentCount", audit.coop().sourceResidentCount());
        root.addProperty("producePayloadHash",
                VanillaCoopImportEvidenceCodec.sha256(producePayload));
        JsonArray sourceHashes = new JsonArray();
        sources.stream().map(SourceEvidence::sourceFingerprint).sorted().forEach(sourceHashes::add);
        root.add("sourceFingerprints", sourceHashes);
        JsonArray sourcePlans = new JsonArray();
        sources.stream().sorted(Comparator.comparing(SourceEvidence::sourceId)).forEach(source -> {
            JsonObject plan = new JsonObject();
            plan.addProperty("sourceId", source.sourceId());
            plan.addProperty("sourceFingerprint", source.sourceFingerprint());
            plan.addProperty("sourceEnvelopeHash", source.sourceEnvelopeHash());
            sourcePlans.add(plan);
        });
        root.add("sourcePlans", sourcePlans);
        return root.toString();
    }

    private static String token(String value) {
        String normalized = requireText(value, "token");
        return normalized.length() + ":" + normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
