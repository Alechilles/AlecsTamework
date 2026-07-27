package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.bonded
        .BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Resolves the one role identity owned by a bonded capture operation. */
final class BondedCompanionCaptureRoleResolver {
    private BondedCompanionCaptureRoleResolver() {
    }

    /** Maps wild capture roles before selecting exactly one bonded family. */
    @Nullable
    static Resolution resolve(
            ItemFeatureConfig config,
            List<BondedCompanionRosterRegistry.RosterDefinition> families,
            @Nullable String sourceRoleId
    ) {
        String roleId = authoritativeRole(config, sourceRoleId);
        if (roleId == null || families == null || families.isEmpty()) {
            return null;
        }
        BondedCompanionRosterRegistry.RosterDefinition match = null;
        for (var family : families) {
            if (family == null || !family.allowedRoles().contains(roleId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = family;
        }
        return match == null ? null : new Resolution(roleId, match);
    }

    /** Returns the mapped tamed role only for the explicit wild-taming flow. */
    @Nullable
    static String authoritativeRole(
            ItemFeatureConfig config,
            @Nullable String sourceRoleId
    ) {
        if (config == null || sourceRoleId == null || sourceRoleId.isBlank()) {
            return null;
        }
        String roleId = config.isCaptureTamesTarget()
                ? config.resolveCaptureTamedRole(sourceRoleId)
                : sourceRoleId;
        return roleId == null || roleId.isBlank() ? null : roleId.trim();
    }

    /** Rewrites only role identity when a source snapshot predates role mapping. */
    @Nullable
    static BondedCompanionSnapshot alignSnapshotRole(
            @Nullable BondedCompanionSnapshot snapshot,
            String roleId
    ) {
        if (snapshot == null) {
            return null;
        }
        CoopResidentStateSnapshot state = snapshot.fullState();
        if (Objects.equals(roleId, state.roleId())) {
            return snapshot;
        }
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(), roleId,
                state.commandLinks(), state.owner(), state.tamed(),
                state.npcName(), state.happiness(), state.needs(),
                state.breeding(), state.leveling(), state.traits(),
                state.talents(), state.lifeStage(), state.attachments(),
                state.healthPercent(), state.capturedAtMs()
        ), snapshot.extensionData());
    }

    /** Exact authoritative role and family frozen by admission. */
    record Resolution(
            String roleId,
            BondedCompanionRosterRegistry.RosterDefinition family
    ) {
        Resolution {
            roleId = Objects.requireNonNull(roleId, "roleId");
            family = Objects.requireNonNull(family, "family");
        }
    }
}
