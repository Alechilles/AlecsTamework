package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.deterministicId;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.flag;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.refusal;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.requireOptionalUuid;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.requireProfile;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.sha256;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.validJsonObject;

/** Resolves snapshots, coop detail, lifecycle, and bounded quarantine from public evidence. */
final class PublicImportLifecyclePlanner {
    @Nonnull
    PublicImportPlanningModel.Lifecycle plan(
            @Nonnull LegacyPublicData source,
            @Nonnull PublicImportPlanningModel.Identity identity,
            @Nonnull LegacySourceFingerprint fingerprint,
            long importedAtMs
    ) throws Exception {
        ArrayList<PublicImportPlanningModel.SnapshotDraft> snapshots =
                snapshots(source, identity.profiles(), fingerprint);
        PublicImportPlanningModel.CoopAnalysis coop =
                coops(source, identity.profiles(), snapshots, fingerprint);
        markDuplicateActiveSnapshots(snapshots, identity.profiles());
        Map<String, LegacyPublicData.ProfileState> states =
                profileStates(source, identity.profiles());
        resolveLifecycles(identity.profiles(), snapshots, coop, states);

        ArrayList<PublicImportPlan.Lifecycle> lifecycles = new ArrayList<>();
        ArrayList<PublicImportPlan.Incident> incidents = new ArrayList<>();
        for (PublicImportPlanningModel.ProfileDraft profile : identity.profiles().values()) {
            finalizeProfile(profile, fingerprint, importedAtMs, lifecycles, incidents);
        }
        return new PublicImportPlanningModel.Lifecycle(
                finalizeSnapshots(snapshots, identity.profiles()),
                coop.slots(),
                finalizeResidencies(coop, identity.profiles()),
                List.copyOf(lifecycles),
                List.copyOf(incidents)
        );
    }

    private ArrayList<PublicImportPlanningModel.SnapshotDraft> snapshots(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            LegacySourceFingerprint fingerprint
    ) throws Exception {
        ArrayList<PublicImportPlanningModel.SnapshotDraft> result = new ArrayList<>();
        for (LegacyPublicData.Snapshot snapshot : source.snapshots()) {
            PublicImportPlanningModel.ProfileDraft profile =
                    requireProfile(profiles, snapshot.profileId(), "SNAPSHOT_PROFILE_MISSING");
            if (invalidSnapshotHeader(snapshot)) {
                profile.conflict("INVALID_SNAPSHOT_HEADER");
            } else if (!validJsonObject(snapshot.payloadJson())) {
                profile.conflict("INVALID_SNAPSHOT_JSON");
                profile.rawEvidence("snapshot-" + snapshot.sourceSnapshotId(),
                        snapshot.payloadJson());
            } else {
                result.add(snapshotDraft(snapshot, fingerprint));
            }
        }
        return result;
    }

    private boolean invalidSnapshotHeader(LegacyPublicData.Snapshot snapshot) {
        return snapshot.kind() == null || snapshot.kind().isBlank()
                || snapshot.version() <= 0
                || (snapshot.active() != 0 && snapshot.active() != 1);
    }

    private PublicImportPlanningModel.SnapshotDraft snapshotDraft(
            LegacyPublicData.Snapshot snapshot,
            LegacySourceFingerprint fingerprint
    ) throws Exception {
        return new PublicImportPlanningModel.SnapshotDraft(
                deterministicId(fingerprint.snapshotSha256(),
                        "snapshot:" + snapshot.sourceSnapshotId()),
                snapshot.profileId(),
                snapshot.kind(),
                snapshot.version(),
                snapshot.payloadJson(),
                sha256(snapshot.payloadJson()),
                snapshot.active() == 1,
                snapshot.createdAtMs()
        );
    }

    private PublicImportPlanningModel.CoopAnalysis coops(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            LegacySourceFingerprint fingerprint
    ) throws Exception {
        ArrayList<PublicImportPlan.CoopSlot> slots = new ArrayList<>();
        HashMap<String, List<PublicImportPlanningModel.CoopDraft>> byProfile = new HashMap<>();
        for (LegacyPublicData.CoopSlot slot : source.coopSlots()) {
            slots.add(new PublicImportPlan.CoopSlot(
                    slot.coopKey(), slot.worldName(), slot.coopId(),
                    slot.x(), slot.y(), slot.z(), slot.residentSlot()
            ));
            if (slot.profileId() != null) {
                addOccupiedCoop(slot, profiles, snapshots, fingerprint, byProfile);
            }
        }
        byProfile.forEach((profileId, rows) -> {
            if (rows.size() > 1) {
                profiles.get(profileId).conflict("MULTIPLE_COOP_SLOTS");
            }
        });
        return new PublicImportPlanningModel.CoopAnalysis(
                List.copyOf(slots), Map.copyOf(byProfile)
        );
    }

