package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Exact schema and identity checks for managed-coop projection markers. */
final class ManagedCoopProjectionMarkerPolicy {
    private static final String MANAGED_RELEASE_KIND = "MANAGED_COOP_RELEASE";
    private static final String MANAGED_IMPORT_ADOPTION_KIND =
            "MANAGED_COOP_IMPORT_ADOPTION";
    private static final Pattern RELEASE_OPERATION_ID = Pattern.compile(
            "managed-coop-release:[0-9a-f]{64}");
    private static final Pattern IMPORT_OPERATION_ID = Pattern.compile(
            "managed-coop-import-operation:[0-9a-f]{64}");
    private static final long FINALIZED_RELEASE_MARKER_GENERATION = 1L;

    private ManagedCoopProjectionMarkerPolicy() {
    }

    static boolean matchesFinalizedRelease(@Nullable MarkerEvidence marker,
                                           ResidentRecord resident) {
        if (marker == null || !canonicalReleaseOperationId(marker.operationId())) {
            return false;
        }
        return matchesRelease(
                marker,
                marker.operationId(),
                resident.profileId(),
                resident.authorityKey().slotKey(resident.residentSlot()),
                resident.sourceNpcUuid(),
                FINALIZED_RELEASE_MARKER_GENERATION
        ) && resident.sourceNpcUuid() != null
                && !resident.sourceNpcUuid().equals(resident.deployedNpcUuid());
    }

    static boolean matchesFinalizedImport(@Nullable MarkerEvidence marker,
                                          ResidentRecord resident) {
        return marker != null
                && marker.operationId() != null
                && IMPORT_OPERATION_ID.matcher(marker.operationId()).matches()
                && canonicalText(marker.profileId())
                && MANAGED_IMPORT_ADOPTION_KIND.equals(marker.projectionKind())
                && resident.profileId().equals(marker.profileId())
                && resident.authorityKey().slotKey(resident.residentSlot()).equals(marker.slotKey())
                && resident.deployedNpcUuid() != null
                && resident.deployedNpcUuid().equals(marker.sourceNpcUuid())
                && resident.generation() == marker.generation();
    }

    static boolean matchesRelease(@Nullable MarkerEvidence marker,
                                  String operationId,
                                  String profileId,
                                  String slotKey,
                                  @Nullable UUID sourceNpcUuid,
                                  long generation) {
        return marker != null
                && canonicalText(marker.operationId())
                && canonicalText(marker.profileId())
                && MANAGED_RELEASE_KIND.equals(marker.projectionKind())
                && operationId.equals(marker.operationId())
                && profileId.equals(marker.profileId())
                && slotKey.equals(marker.slotKey())
                && sourceNpcUuid != null
                && sourceNpcUuid.equals(marker.sourceNpcUuid())
                && marker.generation() == generation;
    }

    private static boolean canonicalReleaseOperationId(@Nullable String operationId) {
        return operationId != null && RELEASE_OPERATION_ID.matcher(operationId).matches();
    }

    private static boolean canonicalText(@Nullable String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }
}
