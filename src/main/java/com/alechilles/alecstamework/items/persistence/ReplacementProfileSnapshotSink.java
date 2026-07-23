package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CompanionProfileSnapshotSink;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Publishes observed live metadata through replacement profile operations.
 *
 * <p>This sink is ownership-neutral. It creates an unowned unloaded lifecycle
 * only when no profile exists, and never changes canonical owner or lifecycle
 * state during later metadata refreshes.</p>
 */
public final class ReplacementProfileSnapshotSink
        implements CompanionProfileSnapshotSink {
    private static final String LINK_TYPE = "command";

    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final LongSupplier clock;
    private final Consumer<String> warnings;
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> inFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CommandLinkedNpcDeathService
            .DeadLinkedNpcSnapshot> pending = new ConcurrentHashMap<>();

    public ReplacementProfileSnapshotSink(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull LongSupplier clock,
            @Nonnull Consumer<String> warnings
    ) {
        if (queries == null || operations == null || clock == null
                || warnings == null) {
            throw new IllegalArgumentException(
                    "Complete replacement profile snapshot dependencies are required"
            );
        }
        this.queries = queries;
        this.operations = operations;
        this.clock = clock;
        this.warnings = warnings;
    }

    @Override
    public void publish(
            @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        UUID npcUuid = snapshot.npcUuid();
        pending.put(npcUuid, snapshot);
        drain(npcUuid);
    }

    private void drain(UUID npcUuid) {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        if (inFlight.putIfAbsent(npcUuid, marker) != null) {
            return;
        }
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot =
                pending.remove(npcUuid);
        if (snapshot == null) {
            inFlight.remove(npcUuid, marker);
            return;
        }
        resolve(snapshot).whenComplete((ignored, failure) -> {
            if (failure != null) {
                warn("profile_snapshot_publication_failed:"
                        + failure.getClass().getSimpleName());
                marker.completeExceptionally(failure);
            } else {
                marker.complete(null);
            }
            inFlight.remove(npcUuid, marker);
            if (pending.containsKey(npcUuid)) {
                drain(npcUuid);
            }
        });
    }

    private CompletionStage<Void> resolve(
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        NpcAlias alias = new NpcAlias(snapshot.npcUuid());
        return queries.findProfile(alias).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found) {
                return update(found.value(), snapshot)
                        .thenCompose(ignored -> ensureAlias(
                                found.value().identity().profileId(),
                                found.value(),
                                alias
                        ));
            }
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return failed("profile_snapshot_alias_read_failed");
            }
            ProfileId fallback = new ProfileId(snapshot.npcUuid());
            return queries.findProfile(fallback).thenCompose(byProfile -> {
                if (byProfile instanceof PersistenceReadResult.Found<
                        CompanionProfileReadModel> found) {
                    return update(found.value(), snapshot)
                            .thenCompose(ignored -> ensureAlias(
                                    fallback, found.value(), alias
                            ));
                }
                if (byProfile instanceof PersistenceReadResult.Failed<?>) {
                    return failed("profile_snapshot_profile_read_failed");
                }
                return create(fallback, snapshot)
                        .thenCompose(ignored ->
                                ensureAlias(fallback, null, alias));
            });
        });
    }

    private CompletionStage<Void> create(
            ProfileId profileId,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        long now = clock.getAsLong();
        String metadata = metadata(null, null, snapshot, false);
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                normalize(snapshot.displayName()),
                normalize(snapshot.roleId()),
                metadata,
                hash(metadata),
                null,
                now,
                now,
                now,
                0L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                null,
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                now,
                ReconciliationGeneration.INITIAL,
                null
        );
        return submitProfile(new CompanionProfileMutation.Create(
                identity,
                lifecycle,
                links(profileId, List.of(), snapshot.toolIds(), now),
                now
        ));
    }

    private CompletionStage<Void> update(
            CompanionProfileReadModel current,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        CompanionIdentity before = current.identity();
        String displayName = first(
                snapshot.displayName(), before.displayName()
        );
        String roleId = first(snapshot.roleId(), before.roleId());
        boolean ownerMatches = current.lifecycle().ownerId() != null
                && snapshot.ownerId() != null
                && current.lifecycle().ownerId().value().equals(
                snapshot.ownerId()
        );
        String metadata = metadata(
                before.metadataJson(),
                current.lifecycle(),
                snapshot,
                ownerMatches
        );
        long now = clock.getAsLong();
        List<CompanionToolLink> links = links(
                before.profileId(),
                current.toolLinks(),
                snapshot.toolIds(),
                now
        );
        boolean changed = !Objects.equals(displayName, before.displayName())
                || !Objects.equals(roleId, before.roleId())
                || !Objects.equals(metadata, before.metadataJson())
                || !links.equals(current.toolLinks());
        if (!changed) {
            return CompletableFuture.completedFuture(null);
        }
        CompanionIdentity next = new CompanionIdentity(
                before.profileId(),
                displayName,
                roleId,
                metadata,
                hash(metadata),
                before.lastKnownWorldKey(),
                before.createdAtMs(),
                now,
                now,
                before.metadataRevision() + 1L
        );
        return submitProfile(new CompanionProfileMutation.Update(
                next,
                before.metadataRevision(),
                links,
                now
        ));
    }

    private CompletionStage<Void> ensureAlias(
            ProfileId profileId,
            CompanionProfileReadModel current,
            NpcAlias alias
    ) {
        if (current != null && current.currentAlias() != null
                && current.currentAlias().alias().equals(alias)) {
            return CompletableFuture.completedFuture(null);
        }
        long now = clock.getAsLong();
        OperationId operationId = OperationId.create();
        PublicOperationSubmission submitted = operations.rotateAlias(
                operationId,
                idempotency("live-alias", operationId),
                new CompanionAliasRotation(profileId, alias, now)
        );
        return completion("profile_snapshot_alias", submitted);
    }

    private CompletionStage<Void> submitProfile(
            CompanionProfileMutation mutation
    ) {
        OperationId operationId = OperationId.create();
        PublicOperationSubmission submitted = operations.mutateProfile(
                operationId,
                idempotency("live-profile", operationId),
                mutation
        );
        return completion("profile_snapshot_metadata", submitted);
    }

    private CompletionStage<Void> completion(
            String context,
            PublicOperationSubmission submitted
    ) {
        if (!submitted.accepted()) {
            return failed(context + "_not_accepted");
        }
        return submitted.completion().thenCompose(result -> {
            if (result.status()
                    == OperationWorkflowResult.Status.PUBLISHED) {
                return CompletableFuture.completedFuture(null);
            }
            return failed(context + "_" + result.status().name()
                    .toLowerCase(Locale.ROOT));
        });
    }

    private List<CompanionToolLink> links(
            ProfileId profileId,
            List<CompanionToolLink> existing,
            String[] values,
            long now
    ) {
        Map<UUID, CompanionToolLink> previous = new HashMap<>();
        existing.forEach(link -> previous.put(link.toolId(), link));
        ArrayList<UUID> ids = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                try {
                    UUID id = UUID.fromString(value);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (RuntimeException invalid) {
                    warn("profile_snapshot_tool_uuid_invalid");
                }
            }
        }
        ids.sort(UUID::compareTo);
        boolean sameLinks = existing.size() == ids.size();
        for (CompanionToolLink link : existing) {
            if (!LINK_TYPE.equals(link.linkType())
                    || !ids.contains(link.toolId())) {
                sameLinks = false;
                break;
            }
        }
        if (sameLinks) {
            return existing;
        }
        ArrayList<CompanionToolLink> result = new ArrayList<>();
        for (UUID id : ids) {
            CompanionToolLink old = previous.get(id);
            result.add(new CompanionToolLink(
                    profileId,
                    id,
                    LINK_TYPE,
                    old == null ? now : old.createdAtMs(),
                    now
            ));
        }
        return List.copyOf(result);
    }

    private String metadata(
            String existingJson,
            CompanionLifecycle lifecycle,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
            boolean ownerMatches
    ) {
        JsonObject json = object(existingJson);
        if (lifecycle != null && lifecycle.ownerId() == null) {
            json.remove("owner_name");
        } else if (ownerMatches && normalize(snapshot.ownerName()) != null) {
            json.addProperty("owner_name", snapshot.ownerName().trim());
        }
        if (normalize(snapshot.customName()) != null) {
            json.addProperty("custom_name", snapshot.customName().trim());
        }
        json.addProperty("tamed", snapshot.tamed());
        return json.isEmpty() ? null : json.toString();
    }

    private JsonObject object(String json) {
        if (json != null && !json.isBlank()) {
            try {
                return JsonParser.parseString(json).getAsJsonObject();
            } catch (RuntimeException invalid) {
                warn("profile_snapshot_metadata_invalid");
            }
        }
        return new JsonObject();
    }

    private Sha256Hash hash(String json) {
        return json == null ? null : Sha256Hash.ofUtf8(json);
    }

    private IdempotencyKey idempotency(
            String prefix,
            OperationId operationId
    ) {
        return new IdempotencyKey(prefix + ":" + operationId);
    }

    private <T> CompletionStage<T> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }

    private String first(String preferred, String fallback) {
        String normalized = normalize(preferred);
        return normalized != null ? normalized : normalize(fallback);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void warn(String code) {
        try {
            warnings.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must never break ECS snapshot publication.
        }
    }
}
