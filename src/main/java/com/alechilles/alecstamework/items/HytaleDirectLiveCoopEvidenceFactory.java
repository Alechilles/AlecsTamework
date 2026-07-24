package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopAuthor;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Freezes Hytale live-entity and release-placement evidence before persistence submission.
 *
 * <p>Mutable component snapshots remain method locals. Returned capture evidence contains only
 * immutable domain values and an integrity-checked JSON payload.</p>
 */
final class HytaleDirectLiveCoopEvidenceFactory {
    private final CoopResidentStateSnapshotService snapshots =
            new CoopResidentStateSnapshotService();
    private final CoopResidentReleasePositionService releasePositions =
            new CoopResidentReleasePositionService();
    private final SnapshotCodecRegistry snapshotCodecs =
            TameworkSnapshotCodecs.create();

    @Nullable
    DirectLiveCoopAuthor.LiveNpcSource captureSource(
            @Nonnull HytaleDirectLiveCoopScanner.Scan scan,
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nonnull CoopSlotKey slot,
            @Nonnull HytaleDirectLiveCoopScanner.LiveNpc candidate,
            long observedAtMs
    ) {
        CoopResidentStateSnapshot snapshot =
                snapshots.captureSnapshotForPersistence(
                        candidate.reference(),
                        scan.entityStore(),
                        candidate.alias(),
                        candidate.roleId()
                );
        if (snapshot == null) {
            return null;
        }
        snapshot = withSlot(snapshot, coop.coopId(), slot.residentSlot());
        ProfileId profileId = new ProfileId(candidate.alias());
        NpcAlias alias = new NpcAlias(candidate.alias());
        String metadata = metadata(snapshot);
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                snapshot.npcName() == null
                        ? null : snapshot.npcName().getName(),
                candidate.roleId(),
                metadata,
                Sha256Hash.ofUtf8(metadata),
                coop.worldKey(),
                observedAtMs,
                observedAtMs,
                observedAtMs,
                0
        );
        UUID owner = owner(snapshot);
        CompanionProfileMutation.AdoptLive adoption =
                new CompanionProfileMutation.AdoptLive(
                        identity,
                        alias,
                        owner == null ? null : new OwnerId(owner),
                        coop.worldKey(),
                        toolLinks(profileId, snapshot, observedAtMs),
                        observedAtMs
                );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshotCodecs.encode(
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                        CoopResidentStateSnapshot.class,
                        snapshot
                );
        return new DirectLiveCoopAuthor.LiveNpcSource(
                profileId,
                alias,
                coop.worldKey(),
                adoption,
                slot,
                encoded
        );
    }

    private CoopResidentStateSnapshot withSlot(
            CoopResidentStateSnapshot source,
            String coopId,
            int residentSlot
    ) {
        return new CoopResidentStateSnapshot(
                source.npcUuid(),
                coopId,
                residentSlot,
                source.roleId(),
                source.commandLinks(),
                source.owner(),
                source.tamed(),
                source.npcName(),
                source.happiness(),
                source.needs(),
                source.breeding(),
                source.leveling(),
                source.traits(),
                source.talents(),
                source.lifeStage(),
                source.attachments(),
                source.healthPercent(),
                source.capturedAtMs()
        );
    }

    @Nullable
    CompanionSpawnPlacement releasePlacement(
            @Nonnull HytaleDirectLiveCoopScanner.Scan scan,
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nullable String roleId
    ) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        NPCPlugin plugin = NPCPlugin.get();
        int roleIndex = plugin == null ? -1 : plugin.getIndex(roleId);
        Builder<Role> builder = roleIndex < 0
                ? null : plugin.tryGetCachedValidRole(roleIndex);
        if (builder == null) {
            return null;
        }
        var offset = coop.config() == null
                ? null : coop.config().getLifecycleRules()
                .getResidentSpawnOffset();
        Vector3d position = releasePositions.resolveSpawnPosition(
                scan.world(),
                builder,
                coop.block(),
                coop.rotationIndex(),
                offset == null ? 0.0 : offset.getX(),
                offset == null ? 0.0 : offset.getY(),
                offset == null ? 0.0 : offset.getZ()
        );
        return new CompanionSpawnPlacement(
                coop.worldKey(),
                position.x,
                position.y,
                position.z,
                0.0f,
                0.0f,
                0.0f
        );
    }

    private List<CompanionToolLink> toolLinks(
            ProfileId profileId,
            CoopResidentStateSnapshot snapshot,
            long now
    ) {
        TameworkCommandLinksComponent links = snapshot.commandLinks();
        if (links == null || links.getToolIds() == null) {
            return List.of();
        }
        HashSet<UUID> unique = new HashSet<>();
        for (String value : links.getToolIds()) {
            try {
                unique.add(UUID.fromString(value));
            } catch (RuntimeException ignored) {
                // Invalid legacy tool text is not canonical identity evidence.
            }
        }
        return unique.stream().sorted().map(tool -> new CompanionToolLink(
                profileId, tool, "profile", now, now
        )).toList();
    }

    @Nullable
    private UUID owner(CoopResidentStateSnapshot snapshot) {
        if (snapshot.commandLinks() != null
                && snapshot.commandLinks().getOwnerId() != null) {
            return snapshot.commandLinks().getOwnerId();
        }
        return snapshot.owner() == null
                ? null : snapshot.owner().getOwnerId();
    }

    private String metadata(CoopResidentStateSnapshot snapshot) {
        JsonObject json = new JsonObject();
        if (snapshot.owner() != null
                && snapshot.owner().getOwnerName() != null) {
            json.addProperty(
                    "owner_name", snapshot.owner().getOwnerName()
            );
        }
        if (snapshot.npcName() != null
                && snapshot.npcName().getName() != null) {
            json.addProperty(
                    "custom_name", snapshot.npcName().getName()
            );
        }
        json.addProperty(
                "tamed",
                snapshot.tamed() != null && snapshot.tamed().isTamed()
        );
        return json.toString();
    }
}
