package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Selects command recipients from exact active bonded leases.
 *
 * <p>Bonded profiles, not command-link components or item metadata, own membership. A live NPC
 * becomes actionable only when its UUID and projection marker exactly match the current profile
 * lease in the player's roster and world.</p>
 */
final class BondedCompanionCommandRecipientSource {
    private final ProfileSource profiles;
    private final CommandLinkPolicyService rolePolicy;
    private final LongSupplier clock;

    BondedCompanionCommandRecipientSource(
            @Nonnull ProfileSource profiles,
            @Nonnull CommandLinkPolicyService rolePolicy,
            @Nonnull LongSupplier clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.rolePolicy = Objects.requireNonNull(rolePolicy, "rolePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static BondedCompanionCommandRecipientSource production(
            @Nullable Supplier<BondedCompanionApi> api,
            @Nonnull CommandLinkPolicyService rolePolicy) {
        Supplier<BondedCompanionApi> safeApi = api != null
                ? api : BondedCompanionApi::unavailable;
        return new BondedCompanionCommandRecipientSource(
                (owner, roster) -> readProfiles(safeApi, owner, roster),
                rolePolicy,
                System::currentTimeMillis);
    }

    @Nonnull
    List<Candidate> queryRecipients(@Nonnull Context context) {
        if (context.player == null || context.config == null
                || !context.config.usesBondedCompanionRoster()) return List.of();
        World world = context.player.getWorld();
        String rosterId = context.config.getBondedRosterId();
        String worldKey = world == null ? null : world.getName();
        if (context.player.getUuid() == null || world == null
                || worldKey == null || worldKey.isBlank()
                || rosterId == null || rosterId.isBlank()) return List.of();
        TransformComponent playerTransform = context.store.getComponent(
                context.playerRef, TransformComponent.getComponentType());
        Vector3d playerPosition = playerTransform == null
                ? null : new Vector3d(playerTransform.getPosition());
        double radius = context.config.getRadius();
        double radiusSq = radius >= 0D ? radius * radius : -1D;
        Request request = new Request(
                context.player.getUuid(), rosterId, worldKey,
                context.config, playerPosition, radiusSq,
                Math.max(1, context.config.getMaxTargets()));
        return select(
                request,
                uuid -> readProjection(context, world, uuid),
                physicalMarkerMultiplicity(context.store));
    }

    @Nonnull
    List<Candidate> select(
            @Nonnull Request request,
            @Nonnull ProjectionReader projectionReader) {
        return select(request, projectionReader, ProjectionMultiplicity.trusted());
    }

    @Nonnull
    List<Candidate> select(
            @Nonnull Request request,
            @Nonnull ProjectionReader projectionReader,
            @Nonnull ProjectionMultiplicity projectionMultiplicity) {
        Objects.requireNonNull(projectionMultiplicity, "projectionMultiplicity");
        Map<UUID, Authority> authorities = activeAuthorities(request);
        if (authorities.isEmpty()) return List.of();
        ArrayList<Selected> selected = new ArrayList<>(authorities.size());
        for (Map.Entry<UUID, Authority> entry : authorities.entrySet()) {
            Authority authority = entry.getValue();
            if (!isUnique(projectionMultiplicity, authority)) continue;
            LoadedProjection projection = readSafely(
                    projectionReader, entry.getKey());
            if (!matches(authority, projection)
                    || !rolePolicy.isRoleAllowed(projection.roleId(), request.config())) {
                continue;
            }
            double distanceSq = distanceSq(
                    request.playerPosition(), projection.position());
            if (Double.isNaN(distanceSq)) {
                if (request.radiusSq() >= 0D) continue;
                distanceSq = 0D;
            } else if (request.radiusSq() >= 0D
                    && distanceSq > request.radiusSq()) {
                continue;
            }
            selected.add(new Selected(authority.profileId(), projection, distanceSq));
        }
        selected.sort(Comparator.comparingDouble(Selected::distanceSq)
                .thenComparing(Selected::profileId)
                .thenComparing(value -> value.projection().npc().getUuid()));
        int count = Math.min(request.maxTargets(), selected.size());
        ArrayList<Candidate> recipients = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Selected value = selected.get(index);
            recipients.add(new Candidate(
                    value.projection().ref(), value.projection().npc(),
                    value.distanceSq(), value.profileId()));
        }
        return recipients;
    }

