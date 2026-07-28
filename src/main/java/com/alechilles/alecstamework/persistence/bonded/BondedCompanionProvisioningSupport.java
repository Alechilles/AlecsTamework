package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;

/** Builds deterministic provision identities, snapshots, and stored rows. */
final class BondedCompanionProvisioningSupport {
    private static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();
    private final BondedCompanionRoleHealthResolver roleHealths;

    BondedCompanionProvisioningSupport() {
        this(new HytaleBondedCompanionRoleHealthResolver());
    }

    BondedCompanionProvisioningSupport(
            BondedCompanionRoleHealthResolver roleHealths
    ) {
        this.roleHealths = java.util.Objects.requireNonNull(
                roleHealths, "roleHealths");
    }

    Prepared prepare(BondedCompanionProvisionRequest request, long now) {
        String profileId = stableProfileId(request);
        return new Prepared(profileId, operation(request, profileId, now),
                provisionedSnapshot(request, profileId, now));
    }

    BondedCompanionRecord.Profile storedProfile(
            BondedCompanionProvisionRequest request,
            BondedCompanionProfile domain,
            BondedCompanionPolicy policy,
            long now
    ) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("policyRevision", Long.toString(policy.revision()));
        request.snapshotPresentationData().forEach((key, value) ->
                metadata.put("presentation:" + key, value));
        return new BondedCompanionRecord.Profile(
                domain.profileId(), domain.ownerUuid(), domain.rosterId(),
                domain.familyId(), domain.roleId(), domain.state(),
                domain.revision(), payload(domain.snapshot()), now, now,
                metadata, request.displayName(), request.species(),
                request.gender(), null, 0L, 0L, null, null);
    }

    private BondedCompanionSnapshot provisionedSnapshot(
            BondedCompanionProvisionRequest request,
            String profileId,
            long now
    ) {
        UUID source = UUID.nameUUIDFromBytes(("bonded:" + profileId)
                .getBytes(StandardCharsets.UTF_8));
        TameworkNpcNameComponent name = request.displayName() == null ? null
                : new TameworkNpcNameComponent(
                        request.displayName(), request.ownerUuid(), now,
                        TameworkNpcNameComponent.NameSource.System);
        Double maximumHealth = configuredMaximumHealth(request.roleId());
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                source, null, -1, request.roleId(), null,
                new TameworkOwnerComponent(request.ownerUuid(), null),
                new TameworkTamedComponent(true), name, null, null, null,
                null, null, null, null, null, maximumHealth, maximumHealth,
                maximumHealth == null ? null : 100.0D, now), Map.of());
    }

    @Nullable
    private Double configuredMaximumHealth(String roleId) {
        Double maximum = roleHealths.resolveMaximumHealth(roleId);
        return maximum != null && Double.isFinite(maximum) && maximum > 0.0D
                ? maximum : null;
    }

    private BondedCompanionPayload payload(BondedCompanionSnapshot snapshot) {
        return BondedCompanionPayload.of(snapshots.encode(snapshot)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String stableProfileId(BondedCompanionProvisionRequest request) {
        String identity = request.callerNamespace() + "\0"
                + request.ownerUuid() + "\0" + request.rosterId() + "\0"
                + request.idempotencyKey();
        return UUID.nameUUIDFromBytes(
                identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private BondedCompanionOperation operation(
            BondedCompanionProvisionRequest request,
            String profileId,
            long now
    ) {
        return new BondedCompanionOperation(
                request.callerNamespace(), request.idempotencyKey(),
                sha256(provisionPayload(request)), request.ownerUuid(),
                request.rosterId(), profileId,
                BondedCompanionOperation.Type.PROVISION, now,
                safeAdd(now, RETENTION_MS));
    }

    private String provisionPayload(BondedCompanionProvisionRequest request) {
        StringBuilder payload = new StringBuilder();
        append(payload, request.ownerUuid().toString());
        append(payload, request.rosterId());
        if (request.familyId() != null) {
            append(payload, "family:" + request.familyId());
        }
        append(payload, request.roleId());
        append(payload, request.displayName());
        append(payload, request.species());
        append(payload, request.gender());
        new TreeMap<>(request.snapshotPresentationData()).forEach((key, value) -> {
            append(payload, key);
            append(payload, value);
        });
        return payload.toString();
    }

    private void append(StringBuilder target, @Nullable String value) {
        if (value == null) target.append("-1:");
        else target.append(value.length()).append(':').append(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long safeAdd(long value, long increment) {
        try { return Math.addExact(value, increment); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    record Prepared(
            String profileId,
            BondedCompanionOperation operation,
            BondedCompanionSnapshot snapshot
    ) {}
}
