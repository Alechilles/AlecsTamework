package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotPresentationMapper;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps durable records to the immutable public bonded profile view. */
final class BondedCompanionViewFactory {
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease
    ) {
        return view(profile, lease,
                profile.state() == BondedCompanionState.STORED,
                profile.state() == BondedCompanionState.ACTIVE,
                profile.state() == BondedCompanionState.DEAD, Map.of());
    }

    BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease,
            boolean summonAvailable,
            boolean storeAvailable,
            boolean reviveAvailable,
            Map<String, String> extensionData
    ) {
        LinkedHashMap<String, String> presentation = new LinkedHashMap<>();
        profile.policy().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("presentation:"))
                .forEach(entry -> presentation.put(
                        entry.getKey().substring("presentation:".length()),
                        entry.getValue()));
        SnapshotFields durable = durable(profile);
        if (durable != null) presentation.putAll(durable.data());
        extensionData.forEach((namespace, json) ->
                presentation.put("extension:" + namespace, json));
        BondedCompanionLeaseView active = lease == null ? null
                : new BondedCompanionLeaseView(
                        lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                        lease.startedAtMs(), lease.expiresAtMs()
                );
        return new BondedCompanionProfileView(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(),
                first(profile.displayName(), durable == null ? null : durable.displayName()),
                first(profile.species(), durable == null ? null : durable.species()),
                first(profile.gender(), durable == null ? null : durable.gender()),
                profile.revision(),
                BondedCompanionStateView.valueOf(profile.state().name()),
                summonAvailable, storeAvailable,
                reviveAvailable,
                presentation, active, profile.reviveCooldownUntilMs(), null
        );
    }

    private SnapshotFields durable(BondedCompanionRecord.Profile profile) {
        String raw = new String(profile.snapshot().bytes(), StandardCharsets.UTF_8);
        BondedCompanionSnapshotCodec.DecodeResult decoded = snapshots.decode(raw);
        BondedCompanionSnapshot snapshot = decoded.snapshot();
        if (decoded.status() != BondedCompanionSnapshotCodec.Status.FOUND
                || snapshot == null) return null;
        String rolePresentation = humanizeRole(profile.roleId());
        BondedCompanionSnapshotPresentationMapper mapper =
                new BondedCompanionSnapshotPresentationMapper(ignored ->
                        new BondedCompanionSnapshotPresentationMapper.RolePresentation(
                                null, null, null,
                                Map.of("rolePresentation", rolePresentation)));
        var mapped = mapper.map(snapshot);
        LinkedHashMap<String, String> data = new LinkedHashMap<>(mapped.data());
        snapshot.extensionData().forEach((namespace, json) ->
                data.put("extension:" + namespace, json));
        return new SnapshotFields(mapped.displayName(), mapped.species(),
                mapped.gender(), Map.copyOf(data));
    }

    private static String humanizeRole(String roleId) {
        String value = roleId == null ? "Companion" : roleId.trim();
        for (String prefix : new String[] {"Tamed_", "Bonded_"}) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                value = value.substring(prefix.length());
            }
        }
        value = value.replace('_', ' ').replace('-', ' ').trim();
        if (value.isEmpty()) return "Companion";
        String[] words = value.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }
        return result.toString();
    }

    private static String first(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim() : fallback;
    }

    private record SnapshotFields(String displayName, String species,
                                  String gender, Map<String, String> data) {}
}