    private void addOccupiedCoop(
            LegacyPublicData.CoopSlot slot,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            LegacySourceFingerprint fingerprint,
            Map<String, List<PublicImportPlanningModel.CoopDraft>> byProfile
    ) throws Exception {
        PublicImportPlanningModel.ProfileDraft profile =
                requireProfile(profiles, slot.profileId(), "COOP_PROFILE_MISSING");
        requireOptionalUuid(slot.housedNpcUuid(), "INVALID_COOP_NPC_UUID");
        requireOptionalUuid(slot.lastReleasedNpcUuid(), "INVALID_RELEASED_NPC_UUID");
        PublicImportPlanningModel.SnapshotDraft snapshot =
                coopSnapshot(slot, profile, fingerprint);
        if (snapshot != null) {
            snapshots.add(snapshot);
        }
        byProfile.computeIfAbsent(slot.profileId(), ignored -> new ArrayList<>())
                .add(new PublicImportPlanningModel.CoopDraft(slot, snapshot));
    }

    @Nullable
    private PublicImportPlanningModel.SnapshotDraft coopSnapshot(
            LegacyPublicData.CoopSlot slot,
            PublicImportPlanningModel.ProfileDraft profile,
            LegacySourceFingerprint fingerprint
    ) throws Exception {
        if (slot.stateSnapshotJson() == null) {
            return null;
        }
        if (!validJsonObject(slot.stateSnapshotJson())) {
            profile.conflict("INVALID_COOP_SNAPSHOT_JSON");
            profile.rawEvidence("coop-" + slot.coopKey(), slot.stateSnapshotJson());
            return null;
        }
        return new PublicImportPlanningModel.SnapshotDraft(
                deterministicId(fingerprint.snapshotSha256(), "coop:" + slot.coopKey()),
                slot.profileId(),
                "coop",
                1,
                slot.stateSnapshotJson(),
                sha256(slot.stateSnapshotJson()),
                true,
                slot.updatedAtMs()
        );
    }

    private void markDuplicateActiveSnapshots(
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) {
        HashMap<String, Integer> activeCounts = new HashMap<>();
        for (PublicImportPlanningModel.SnapshotDraft snapshot : snapshots) {
            if (snapshot.sourceActive()) {
                activeCounts.merge(snapshot.profileId() + "\0" + snapshot.kind(), 1, Integer::sum);
            }
        }
        activeCounts.forEach((key, count) -> {
            if (count > 1) {
                profiles.get(key.substring(0, key.indexOf('\0')))
                        .conflict("DUPLICATE_ACTIVE_SNAPSHOT");
            }
        });
    }

    private Map<String, LegacyPublicData.ProfileState> profileStates(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) throws Exception {
        HashMap<String, LegacyPublicData.ProfileState> states = new HashMap<>();
        for (LegacyPublicData.ProfileState state : source.profileStates()) {
            PublicImportPlanningModel.ProfileDraft profile =
                    requireProfile(profiles, state.profileId(), "PROFILE_STATE_PROFILE_MISSING");
            if (states.put(state.profileId(), state) != null) {
                throw refusal("DUPLICATE_PROFILE_STATE", state.profileId());
            }
            if (!flag(state.captureActive()) || !flag(state.deathActive())
                    || !flag(state.lostActive()) || !flag(state.inCoop())) {
                profile.conflict("INVALID_LIFECYCLE_FLAG");
            }
        }
        return Map.copyOf(states);
    }

    private void resolveLifecycles(
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            PublicImportPlanningModel.CoopAnalysis coop,
            Map<String, LegacyPublicData.ProfileState> states
    ) {
        for (PublicImportPlanningModel.ProfileDraft profile : profiles.values()) {
            LegacyPublicData.ProfileState state = states.get(profile.source().profileId());
            List<PublicImportPlanningModel.CoopDraft> coopRows =
                    coop.byProfile().getOrDefault(profile.source().profileId(), List.of());
            if (state == null) {
                if (!coopRows.isEmpty()) {
                    profile.conflict("COOP_RESIDENCY_STATE_CONFLICT");
                }
                profile.lifecycle("UNRESOLVED", "UNRESOLVED", null,
                        profile.source().updatedAtMs());
            } else {
                resolveLifecycle(profile, state, snapshots, coopRows);
            }
        }
    }

    private void resolveLifecycle(
            PublicImportPlanningModel.ProfileDraft profile,
            LegacyPublicData.ProfileState state,
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            List<PublicImportPlanningModel.CoopDraft> coopRows
    ) {
        int positive = state.captureActive() + state.deathActive()
                + state.lostActive() + state.inCoop();
        if (positive > 1) {
            profile.conflict("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS");
        }
        if (state.inCoop() == 0 && !coopRows.isEmpty()) {
            profile.conflict("COOP_RESIDENCY_STATE_CONFLICT");
        }
        if (profile.hasConflicts() || positive == 0) {
            profile.lifecycle("UNRESOLVED", "UNRESOLVED", null, state.updatedAtMs());
        } else if (state.captureActive() == 1) {
            resolveSnapshotLifecycle(profile, state, snapshots, "capture",
                    "CAPTURED", "CAPTURE_ITEM");
        } else if (state.deathActive() == 1) {
            resolveSnapshotLifecycle(profile, state, snapshots, "death",
                    "DEAD_REVIVABLE", "NONE");
        } else if (state.lostActive() == 1) {
            resolveSnapshotLifecycle(profile, state, snapshots, "lost", "LOST", "NONE");
        } else {
            resolveCoopLifecycle(profile, state, coopRows);
        }
    }

