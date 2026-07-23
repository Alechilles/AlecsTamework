package com.alechilles.alecstamework.persistence.migration;

import java.util.List;
import javax.annotation.Nullable;

/** Immutable target rows produced by deterministic public-source analysis. */
record PublicImportPlan(
        List<Profile> profiles,
        List<Alias> aliases,
        List<ToolLink> toolLinks,
        List<Snapshot> snapshots,
        List<ExtensionData> extensionData,
        List<CoopSlot> coopSlots,
        List<CoopResidency> coopResidencies,
        List<Lifecycle> lifecycles,
        List<Incident> incidents
) {
    PublicImportPlan {
        profiles = List.copyOf(profiles);
        aliases = List.copyOf(aliases);
        toolLinks = List.copyOf(toolLinks);
        snapshots = List.copyOf(snapshots);
        extensionData = List.copyOf(extensionData);
        coopSlots = List.copyOf(coopSlots);
        coopResidencies = List.copyOf(coopResidencies);
        lifecycles = List.copyOf(lifecycles);
        incidents = List.copyOf(incidents);
    }

    record Profile(
            String profileId,
            @Nullable String displayName,
            @Nullable String roleId,
            @Nullable String metadataJson,
            @Nullable String metadataHash,
            @Nullable String lastKnownWorldKey,
            long createdAtMs,
            long updatedAtMs,
            long lastActiveAtMs
    ) {
    }

    record Alias(
            String npcUuid,
            String profileId,
            long generation,
            String state,
            long mappedAtMs,
            @Nullable Long retiredAtMs
    ) {
    }

    record ToolLink(
            String profileId,
            String toolUuid,
            String linkType,
            long createdAtMs,
            long updatedAtMs
    ) {
    }

    record Snapshot(
            String snapshotId,
            String profileId,
            String kind,
            int payloadVersion,
            String payloadJson,
            String payloadHash,
            boolean current,
            long createdAtMs
    ) {
    }

    record ExtensionData(
            String profileId,
            String namespace,
            String dataKey,
            String jsonPayload,
            long createdAtMs,
            long updatedAtMs
    ) {
    }

    record CoopSlot(
            String coopKey,
            String worldKey,
            String coopId,
            int x,
            int y,
            int z,
            int residentSlot
    ) {
    }

    record CoopResidency(
            String coopKey,
            String profileId,
            @Nullable String housedNpcUuid,
            String snapshotId,
            long capturedAtMs,
            long updatedAtMs
    ) {
    }

    record Lifecycle(
            String profileId,
            @Nullable String ownerUuid,
            @Nullable String ownerWorldKey,
            String state,
            String locationKind,
            @Nullable String locationKey,
            long changedAtMs,
            @Nullable String incidentId
    ) {
    }

    record Incident(
            String incidentId,
            String profileId,
            String reasonCode,
            String evidenceJson,
            long createdAtMs
    ) {
    }
}
