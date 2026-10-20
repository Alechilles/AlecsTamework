package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionCaptureEvidenceView;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * The capture evidence of a companion captured into bonded storage (plan 6 R17), as the JSON
 * value of the record's {@link BondedRecords#CAPTURE_EVIDENCE_KEY} extension entry. The entry is
 * in Tamework's own namespace, which public extension and profile data calls refuse, and it goes
 * away with the record's other extensions when the companion is abandoned or released.
 */
public final class BondedCaptureEvidence {
    private BondedCaptureEvidence() {
    }

    /**
     * The JSON stored for {@code evidence}. The profile id is not stored: the entry is on the
     * companion's own record, and {@link #read} fills it from there, so the JSON can be built
     * before an unstamped body's record exists.
     */
    @Nonnull
    public static String toJson(@Nonnull BondedCompanionCaptureEvidenceView evidence) {
        BsonDocument doc = new BsonDocument()
                .append("operationId", new BsonString(evidence.operationId().toString()))
                .append("attemptId", new BsonString(evidence.attemptId().toString()))
                .append("ownerUuid", new BsonString(evidence.ownerUuid().toString()))
                .append("rosterId", new BsonString(evidence.rosterId()))
                .append("familyId", new BsonString(evidence.familyId()))
                .append("sourceNpcUuid", new BsonString(evidence.sourceNpcUuid().toString()))
                .append("roleId", new BsonString(evidence.roleId()))
                .append("callerNamespace", new BsonString(evidence.callerNamespace()))
                .append("idempotencyKey", new BsonString(evidence.idempotencyKey()))
                .append("sourceItemId", new BsonString(evidence.sourceItemId()))
                .append("spawnerConfigId", new BsonString(evidence.spawnerConfigId()))
                .append("spawnerConfigRevision", new BsonInt64(evidence.spawnerConfigRevision()))
                .append("capturePolicyConfigRevision", new BsonInt64(evidence.capturePolicyConfigRevision()))
                .append("sourceConsumption", new BsonString(evidence.sourceConsumption().name()))
                .append("reason", new BsonString(evidence.reason()))
                .append("sourceWorldKey", new BsonString(evidence.sourceWorldKey()))
                .append("committedAtMs", new BsonInt64(evidence.committedAtMs()));
        if (evidence.capturePolicyConfigId() != null) {
            doc.append("capturePolicyConfigId", new BsonString(evidence.capturePolicyConfigId()));
        }
        return doc.toJson();
    }

    /** The capture evidence stored on {@code record}; null when it has none or it cannot be read. */
    @Nullable
    public static BondedCompanionCaptureEvidenceView read(@Nonnull CompanionRecord record) {
        ExtensionEntry entry = record.extensions().get(BondedRecords.CAPTURE_EVIDENCE_KEY);
        if (entry == null) {
            return null;
        }
        try {
            BsonDocument doc = BsonDocument.parse(entry.json());
            return new BondedCompanionCaptureEvidenceView(
                    uuid(doc, "operationId"), uuid(doc, "attemptId"), uuid(doc, "ownerUuid"),
                    text(doc, "rosterId"), text(doc, "familyId"), uuid(doc, "sourceNpcUuid"),
                    record.profileId().toString(), text(doc, "roleId"), text(doc, "callerNamespace"),
                    text(doc, "idempotencyKey"), text(doc, "sourceItemId"), text(doc, "spawnerConfigId"),
                    number(doc, "spawnerConfigRevision"),
                    doc.containsKey("capturePolicyConfigId") ? text(doc, "capturePolicyConfigId") : null,
                    number(doc, "capturePolicyConfigRevision"),
                    CaptureSourceConsumption.valueOf(text(doc, "sourceConsumption")),
                    CaptureSuccessDisposition.STORE_BONDED_COMPANION, CaptureAttemptOutcome.CAPTURED,
                    text(doc, "reason"), text(doc, "sourceWorldKey"), number(doc, "committedAtMs"));
        } catch (RuntimeException unreadable) {
            // Written by another version or damaged: the capture is then reported as not found.
            return null;
        }
    }

    private static String text(BsonDocument doc, String key) {
        return doc.getString(key).getValue();
    }

    /** A JSON writer may store a small long as a 32-bit number. */
    private static long number(BsonDocument doc, String key) {
        return doc.getNumber(key).longValue();
    }

    private static UUID uuid(BsonDocument doc, String key) {
        return UUID.fromString(text(doc, key));
    }
}