    private void resolveSnapshotLifecycle(
            PublicImportPlanningModel.ProfileDraft profile,
            LegacyPublicData.ProfileState state,
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            String kind,
            String lifecycle,
            String location
    ) {
        List<PublicImportPlanningModel.SnapshotDraft> active = snapshots.stream()
                .filter(snapshot -> snapshot.profileId().equals(profile.source().profileId())
                        && snapshot.kind().equals(kind) && snapshot.sourceActive())
                .toList();
        if (active.size() != 1) {
            profile.conflict("REQUIRED_" + kind.toUpperCase() + "_SNAPSHOT_MISSING");
            profile.lifecycle("UNRESOLVED", "UNRESOLVED", null, state.updatedAtMs());
        } else {
            String locationKey = "NONE".equals(location) ? null : active.getFirst().snapshotId();
            profile.lifecycle(lifecycle, location, locationKey, state.updatedAtMs());
        }
    }

    private void resolveCoopLifecycle(
            PublicImportPlanningModel.ProfileDraft profile,
            LegacyPublicData.ProfileState state,
            List<PublicImportPlanningModel.CoopDraft> coopRows
    ) {
        boolean complete = coopRows.size() == 1
                && state.coopKey() != null
                && state.coopKey().equals(coopRows.getFirst().slot().coopKey())
                && coopRows.getFirst().snapshot() != null;
        if (!complete) {
            profile.conflict("COOP_EVIDENCE_INCOMPLETE");
            profile.lifecycle("UNRESOLVED", "UNRESOLVED", null, state.updatedAtMs());
        } else {
            profile.lifecycle("COOP", "COOP_SLOT", state.coopKey(), state.updatedAtMs());
        }
    }

    private List<PublicImportPlan.Snapshot> finalizeSnapshots(
            List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) {
        return snapshots.stream()
                .map(snapshot -> snapshot.target(
                        snapshot.sourceActive()
                                && !profiles.get(snapshot.profileId()).hasConflicts()
                ))
                .toList();
    }

    private List<PublicImportPlan.CoopResidency> finalizeResidencies(
            PublicImportPlanningModel.CoopAnalysis coop,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) {
        ArrayList<PublicImportPlan.CoopResidency> result = new ArrayList<>();
        for (Map.Entry<String, List<PublicImportPlanningModel.CoopDraft>> entry
                : coop.byProfile().entrySet()) {
            PublicImportPlanningModel.ProfileDraft profile = profiles.get(entry.getKey());
            if (!profile.hasConflicts() && "COOP".equals(profile.lifecycleState())) {
                PublicImportPlanningModel.CoopDraft row = entry.getValue().getFirst();
                result.add(new PublicImportPlan.CoopResidency(
                        row.slot().coopKey(), row.slot().profileId(),
                        row.slot().housedNpcUuid(), row.snapshot().snapshotId(),
                        row.slot().capturedAtMs(), row.slot().updatedAtMs()
                ));
            }
        }
        return List.copyOf(result);
    }

    private void finalizeProfile(
            PublicImportPlanningModel.ProfileDraft profile,
            LegacySourceFingerprint fingerprint,
            long importedAtMs,
            List<PublicImportPlan.Lifecycle> lifecycles,
            List<PublicImportPlan.Incident> incidents
    ) {
        String incidentId = null;
        if (profile.hasConflicts()) {
            incidentId = deterministicId(
                    fingerprint.snapshotSha256(),
                    "incident:" + profile.source().profileId() + ":" + profile.conflicts()
            );
            JsonArray codes = new JsonArray();
            profile.conflicts().forEach(codes::add);
            profile.evidence().add("conflicts", codes);
            profile.evidence().addProperty("profileId", profile.source().profileId());
            incidents.add(new PublicImportPlan.Incident(
                    incidentId, profile.source().profileId(), profile.conflicts().getFirst(),
                    profile.evidence().toString(), importedAtMs
            ));
        }
        lifecycles.add(lifecycle(profile, incidentId));
    }

    private PublicImportPlan.Lifecycle lifecycle(
            PublicImportPlanningModel.ProfileDraft profile,
            @Nullable String incidentId
    ) {
        boolean unresolved = profile.hasConflicts();
        return new PublicImportPlan.Lifecycle(
                profile.source().profileId(),
                profile.source().ownerUuid(),
                unresolved ? "UNRESOLVED" : profile.lifecycleState(),
                unresolved ? "UNRESOLVED" : profile.locationKind(),
                unresolved ? null : profile.locationKey(),
                profile.changedAtMs(),
                incidentId
        );
    }
}
