package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CompanionProfileSnapshotSink;
import com.alechilles.alecstamework.items.persistence.maintenance.LatestWorkCoordinator;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Publishes observed live metadata through replacement profile operations.
 *
 * <p>A missing profile is atomically adopted as the exact observed live
 * identity, owner, alias, and world. Later publications update metadata and
 * may reconcile the world of the exact current live alias; they never create
 * or rotate aliases.</p>
 */
public final class ReplacementProfileSnapshotSink
        implements CompanionProfileSnapshotSink {
    private static final String LINK_TYPE = "command";

    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final LongSupplier clock;
    private final Consumer<String> warnings;
    private final LatestWorkCoordinator<UUID, LiveSnapshot> coordinator;

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
        this.coordinator = new LatestWorkCoordinator<>(
                16,
                this::resolveWithWarning
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Void> publish(
            @Nonnull CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            @Nonnull String worldKey
    ) {
        if (snapshot == null || snapshot.npcUuid() == null
                || worldKey == null || worldKey.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return coordinator.submit(
                snapshot.npcUuid(),
                new LiveSnapshot(snapshot, worldKey.trim())
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Void> flush(@Nonnull UUID npcUuid) {
        return coordinator.flush(Objects.requireNonNull(npcUuid, "npcUuid"));
    }

    @Override
    @Nonnull
    public MaintenanceMetricsSnapshot metrics() {
        return coordinator.metrics();
    }

    @Override
    @Nonnull
    public MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        return coordinator.shutdown(Objects.requireNonNull(timeout, "timeout"));
    }

    private CompletionStage<Void> resolveWithWarning(
            UUID npcUuid,
            LiveSnapshot snapshot
    ) {
        try {
            CompletionStage<Void> result = resolve(snapshot);
            if (result == null) {
                throw new NullPointerException(
                        "Profile snapshot resolution returned no completion"
                );
            }
            return result.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    warn("profile_snapshot_publication_failed:npc="
                            + npcUuid + ':' + failureMessage(failure));
                }
            });
        } catch (Throwable failure) {
            warn("profile_snapshot_publication_failed:npc="
                    + npcUuid + ':' + failureMessage(failure));
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Void> resolve(
            LiveSnapshot observed
    ) {
        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot =
                observed.snapshot();
        NpcAlias alias = new NpcAlias(snapshot.npcUuid());
        return queries.findProfile(alias).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found) {
                return observeExisting(
                        found.value(), alias, snapshot, observed.worldKey()
                );
            }
            if (read instanceof PersistenceReadResult.Failed<?> failed) {
                return failedStorage(
                        "profile_snapshot_alias_read_failed",
                        failed.failure()
                );
            }
            ProfileId fallback = new ProfileId(snapshot.npcUuid());
            return queries.findProfile(fallback).thenCompose(byProfile -> {
                if (byProfile instanceof PersistenceReadResult.Found<
                        CompanionProfileReadModel> found) {
                    return observeExisting(
                            found.value(), alias, snapshot, observed.worldKey()
                    );
                }
                if (byProfile instanceof PersistenceReadResult.Failed<?>
                        failed) {
                    return failedStorage(
                            "profile_snapshot_profile_read_failed",
                            failed.failure()
                    );
                }
                return adopt(fallback, alias, snapshot, observed.worldKey());
            });
        });
    }

    private CompletionStage<Void> observeExisting(
            CompanionProfileReadModel current,
            NpcAlias alias,
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            String worldKey
    ) {
        if (!currentAliasCanBecomeActive(current, alias)) {
            return CompletableFuture.completedFuture(null);
        }
        return update(current, snapshot, worldKey)
                .thenCompose(ignored -> reconcileWorld(
                        current, alias, worldKey
                ));
    }

    private CompletionStage<Void> adopt(
            ProfileId profileId,
            NpcAlias alias,
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            String worldKey
    ) {
        long now = clock.getAsLong();
        OwnerId ownerId = snapshot.ownerId() == null
                ? null : new OwnerId(snapshot.ownerId());
        String metadata = metadata(
                null, null, snapshot, ownerId != null
        );
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                normalize(snapshot.displayName()),
                normalize(snapshot.roleId()),
                metadata,
                hash(metadata),
                worldKey,
                now,
                now,
                now,
                0L
        );
        return submitProfile(new CompanionProfileMutation.AdoptLive(
                identity,
                alias,
                ownerId,
                worldKey,
                links(profileId, List.of(), snapshot.toolIds(), now),
                now
        ));
    }

    private CompletionStage<Void> update(
            CompanionProfileReadModel current,
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            String worldKey
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
                || !Objects.equals(worldKey, before.lastKnownWorldKey())
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
                worldKey,
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

    private CompletionStage<Void> reconcileWorld(
            CompanionProfileReadModel current,
            NpcAlias alias,
            String worldKey
    ) {
        CompanionLifecycle lifecycle = current.lifecycle();
        if (worldKey.equals(lifecycle.location().worldKey())) {
            return CompletableFuture.completedFuture(null);
        }
        long now = clock.getAsLong();
        return submitProfile(new CompanionProfileMutation.ReconcileLoaded(
                current.identity().profileId(),
                lifecycle.revision(),
                lifecycle.lastReconciledGeneration(),
                alias,
                alias,
                worldKey,
                now
        ));
    }

    private boolean currentAliasCanBecomeActive(
            CompanionProfileReadModel current,
            NpcAlias alias
    ) {
        return current.currentAlias() != null
                && current.currentAlias().alias().equals(alias)
                && current.lifecycle().activeOperationId() == null
                && !current.lifecycle().quarantined()
                && (current.lifecycle().state() == LifecycleState.UNLOADED
                || (current.lifecycle().state() == LifecycleState.ACTIVE
                && alias.toString().equals(
                        current.lifecycle().location().key()
                )));
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
            return failed(
                    context + "_" + result.status().name()
                            .toLowerCase(Locale.ROOT),
                    result.failure()
            );
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
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
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
        return failed(code, null);
    }

    private <T> CompletionStage<T> failed(String code, Throwable cause) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code, cause)
        );
    }

    private <T> CompletionStage<T> failedStorage(
            String context,
            StorageFailure failure
    ) {
        return failed(
                context + ':' + failure.code(),
                failure.cause()
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

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        List<String> messages = new ArrayList<>();
        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().isBlank()) {
                messages.add(current.getMessage());
            }
            current = current.getCause();
            while ((current instanceof CompletionException
                    || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
        }
        if (messages.isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        if (messages.size() == 1) {
            return messages.get(0);
        }
        String deepest = messages.get(messages.size() - 1);
        return deepest + " (cause-chain: "
                + String.join(
                        " -> ", messages.subList(0, messages.size() - 1)
                )
                + ')';
    }

    private record LiveSnapshot(
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            String worldKey
    ) {
    }
}