    @Nullable
    private LoadedProjection readSafely(
            ProjectionReader reader, UUID liveNpcUuid) {
        try {
            return reader.read(liveNpcUuid);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private boolean isUnique(
            ProjectionMultiplicity multiplicity, Authority authority) {
        try {
            return multiplicity.isUnique(
                    authority.profileId(), authority.leaseToken());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private ProjectionMultiplicity physicalMarkerMultiplicity(
            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (store == null || markerType == null) {
            return ProjectionMultiplicity.unavailable();
        }
        HashMap<MarkerIdentity, Integer> counts = new HashMap<>();
        try {
            store.forEachChunk(
                    Query.and(markerType),
                    (ArchetypeChunk<EntityStore> chunk,
                            CommandBuffer<EntityStore> commandBuffer) -> {
                        for (int index = 0; index < chunk.size(); index++) {
                            TameworkProjectionIdentityComponent marker =
                                    chunk.getComponent(index, markerType);
                            MarkerIdentity identity = MarkerIdentity.from(marker);
                            if (identity != null) counts.merge(identity, 1, Integer::sum);
                        }
                    });
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionMultiplicity.unavailable();
        }
        Map<MarkerIdentity, Integer> snapshot = Map.copyOf(counts);
        return (profileId, leaseToken) -> snapshot.getOrDefault(
                new MarkerIdentity(profileId, leaseToken), 0) == 1;
    }

    private Map<UUID, Authority> activeAuthorities(Request request) {
        List<BondedCompanionProfileView> listed;
        try {
            listed = profiles.list(request.ownerUuid(), request.rosterId());
        } catch (RuntimeException | LinkageError failure) {
            return Map.of();
        }
        if (listed == null || listed.isEmpty()) return Map.of();
        HashMap<UUID, Authority> exact = new HashMap<>();
        HashSet<UUID> ambiguous = new HashSet<>();
        long now = clock.getAsLong();
        for (BondedCompanionProfileView profile : listed) {
            Authority authority = authority(request, profile, now);
            if (authority == null || ambiguous.contains(authority.liveNpcUuid())) continue;
            if (exact.putIfAbsent(authority.liveNpcUuid(), authority) != null) {
                exact.remove(authority.liveNpcUuid());
                ambiguous.add(authority.liveNpcUuid());
            }
        }
        return Map.copyOf(exact);
    }

    @Nullable
    private Authority authority(
            Request request, @Nullable BondedCompanionProfileView profile,
            long now) {
        if (profile == null || profile.state() != BondedCompanionStateView.ACTIVE
                || !request.ownerUuid().equals(profile.ownerUuid())
                || !request.rosterId().equals(profile.rosterId())) return null;
        BondedCompanionLeaseView lease = profile.activeLease();
        if (lease == null || lease.liveNpcUuid() == null
                || !request.worldKey().equals(lease.worldKey())
                || lease.expiresAtMs() != 0L && lease.expiresAtMs() <= now) return null;
        return new Authority(
                profile.profileId(), lease.leaseToken(), lease.liveNpcUuid());
    }

    private boolean matches(
            Authority authority, @Nullable LoadedProjection projection) {
        return projection != null
                && projection.ref().isValid()
                && authority.liveNpcUuid().equals(projection.npc().getUuid())
                && projection.marker().matches(
                        TameworkProjectionIdentityComponent.KIND_BONDED_COMPANION,
                        authority.leaseToken(), authority.profileId());
    }

    private double distanceSq(
            @Nullable Vector3d player, @Nullable Vector3d npc) {
        if (player == null || npc == null) return Double.NaN;
        double dx = npc.x - player.x;
        double dy = npc.y - player.y;
        double dz = npc.z - player.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Nullable
    private LoadedProjection readProjection(
            Context context, World world, UUID liveNpcUuid) {
        Ref<EntityStore> ref = world.getEntityRef(liveNpcUuid);
        if (ref == null || !ref.isValid() || ref.getStore() != context.store) return null;
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (markerType == null) return null;
        NPCEntity npc = context.store.getComponent(ref, NPCEntity.getComponentType());
        TameworkProjectionIdentityComponent marker = context.store.getComponent(
                ref, markerType);
        if (npc == null || marker == null) return null;
        TransformComponent transform = context.store.getComponent(
                ref, TransformComponent.getComponentType());
        Vector3d position = transform == null
                ? null : new Vector3d(transform.getPosition());
        return new LoadedProjection(
                ref, npc, marker, position, rolePolicy.resolveRoleId(npc));
    }

    private static List<BondedCompanionProfileView> readProfiles(
            Supplier<BondedCompanionApi> api,
            UUID ownerUuid,
            String rosterId) {
        try {
            BondedCompanionApi current = api.get();
            if (current == null || !current.availability().available()) return List.of();
            CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
                    pending = current.list(ownerUuid, rosterId);
            if (pending == null || !pending.isDone()) return List.of();
            BondedCompanionResult<List<BondedCompanionProfileView>> result =
                    pending.getNow(null);
            return result != null && result.successful() && result.value() != null
                    ? List.copyOf(result.value()) : List.of();
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    @FunctionalInterface
    interface ProfileSource {
        List<BondedCompanionProfileView> list(UUID ownerUuid, String rosterId);
    }

    @FunctionalInterface
    interface ProjectionReader {
        @Nullable LoadedProjection read(UUID liveNpcUuid);
    }

    @FunctionalInterface
    interface ProjectionMultiplicity {
        boolean isUnique(String profileId, String leaseToken);

        static ProjectionMultiplicity trusted() {
            return (profileId, leaseToken) -> true;
        }

        static ProjectionMultiplicity unavailable() {
            return (profileId, leaseToken) -> false;
        }
    }

    record Request(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String worldKey,
            @Nonnull TwCommandItemConfig config,
            @Nullable Vector3d playerPosition,
            double radiusSq,
            int maxTargets) {
        Request {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = required(rosterId, "rosterId");
            worldKey = required(worldKey, "worldKey");
            Objects.requireNonNull(config, "config");
            playerPosition = playerPosition == null
                    ? null : new Vector3d(playerPosition);
            maxTargets = Math.max(1, maxTargets);
        }
    }

    record LoadedProjection(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull NPCEntity npc,
            @Nonnull TameworkProjectionIdentityComponent marker,
            @Nullable Vector3d position,
            @Nullable String roleId) {
        LoadedProjection {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(npc, "npc");
            Objects.requireNonNull(marker, "marker");
            position = position == null ? null : new Vector3d(position);
        }
    }

    private record Authority(String profileId, String leaseToken,
                             UUID liveNpcUuid) { }

    private record MarkerIdentity(String profileId, String leaseToken) {
        @Nullable
        private static MarkerIdentity from(
                @Nullable TameworkProjectionIdentityComponent marker) {
            if (marker == null || !marker.isBondedCompanion()
                    || marker.getProfileId() == null
                    || marker.getBondedLeaseToken() == null) return null;
            return new MarkerIdentity(
                    marker.getProfileId(), marker.getBondedLeaseToken());
        }
    }

    private record Selected(String profileId, LoadedProjection projection,
                            double distanceSq) { }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
