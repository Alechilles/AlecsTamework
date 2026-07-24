package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Reconciles persisted restoration evidence without performing codec or operation work.
 *
 * <p>The resolver owns alias, owner, role, metadata, and tool-link consistency across legacy and
 * complete snapshots. In particular, legacy public v2-v4 rows retain their persisted current NPC
 * alias; profile identity is used only when no current alias exists.</p>
 */
final class TameworkRestorationEvidenceResolver {

    NpcAlias modernSourceAlias(
            CompanionProfileReadModel profile,
            CoopResidentStateSnapshot state
    ) {
        NpcAlias sourceAlias = new NpcAlias(state.npcUuid());
        CompanionAlias current = profile.currentAlias();
        if (current != null && !current.alias().equals(sourceAlias)) {
            throw new EvidenceConflict("sourceAlias");
        }
        return sourceAlias;
    }

    NpcAlias legacySourceAlias(CompanionProfileReadModel profile) {
        CompanionAlias current = profile.currentAlias();
        if (current != null) {
            return current.alias();
        }
        return new NpcAlias(profile.identity().profileId().value());
    }

    void validateComplete(
            CompanionProfileReadModel profile,
            NpcAlias sourceAlias,
            CoopResidentStateSnapshot state
    ) {
        if (!sourceAlias.value().equals(state.npcUuid())) {
            throw new EvidenceConflict("sourceAlias");
        }
        String role = requireRole(state.roleId());
        String profileRole = normalize(profile.identity().roleId());
        if (profileRole != null && !profileRole.equalsIgnoreCase(role)) {
            throw new EvidenceConflict("roleId");
        }
        UUID canonicalOwner = owner(profile);
        TameworkOwnerComponent owner = state.owner();
        TameworkCommandLinksComponent links = state.commandLinks();
        validateOwner(canonicalOwner, owner == null ? null : owner.getOwnerId());
        validateOwner(canonicalOwner, links == null ? null : links.getOwnerId());
    }

    CoopResidentStateSnapshot resolveLegacyDeath(
            CompanionProfileReadModel profile,
            NpcAlias sourceAlias,
            LegacyDeathV1Payload legacy,
            long createdAtMs
    ) {
        LegacyRestorationEvidence.Metadata metadata = metadata(profile);
        LegacyDeathEvidence resolved = reconcileLegacyDeath(
                profile,
                legacy,
                metadata
        );
        return LegacyRestorationFullStateMapper.death(
                sourceAlias.value(),
                resolved.roleId(),
                resolved.ownerId(),
                resolved.ownerName(),
                resolved.customName(),
                toolIds(profile),
                legacy,
                createdAtMs
        );
    }

    private LegacyDeathEvidence reconcileLegacyDeath(
            CompanionProfileReadModel profile,
            LegacyDeathV1Payload legacy,
            LegacyRestorationEvidence.Metadata metadata
    ) {
        String roleId = reconcileRole(
                profile.identity().roleId(),
                legacy.roleId()
        );
        UUID ownerId = reconcileOwner(profile, legacy.ownerId());
        String ownerName = reconcile(
                "ownerName",
                metadata.ownerName(),
                legacy.ownerName()
        );
        if (ownerId == null && ownerName != null) {
            throw new EvidenceConflict("ownerName");
        }
        String customName = reconcile(
                "customName",
                metadata.customName(),
                legacy.customName()
        );
        if (metadata.tamed() != null
                && metadata.tamed() != legacy.tamed()) {
            throw new EvidenceConflict("tamed");
        }
        return new LegacyDeathEvidence(
                roleId,
                ownerId,
                ownerName,
                customName
        );
    }

    CoopResidentStateSnapshot resolveLegacyLost(
            CompanionProfileReadModel profile,
            NpcAlias sourceAlias,
            LegacyLostV1Payload legacy,
            long createdAtMs
    ) {
        if (legacy.replacementNpcUuid() != null
                || legacy.recoveredAtMs() != 0L) {
            throw new EvidenceConflict("legacyRecoveryEvidence");
        }
        LegacyRestorationEvidence.Metadata metadata = metadata(profile);
        String roleId = requireRole(profile.identity().roleId());
        UUID ownerId = owner(profile);
        if (ownerId == null && metadata.ownerName() != null) {
            throw new EvidenceConflict("ownerName");
        }
        return LegacyRestorationFullStateMapper.lost(
                sourceAlias.value(),
                roleId,
                ownerId,
                metadata.ownerName(),
                metadata.customName(),
                metadata.tamed(),
                toolIds(profile),
                legacy,
                createdAtMs
        );
    }

    private void validateOwner(
            @Nullable UUID canonical,
            @Nullable UUID snapshot
    ) {
        if (snapshot != null && !Objects.equals(canonical, snapshot)) {
            throw new EvidenceConflict("ownerId");
        }
    }

    private UUID reconcileOwner(
            CompanionProfileReadModel profile,
            @Nullable UUID payloadOwner
    ) {
        UUID canonical = owner(profile);
        if (payloadOwner != null && !payloadOwner.equals(canonical)) {
            throw new EvidenceConflict("ownerId");
        }
        return canonical;
    }

    @Nullable
    private UUID owner(CompanionProfileReadModel profile) {
        OwnerId owner = profile.lifecycle().ownerId();
        return owner == null ? null : owner.value();
    }

    private String reconcileRole(
            @Nullable String profileRole,
            @Nullable String payloadRole
    ) {
        String canonical = normalize(profileRole);
        String payload = normalize(payloadRole);
        if (canonical != null && payload != null
                && !canonical.equalsIgnoreCase(payload)) {
            throw new EvidenceConflict("roleId");
        }
        return requireRole(canonical != null ? canonical : payload);
    }

    private String requireRole(@Nullable String role) {
        String normalized = normalize(role);
        if (normalized == null) {
            throw new MissingRole();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String reconcile(
            String field,
            @Nullable String first,
            @Nullable String second
    ) {
        String left = normalize(first);
        String right = normalize(second);
        if (left != null && right != null && !left.equals(right)) {
            throw new EvidenceConflict(field);
        }
        return left != null ? left : right;
    }

    private LegacyRestorationEvidence.Metadata metadata(
            CompanionProfileReadModel profile
    ) {
        return LegacyRestorationEvidence.metadata(
                profile.identity().metadataJson()
        );
    }

    private String[] toolIds(CompanionProfileReadModel profile) {
        return profile.toolLinks().stream()
                .map(CompanionToolLink::toolId)
                .distinct()
                .sorted()
                .map(UUID::toString)
                .toArray(String[]::new);
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record LegacyDeathEvidence(
            String roleId,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nullable String customName
    ) {
    }

    static final class EvidenceConflict extends IllegalArgumentException {
        private final String field;

        EvidenceConflict(String field) {
            super(field);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    static final class MissingRole extends IllegalArgumentException {
    }
}
