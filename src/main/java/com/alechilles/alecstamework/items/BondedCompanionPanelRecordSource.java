package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reads bonded panel records only from stable profile views. */
final class BondedCompanionPanelRecordSource {
    private static final String UUID_NAMESPACE = "tamework-bonded-profile\0";
    private final Supplier<BondedCompanionApi> api;

    BondedCompanionPanelRecordSource(@Nonnull Supplier<BondedCompanionApi> api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Nonnull
    PanelSnapshot snapshotFor(@Nullable UUID ownerUuid, @Nullable String rosterId) {
        String roster = normalize(rosterId);
        if (ownerUuid == null || roster == null) return PanelSnapshot.empty();
        BondedCompanionApi current = currentApi();
        if (!current.availability().available()) return PanelSnapshot.empty();
        try {
            BondedCompanionResult<List<BondedCompanionProfileView>> result =
                    current.list(ownerUuid, roster).join();
            if (result == null || !result.successful() || result.value() == null) {
                return PanelSnapshot.empty();
            }
            return snapshot(ownerUuid, roster, result.value());
        } catch (RuntimeException | LinkageError ignored) {
            return PanelSnapshot.empty();
        }
    }

    private PanelSnapshot snapshot(UUID owner, String roster,
                                   List<BondedCompanionProfileView> profiles) {
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
        return new PanelSnapshot(List.copyOf(records));
    }

    @Nonnull
    static UUID presentationUuid(@Nonnull String profileId) {
        String normalized = Objects.requireNonNull(profileId, "profileId").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("profileId is required");
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE + normalized).getBytes(StandardCharsets.UTF_8));
    }

    private BondedCompanionApi currentApi() {
        try {
            BondedCompanionApi current = api.get();
            return current == null ? BondedCompanionApi.unavailable() : current;
        } catch (RuntimeException | LinkageError ignored) {
            return BondedCompanionApi.unavailable();
        }
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

    record PanelSnapshot(@Nonnull List<PanelRecord> records) {
        PanelSnapshot { records = List.copyOf(records); }
        static PanelSnapshot empty() { return new PanelSnapshot(List.of()); }
    }
}
