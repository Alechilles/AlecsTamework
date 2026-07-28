package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Package-local mutable drafts used only while compiling an immutable public import plan. */
final class PublicImportPlanningModel {
    private PublicImportPlanningModel() {
    }

    record Identity(
            LinkedHashMap<String, ProfileDraft> profiles,
            List<PublicImportPlan.Alias> aliases,
            List<PublicImportPlan.ToolLink> toolLinks,
            List<PublicImportPlan.ExtensionData> extensions
    ) {
    }

    record Lifecycle(
            List<PublicImportPlan.Snapshot> snapshots,
            List<PublicImportPlan.CoopSlot> coopSlots,
            List<PublicImportPlan.CoopResidency> coopResidencies,
            List<PublicImportPlan.Lifecycle> lifecycles,
            List<PublicImportPlan.Incident> incidents
    ) {
    }

    static final class ProfileDraft {
        private final LegacyPublicData.Profile source;
        private final LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        private final JsonObject evidence = new JsonObject();
        private String metadataJson;
        private String metadataHash;
        private String lifecycleState = "UNRESOLVED";
        private String locationKind = "UNRESOLVED";
        private String locationKey;
        private long changedAtMs;

        ProfileDraft(LegacyPublicData.Profile source) {
            this.source = source;
            this.changedAtMs = source.updatedAtMs();
        }

        LegacyPublicData.Profile source() {
            return source;
        }

        void metadata(@Nullable String json, @Nullable String hash) {
            metadataJson = json;
            metadataHash = hash;
        }

        void conflict(String code) {
            conflicts.add(code);
        }

        void rawEvidence(String key, @Nullable String raw) {
            if (raw == null) {
                evidence.add(key, null);
            } else {
                evidence.addProperty(key, raw);
            }
        }

        boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        List<String> conflicts() {
            return List.copyOf(conflicts);
        }

        JsonObject evidence() {
            return evidence;
        }

        void lifecycle(String state, String location, @Nullable String key, long changedAt) {
            lifecycleState = state;
            locationKind = location;
            locationKey = key;
            changedAtMs = changedAt;
        }

        String lifecycleState() {
            return lifecycleState;
        }

        String locationKind() {
            return locationKind;
        }

        @Nullable
        String locationKey() {
            return locationKey;
        }

        long changedAtMs() {
            return changedAtMs;
        }

        PublicImportPlan.Profile target() {
            return new PublicImportPlan.Profile(
                    source.profileId(),
                    PublicImportDisplayNameNormalizer.normalize(source),
                    source.roleId(),
                    metadataJson,
                    metadataHash,
                    source.lastWorldName(),
                    source.createdAtMs(),
                    source.updatedAtMs(),
                    source.lastActiveAtMs()
            );
        }
    }

    record SnapshotDraft(
            String snapshotId,
            String profileId,
            String kind,
            int version,
            String payloadJson,
            String payloadHash,
            boolean sourceActive,
            long createdAtMs
    ) {
        PublicImportPlan.Snapshot target(boolean current) {
            return new PublicImportPlan.Snapshot(
                    snapshotId, profileId, kind, version, payloadJson,
                    payloadHash, current, createdAtMs
            );
        }
    }

    record CoopDraft(
            LegacyPublicData.CoopSlot slot,
            @Nullable SnapshotDraft snapshot
    ) {
    }

    record CoopAnalysis(
            List<PublicImportPlan.CoopSlot> slots,
            Map<String, List<CoopDraft>> byProfile
    ) {
    }
}
