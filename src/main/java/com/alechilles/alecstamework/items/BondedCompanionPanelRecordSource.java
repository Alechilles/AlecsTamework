package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reads bonded panel records only from stable profile views. */
final class BondedCompanionPanelRecordSource {
    private static final String UUID_NAMESPACE = "tamework-bonded-profile\0";

    @Nonnull
    PanelSnapshot snapshotFor(
            @Nullable UUID ownerUuid,
            @Nullable String rosterId,
            @Nullable BondedCompanionPanelSnapshotCache.Snapshot cached) {
        String roster = normalize(rosterId);
        if (ownerUuid == null || roster == null) return PanelSnapshot.empty();
        if (cached == null) return PanelSnapshot.empty();
        return snapshot(ownerUuid, roster, cached.profiles(),
                cached.generation(), cached.state(), cached.trusted());
    }

    PanelSnapshot ready(UUID owner, String roster,
                        List<BondedCompanionProfileView> profiles) {
        return snapshot(owner, roster, profiles, 1L,
                BondedCompanionPanelSnapshotCache.State.READY, true);
    }

    private PanelSnapshot snapshot(
            UUID owner,
            String roster,
            List<BondedCompanionProfileView> profiles,
            long generation,
            BondedCompanionPanelSnapshotCache.State state,
            boolean trusted) {
        LinkedHashMap<String, BondedCompanionProfileView> newest =
                new LinkedHashMap<>();
        for (BondedCompanionProfileView profile : profiles) {
            if (profile == null || !owner.equals(profile.ownerUuid())
                    || !roster.equals(profile.rosterId())) continue;
            newest.merge(profile.profileId(), profile,
                    (left, right) -> right.revision() >= left.revision()
                            ? right : left);
        }
        ArrayList<PanelRecord> records = new ArrayList<>(newest.size());
        newest.values().stream()
                .sorted(Comparator.comparing(BondedCompanionProfileView::profileId))
                .forEach(profile -> records.add(new PanelRecord(
                        presentationUuid(profile.profileId()), profile)));
        return new PanelSnapshot(List.copyOf(records), generation, state,
                trusted);
    }

    @Nonnull
    static UUID presentationUuid(@Nonnull String profileId) {
        String normalized = Objects.requireNonNull(profileId, "profileId").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("profileId is required");
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE + normalized).getBytes(StandardCharsets.UTF_8));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record PanelRecord(@Nonnull UUID presentationUuid,
                       @Nonnull BondedCompanionProfileView profile) {
        PanelRecord {
            Objects.requireNonNull(presentationUuid, "presentationUuid");
            Objects.requireNonNull(profile, "profile");
        }
    }

    record PanelSnapshot(
            @Nonnull List<PanelRecord> records,
            long generation,
            @Nonnull BondedCompanionPanelSnapshotCache.State state,
            boolean trusted) {
        PanelSnapshot {
            records = List.copyOf(records);
            state = Objects.requireNonNull(state, "state");
        }

        static PanelSnapshot empty() {
            return new PanelSnapshot(List.of(), 0L,
                    BondedCompanionPanelSnapshotCache.State.CLOSED, false);
        }
    }
}
