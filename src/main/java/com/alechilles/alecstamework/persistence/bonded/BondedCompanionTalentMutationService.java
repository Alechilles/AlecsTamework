package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Applies validated talent changes directly to a bonded profile's canonical
 * snapshot, independent of whether a disposable projection is active.
 */
final class BondedCompanionTalentMutationService {
    private static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private final BondedCompanionStore store;
    private final LongSupplier clock;
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    BondedCompanionTalentMutationService(
            @Nonnull BondedCompanionStore store,
            @Nonnull LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    BondedCompanionStoreResult<BondedCompanionRecord.Profile> apply(
            @Nonnull BondedCompanionTalentActionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        BondedCompanionRecord.Profile profile = store.findProfile(
                request.ownerUuid(), request.rosterId(), request.profileId()
        ).orElse(null);
        if (profile == null) {
            return rejected(BondedCompanionStoreResult.Code.NOT_FOUND,
                    "bonded-profile-not-found");
        }
        if (profile.revision() != request.expectedRevision()) {
            return rejected(BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                    "bonded-profile-revision-conflict");
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return rejected(BondedCompanionStoreResult.Code.VALIDATION_FAILED,
                    "bonded-talents-disabled");
        }
        BondedCompanionSnapshot snapshot = decode(profile);
        if (snapshot == null || snapshot.fullState().leveling() == null) {
            return rejected(BondedCompanionStoreResult.Code.VALIDATION_FAILED,
                    "bonded-level-data-unavailable");
        }
        TameworkTalentsComponent updated = request.action()
                == BondedCompanionTalentActionRequest.Action.PURCHASE
                ? purchase(snapshot, profile.roleId(), request.talentId())
                : reset(snapshot, profile.roleId());
        if (updated == null) {
            return rejected(BondedCompanionStoreResult.Code.VALIDATION_FAILED,
                    request.action() == BondedCompanionTalentActionRequest.Action.PURCHASE
                            ? "bonded-talent-purchase-rejected"
                            : "bonded-talent-reset-rejected");
        }
        long now = clock.getAsLong();
        BondedCompanionSnapshot changed = snapshot.withTalents(updated);
        BondedCompanionPayload payload = BondedCompanionPayload.of(
                snapshots.encode(changed).getBytes(StandardCharsets.UTF_8));
        return store.updateSnapshot(operation(request, now),
                request.expectedRevision(), payload, now);
    }

    private TameworkTalentsComponent purchase(
            BondedCompanionSnapshot snapshot,
            String roleId,
            String talentId
    ) {
        TameworkTalentsComponent existing = snapshot.fullState().talents();
        TwTalentConfig config = resolveConfig(existing, roleId);
        TameworkLevelingComponent leveling = snapshot.fullState().leveling();
        if (config == null || !config.isEnabled() || leveling == null) {
            return null;
        }
        TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
        if (talent == null || leveling.getLevel() < talent.getMinLevel()) {
            return null;
        }
        TameworkTalentsComponent updated = existing == null
                ? new TameworkTalentsComponent(
                        config.getId(),
                        0,
                        new String[0],
                        config.getAllocationRevision()
                )
                : existing.clone();
        updated.setConfigId(config.getId());
        updated.setAllocationRevision(config.getAllocationRevision());
        if (updated.hasPurchasedTalent(talent.getId())
                || !hasPrerequisites(updated, talent)
                || availablePoints(leveling, updated) < talent.getPointCost()) {
            return null;
        }
        LinkedHashSet<String> purchased = new LinkedHashSet<>();
        for (String id : updated.getPurchasedTalentIds()) {
            purchased.add(id);
        }
        purchased.add(talent.getId());
        updated.setPurchasedTalentIds(purchased.toArray(new String[0]));
        updated.setSpentPoints(updated.getSpentPoints() + talent.getPointCost());
        return updated;
    }

    private TameworkTalentsComponent reset(
            BondedCompanionSnapshot snapshot,
            String roleId
    ) {
        TameworkTalentsComponent existing = snapshot.fullState().talents();
        if (existing == null || (existing.getSpentPoints() == 0
                && existing.getPurchasedTalentIds().length == 0)) {
            return null;
        }
        TameworkTalentsComponent updated = existing.clone();
        TwTalentConfig config = resolveConfig(existing, roleId);
        if (config != null) {
            updated.setConfigId(config.getId());
            updated.setAllocationRevision(config.getAllocationRevision());
        }
        updated.setSpentPoints(0);
        updated.setPurchasedTalentIds(new String[0]);
        return updated;
    }

    private int availablePoints(
            TameworkLevelingComponent leveling,
            TameworkTalentsComponent talents
    ) {
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(
                leveling.getLevel(), leveling.getConfigId());
        return Math.max(0, earned - talents.getSpentPoints());
    }

    private TwTalentConfig resolveConfig(
            TameworkTalentsComponent talents,
            String roleId
    ) {
        if (talents != null && talents.getConfigId() != null
                && !talents.getConfigId().isBlank()) {
            TwTalentConfig configured = TwTalentConfig.resolveById(
                    talents.getConfigId());
            if (configured != null) {
                return configured;
            }
        }
        return roleId == null || roleId.isBlank()
                ? null : TwTalentConfig.resolveForRole(roleId);
    }

    private boolean hasPrerequisites(
            TameworkTalentsComponent talents,
            TwTalentConfig.TalentDefinition talent
    ) {
        for (String required : talent.getRequiresTalentIds()) {
            if (required != null && !required.isBlank()
                    && !talents.hasPurchasedTalent(required)) {
                return false;
            }
        }
        return true;
    }

    private BondedCompanionSnapshot decode(
            BondedCompanionRecord.Profile profile
    ) {
        var result = snapshots.decode(new String(profile.snapshot().bytes(),
                StandardCharsets.UTF_8));
        return result.status() == BondedCompanionSnapshotCodec.Status.FOUND
                ? result.snapshot() : null;
    }

    private BondedCompanionOperation operation(
            BondedCompanionTalentActionRequest request,
            long now
    ) {
        return new BondedCompanionOperation(
                request.callerNamespace(), request.idempotencyKey(),
                sha256(request.ownerUuid() + "|" + request.rosterId() + "|"
                        + request.profileId() + "|" + request.expectedRevision()
                        + "|" + request.action() + "|"
                        + Objects.toString(request.talentId(), "")),
                request.ownerUuid(), request.rosterId(), request.profileId(),
                // Profile snapshot edits are store-like mutations. Retaining the
                // established operation vocabulary avoids a schema migration.
                BondedCompanionOperation.Type.STORE, now,
                safeAdd(now, RETENTION_MS));
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
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private BondedCompanionStoreResult<BondedCompanionRecord.Profile> rejected(
            BondedCompanionStoreResult.Code code,
            String reason
    ) {
        return new BondedCompanionStoreResult<>(code, null, reason, false);
    }
}
