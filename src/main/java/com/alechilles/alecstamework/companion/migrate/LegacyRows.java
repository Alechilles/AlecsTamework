package com.alechilles.alecstamework.companion.migrate;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Everything the importer reads from a 3.x/4.x world, as plain immutable rows (spec 12).
 * Either part is {@code null} when that file does not exist; the importer imports what exists.
 *
 * <p>Column values are passed through unchanged: JSON columns stay raw strings, world-time
 * values keep their sign, and a column the old schema allows to be NULL is marked
 * {@link Nullable}. Lists are ordered by primary key, so two reads of one file are equal.</p>
 */
public record LegacyRows(@Nullable State state, @Nullable Bonded bonded) {

    /** Which released shape of {@code tamework-state.sqlite} a file has. All share the domain tables. */
    public enum StateSchema { V1, ROUTED_V2, V2 }

    /** The original file a part was read from, for the import report. */
    public record SourceFile(@Nonnull Path path, long sizeBytes, long lastModifiedMs) {
    }

    /** The rows of {@code tamework-state.sqlite}. */
    public record State(
            @Nonnull SourceFile source,
            @Nonnull StateSchema schema,
            @Nonnull List<Profile> profiles,
            @Nonnull List<Lifecycle> lifecycles,
            @Nonnull List<Alias> aliases,
            /**
             * Every snapshot row, current or not. At most one per profile and kind is current. Older
             * rows are kept because they can be the only saved state of a companion: a coop residency
             * may name one, and a profile whose body is live, or was quarantined, has no current row.
             */
            @Nonnull List<Snapshot> snapshots,
            /**
             * At most one entity checkpoint per profile, and only for profiles whose lifecycle state
             * is ACTIVE or UNLOADED: the row keyed by the profile's CURRENT alias, else the row
             * updated last. Tombstones are skipped.
             */
            @Nonnull List<EntityCheckpoint> entityCheckpoints,
            @Nonnull List<ToolLink> toolLinks,
            @Nonnull List<RosterMembership> rosterMemberships,
            @Nonnull List<TimedLease> timedLeases,
            @Nonnull List<CoopSlot> coopSlots,
            @Nonnull List<CoopResidency> coopResidencies,
            @Nonnull List<Provisioning> provisioningRecords,
            /** Tombstones and the entity checkpoint namespace are excluded. */
            @Nonnull List<ExtensionData> extensionData,
            /** Operations whose phase is not PUBLISHED, COMPENSATED or FAILED. Reported, never replayed. */
            int unfinishedOperations) {
    }

    /** {@code companion_profile}. Owner name and custom name are inside {@code metadataJson}. */
    public record Profile(
            @Nonnull String profileId,
            @Nullable String displayName,
            @Nullable String roleId,
            @Nullable String metadataJson,
            @Nullable String lastKnownWorldKey,
            long createdAtMs,
            long updatedAtMs,
            long lastActiveAtMs,
            long metadataRevision) {
    }

    /** {@code companion_lifecycle}. A row that names a quarantine incident is a quarantined profile. */
    public record Lifecycle(
            @Nonnull String profileId,
            @Nullable String ownerUuid,
            @Nonnull String lifecycleState,
            @Nonnull String locationKind,
            @Nullable String locationKey,
            @Nullable String worldKey,
            @Nullable String ownerWorldKey,
            long revision,
            @Nullable String activeOperationId,
            long stateChangedAtMs,
            @Nullable String quarantineIncidentId) {
    }

    /** {@code companion_alias}, every state (LEASED, CURRENT, RETIRED). */
    public record Alias(
            @Nonnull String npcUuid,
            @Nonnull String profileId,
            long aliasGeneration,
            @Nonnull String aliasState,
            long mappedAtMs,
            @Nullable Long retiredAtMs) {
    }

    /** A {@code companion_snapshot} row. */
    public record Snapshot(
            @Nonnull String snapshotId,
            @Nonnull String profileId,
            @Nonnull String snapshotKind,
            int payloadVersion,
            @Nonnull String payloadJson,
            long sourceLifecycleRevision,
            long createdAtMs,
            boolean current) {
    }

    /** One entity checkpoint. {@code dataKey} is {@code alias:<npcUuid>}; the JSON holds the body. */
    public record EntityCheckpoint(
            @Nonnull String profileId,
            @Nonnull String dataKey,
            @Nonnull String jsonPayload,
            long revision,
            long updatedAtMs) {
    }

