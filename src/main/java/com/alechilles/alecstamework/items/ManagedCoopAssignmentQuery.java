package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Read-only schema-v5 assignment projection for command-panel compatibility queries.
 *
 * <p>Historical UUIDs resolve through canonical profiles. Only trusted HOUSED and RELEASING rows
 * are presented as cooped; any other managed assignment suppresses stale v4 fallback evidence.</p>
 */
final class ManagedCoopAssignmentQuery {
    record Lookup(boolean managedAssignment,
                  @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        static Lookup unmanaged() {
            return new Lookup(false, null);
        }
    }

    record SlotLookup(boolean managedAuthority,
                      @Nullable ResidentRecord resident,
                      @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        static SlotLookup unmanaged() {
            return new SlotLookup(false, null, null);
        }
    }

    @Nullable private final ManagedCoopResidentIndex index;
    @Nullable private final BooleanSupplier trustGate;
    @Nullable private final NpcProfileRepository profiles;
    private final CoopResidentStateSnapshotCodec snapshotCodec = new CoopResidentStateSnapshotCodec();

    ManagedCoopAssignmentQuery(@Nullable ManagedCoopResidentIndex index,
                               @Nullable BooleanSupplier trustGate,
                               @Nullable NpcProfileRepository profiles) {
        this.index = index;
        this.trustGate = trustGate;
        this.profiles = profiles;
    }

    boolean configured() {
        return index != null;
    }

    boolean trusted() {
        return index != null && index.isTrusted() && trustGate != null && trustGate.getAsBoolean()
                && index.snapshot().revision() != 0L;
    }

    @Nonnull
    Lookup byUuid(@Nullable UUID npcUuid) {
        if (npcUuid == null || !trusted()) {
            return Lookup.unmanaged();
        }
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        ResidentRecord direct = snapshot.residentByUuid(npcUuid);
        NpcProfileRepository.ProfileRecord canonicalProfile = profiles == null
                ? null
                : profiles.loadProfileByNpcUuid(npcUuid);
        ResidentRecord canonical = canonicalProfile == null
                ? null
                : snapshot.residentByProfile(canonicalProfile.profileId());
        if (direct != null && canonicalProfile != null
                && !direct.profileId().equals(canonicalProfile.profileId())) {
            return new Lookup(true, null);
        }
        ResidentRecord resident = canonical != null ? canonical : direct;
        if (resident == null) {
            return Lookup.unmanaged();
        }
        if (!isManaged(resident, snapshot) || !isVisible(resident)) {
            return new Lookup(true, null);
        }
        return new Lookup(true, toSnapshot(resident));
    }

    @Nonnull
    SlotLookup bySlot(@Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        ManagedCoopAuthorityKey key = authorityKey(context);
        if (key == null || !trusted()) {
            return SlotLookup.unmanaged();
        }
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        AuthorityRecord authority = snapshot.authority(key, context.coopId());
        if (authority == null) {
            return SlotLookup.unmanaged();
        }
        ResidentRecord resident = snapshot.residentAt(key, context.residentSlot());
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot visible =
                resident != null && isManaged(resident, snapshot) && isVisible(resident)
                        ? toSnapshot(resident)
                        : null;
        return new SlotLookup(true, resident, visible);
    }

    boolean permitsLegacyMutation(@Nullable UUID npcUuid,
                                  @Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        if (!configured()) {
            return true;
        }
        if (!trusted()) {
            return false;
        }
        if (context != null && !LegacyCoopLedgerSupport.hasKnownCoordinates(context)) {
            return false;
        }
        return !byUuid(npcUuid).managedAssignment() && !bySlot(context).managedAuthority();
    }

    @Nonnull
    List<CommandLinkedNpcCoopService.CoopSlotContext> housedSlots(@Nullable String worldName) {
        String world = LegacyCoopLedgerSupport.normalize(worldName);
        if (world == null || !trusted()) {
            return List.of();
        }
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        ArrayList<CommandLinkedNpcCoopService.CoopSlotContext> slots = new ArrayList<>();
        for (ResidentRecord resident : snapshot.allResidents()) {
            if (!world.equals(resident.authorityKey().worldName())
                    || !isManaged(resident, snapshot)
                    || !isVisible(resident)) {
                continue;
            }
            slots.add(CommandLinkedNpcCoopService.CoopSlotContext.of(
                    resident.authorityKey().worldName(), resident.coopId(),
                    resident.authorityKey().x(), resident.authorityKey().y(),
                    resident.authorityKey().z(), resident.residentSlot()));
        }
        return List.copyOf(slots);
    }

    @Nullable
    CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot(
            @Nullable ResidentRecord resident) {
        if (resident == null || resident.snapshotJson() == null || resident.snapshotJson().isBlank()) {
            return null;
        }
        CoopResidentStateSnapshotCodec.DecodeResult result = snapshotCodec.decode(resident.snapshotJson());
        return result.status() == CoopResidentStateSnapshotCodec.Status.FAILED
                ? null
                : result.snapshotOrNull();
    }

    @Nonnull
    private CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot toSnapshot(
            @Nonnull ResidentRecord resident) {
        NpcProfileRepository.ProfileRecord profile = profiles == null
                ? null
                : profiles.loadProfileById(resident.profileId());
        UUID ownerId = profile == null ? null : profile.ownerUuid();
        String[] toolIds = profile == null ? new String[0] : profile.toolIds();
        String roleId = profile != null && profile.roleId() != null
                ? profile.roleId()
                : resident.roleId();
        String displayName = profile == null ? null : profile.displayName();
        return new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                resident.residentUuid(), ownerId,
                LegacyCoopLedgerSupport.sanitizeToolIds(toolIds), roleId, displayName,
                resident.coopId(), resident.residentSlot(), resident.capturedAtMs());
    }

    private boolean isManaged(@Nonnull ResidentRecord resident,
                              @Nonnull ManagedCoopResidentIndex.Snapshot snapshot) {
        AuthorityRecord authority = snapshot.authority(resident.authorityKey(), resident.coopId());
        return authority != null && authority.state() == AuthorityState.TWORK_MANAGED;
    }

    private boolean isVisible(@Nonnull ResidentRecord resident) {
        return resident.state() == ResidentState.HOUSED || resident.state() == ResidentState.RELEASING;
    }

    @Nullable
    private ManagedCoopAuthorityKey authorityKey(
            @Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        if (context == null || context.worldName() == null || context.worldName().isBlank()
                || context.coopId() == null || context.coopId().isBlank()
                || context.residentSlot() < 0
                || !LegacyCoopLedgerSupport.hasKnownCoordinates(context)) {
            return null;
        }
        return new ManagedCoopAuthorityKey(context.worldName(), context.x(), context.y(), context.z());
    }
}