    /** {@code companion_tool_link}. */
    public record ToolLink(
            @Nonnull String profileId,
            @Nonnull String toolUuid,
            @Nonnull String linkType,
            long createdAtMs,
            long updatedAtMs) {
    }

    /** {@code command_roster_membership}. The home fields are all set or all null. */
    public record RosterMembership(
            @Nonnull String slotId,
            @Nonnull String profileId,
            @Nonnull String ownerUuid,
            @Nonnull String familyId,
            long membershipRevision,
            @Nullable String groupId,
            boolean activeForBulkCommands,
            @Nullable String homeWorldKey,
            @Nullable Double homeX,
            @Nullable Double homeY,
            @Nullable Double homeZ,
            long createdAtMs,
            long updatedAtMs) {
    }

    /**
     * {@code timed_summon_lease}. {@code remainingMs} is the time a running summon had left when
     * it was last checkpointed, not an absolute end. A stored one may have {@code cooldownUntilMs}.
     */
    public record TimedLease(
            @Nonnull String profileId,
            @Nullable Long remainingMs,
            @Nullable Long cooldownUntilMs) {
    }

    /** {@code coop_slot}, including empty registrations. */
    public record CoopSlot(
            @Nonnull String coopKey,
            @Nonnull String worldKey,
            @Nonnull String coopId,
            int x,
            int y,
            int z,
            int residentSlot,
            long residencyRevision,
            @Nullable String activeOperationId,
            @Nullable String reservedProfileId) {
    }

    /** {@code coop_residency}. */
    public record CoopResidency(
            @Nonnull String coopKey,
            @Nonnull String profileId,
            @Nullable String housedNpcUuid,
            @Nonnull String snapshotId,
            long capturedAtMs,
            long updatedAtMs) {
    }

    /** {@code provisioning_record}. */
    public record Provisioning(
            @Nonnull String profileId,
            @Nonnull String callerNamespace,
            @Nonnull String callerKey,
            @Nullable String correlationId,
            long createdAtMs) {
    }

    /** A live {@code profile_extension_data} row. */
    public record ExtensionData(
            @Nonnull String profileId,
            @Nonnull String namespace,
            @Nonnull String dataKey,
            int payloadVersion,
            @Nonnull String jsonPayload,
            long revision,
            long createdAtMs,
            long updatedAtMs) {
    }

    /** The rows of {@code bonded-companions.sqlite} (schema version 1, the only one released). */
    public record Bonded(
            @Nonnull SourceFile source,
            @Nonnull List<BondedProfile> profiles,
            @Nonnull List<BondedLease> leases,
            @Nonnull List<BondedExtension> extensionData,
            @Nonnull List<BondedCleanupTarget> cleanupTargets,
            @Nonnull List<BondedCaptureSource> captureSources) {
    }

    /**
     * {@code bonded_companion_profile}, the columns the import uses. {@code snapshotJson} is the
     * {@code {encoding, payload}} envelope as stored. {@code summonCooldownUntilMs} uses 0 for unset.
     */
    public record BondedProfile(
            @Nonnull String profileId,
            @Nonnull String ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String roleId,
            @Nonnull String state,
            long revision,
            @Nonnull String snapshotJson,
            long updatedAtMs,
            @Nullable Long diedAtMs,
            long summonCooldownUntilMs) {
    }

    /** {@code bonded_companion_lease}: the live body of an ACTIVE bonded companion. */
    public record BondedLease(
            @Nonnull String profileId,
            @Nonnull String liveNpcUuid,
            @Nonnull String worldKey,
            long expiresAtMs) {
    }

    /** {@code bonded_companion_extension_data}; {@code jsonPayload} is the stored envelope. */
    public record BondedExtension(
            @Nonnull String profileId,
            @Nonnull String namespace,
            @Nonnull String jsonPayload,
            long revision,
            long updatedAtMs) {
    }

    /** {@code bonded_companion_cleanup}: a body the old runtime still meant to remove. */
    public record BondedCleanupTarget(
            @Nonnull String cleanupId,
            @Nonnull String profileId,
            @Nonnull String targetKind,
            @Nonnull String targetNpcUuid,
            @Nonnull String cleanupState,
            @Nonnull String worldKey) {
    }

    /** {@code bonded_companion_capture_source}: the wild body a bonded companion was made from. */
    public record BondedCaptureSource(
            @Nonnull String profileId,
            @Nonnull String sourceNpcUuid,
            @Nonnull String sourceWorldKey) {
    }
}
