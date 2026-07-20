package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.sqlite.LostRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks linked NPCs that exhausted relocation retries and now require recovery.
 *
 * <p>The service also stores strict replacement mappings so stale originals are despawned
 * if they reappear after a replacement was created.
 */
public final class CommandLinkedNpcLostService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String VECTOR_SEPARATOR = ",";

    private final ConcurrentHashMap<UUID, LostLinkedNpcSnapshot> snapshotsByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> recoverySnapshotsByNpc =
            new ConcurrentHashMap<>();
    private final CommandRecoveredSourceSuppressionIndex recoveredSourceSuppressions;
    @Nullable
    private final LostRepository repository;
    @Nullable
    private final PersistenceHealthService healthService;
    private final Path persistencePath;
    private final Object persistenceLock = new Object();
    @Nullable
    private final HytaleLogger logger;
    @Nullable
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;
    @Nullable
    private final CommandLostTransitionPersistenceService transitionPersistenceService;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;

    public CommandLinkedNpcLostService() {
        this((Path) null, null, null, null, null, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath) {
        this(persistencePath, null, null, null, null, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath, @Nullable HytaleLogger logger) {
        this(persistencePath, logger, null, null, null, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath,
                                       @Nullable HytaleLogger logger,
                                       @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this(persistencePath, logger, stateSnapshotService, null, null, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath,
                                       @Nullable HytaleLogger logger,
                                       @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                       @Nullable CommandLinkedNpcCaptureService captureService) {
        this(persistencePath, logger, stateSnapshotService, captureService, null, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath,
                                       @Nullable HytaleLogger logger,
                                       @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                       @Nullable CommandLinkedNpcCaptureService captureService,
                                       @Nullable CommandLinkedNpcCoopService coopService) {
        this(persistencePath, logger, stateSnapshotService, captureService, coopService, null, null, null);
    }

    public CommandLinkedNpcLostService(@Nullable HytaleLogger logger,
                                       @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                       @Nullable CommandLinkedNpcCaptureService captureService,
                                       @Nullable CommandLinkedNpcCoopService coopService,
                                       @Nonnull LostRepository repository,
                                       @Nonnull PersistenceHealthService healthService) {
        this(null, logger, stateSnapshotService, captureService, coopService, repository, healthService, null);
    }

    public CommandLinkedNpcLostService(@Nullable HytaleLogger logger,
                                       @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                       @Nullable CommandLinkedNpcCaptureService captureService,
                                       @Nullable CommandLinkedNpcCoopService coopService,
                                       @Nonnull LostRepository repository,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nonnull NpcIdentityRepository identityRepository,
                                       @Nonnull LoadedNpcIdentityIndex loadedIdentityIndex) {
        this(
                null,
                logger,
                stateSnapshotService,
                captureService,
                coopService,
                repository,
                healthService,
                new CommandNpcProfileActionResolver(
                        new CommandNpcIdentityService(
                                identityRepository,
                                new CommandNpcExistenceService(loadedIdentityIndex)
                        )
                )
        );
    }

    @Nonnull
    public static List<LostLinkedNpcSnapshot> loadLegacySnapshots(@Nullable Path legacyPath) {
        if (legacyPath == null || !Files.exists(legacyPath)) {
            return List.of();
        }
        CommandLinkedNpcLostService service = new CommandLinkedNpcLostService(
                legacyPath,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        return List.copyOf(service.snapshotsByNpc.values());
    }

    CommandLinkedNpcLostService(@Nullable Path persistencePath,
                                @Nullable HytaleLogger logger,
                                @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                @Nullable CommandLinkedNpcCaptureService captureService,
                                @Nullable CommandLinkedNpcCoopService coopService,
                                @Nullable LostRepository repository,
                                @Nullable PersistenceHealthService healthService,
                                @Nullable CommandNpcProfileActionResolver profileActionResolver) {
        this.persistencePath = persistencePath != null ? persistencePath.toAbsolutePath().normalize() : null;
        this.logger = logger;
        this.stateSnapshotService = stateSnapshotService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.repository = repository;
        this.healthService = healthService;
        this.profileActionResolver = profileActionResolver;
        this.recoveredSourceSuppressions = new CommandRecoveredSourceSuppressionIndex(repository);
        this.transitionPersistenceService = repository != null && stateSnapshotService != null
                ? new CommandLostTransitionPersistenceService(stateSnapshotService, repository)
                : null;
        loadPersistedSnapshots();
    }

    public boolean recordLostFromRelocationDrop(UUID npcUuid,
                                                @Nullable UUID ownerUuid,
                                                @Nullable Vector3d sourceHintPosition,
                                                @Nullable Vector3d alternateSourceHintPosition,
                                                @Nullable Vector3d destination,
                                                long queuedAtMs,
                                                long droppedAtMs,
                                                int retryAttempts) {
        if (npcUuid == null) {
            return false;
        }
        npcUuid = resolveLostTransitionUuid(npcUuid);
        if (npcUuid == null) {
            return false;
        }
        if (captureService != null && captureService.getCapturedSnapshot(npcUuid) != null) {
            clearLostSnapshot(npcUuid);
            if (logger != null) {
                logger.at(Level.FINE).log(
                        "Skipped lost transition for captured companion (npc=" + npcUuid + ")."
                );
            }
            return false;
        }
        if (coopService != null && coopService.getCoopSnapshot(npcUuid) != null) {
            clearLostSnapshot(npcUuid);
            if (logger != null) {
                logger.at(Level.FINE).log(
                        "Skipped lost transition for cooped companion (npc=" + npcUuid + ")."
                );
            }
            return false;
        }
        LostLinkedNpcSnapshot current = snapshotsByNpc.get(npcUuid);
        long now = System.currentTimeMillis();
        LostLinkedNpcSnapshot prepared = CommandLostTransitionPersistenceService.prepare(
                current,
                npcUuid,
                sourceHintPosition,
                alternateSourceHintPosition,
                destination,
                queuedAtMs,
                droppedAtMs,
                retryAttempts,
                now
        );
        if (repository != null) {
            CommandLostTransitionPersistenceService.PersistStatus status =
                    transitionPersistenceService != null
                            ? transitionPersistenceService.persist(
                                    prepared,
                                    committed -> {
                                        snapshotsByNpc.put(committed.npcUuid(), committed);
                                        CommandLostTransitionLogger.committed(logger, committed, ownerUuid);
                                    }
                            )
                            : CommandLostTransitionPersistenceService.PersistStatus.MISSING_FULL_SNAPSHOT;
            if (status != CommandLostTransitionPersistenceService.PersistStatus.ACCEPTED_PENDING
                    && status != CommandLostTransitionPersistenceService.PersistStatus.ALREADY_PENDING) {
                CommandLostTransitionLogger.rejected(logger, npcUuid, status);
            }
            return status == CommandLostTransitionPersistenceService.PersistStatus.ACCEPTED_PENDING
                    || status == CommandLostTransitionPersistenceService.PersistStatus.ALREADY_PENDING;
        }
        snapshotsByNpc.put(prepared.npcUuid(), prepared);
        CommandLostTransitionLogger.committed(logger, prepared, ownerUuid);
        persistSnapshots();
        return true;
    }

    @Nullable
    private UUID resolveLostTransitionUuid(@Nonnull UUID droppedNpcUuid) {
        if (profileActionResolver == null) {
            return droppedNpcUuid;
        }
        CommandNpcProfileActionResolver.ActionTarget target =
                profileActionResolver.resolveLostTransition(droppedNpcUuid);
        if (target.isActionable()) {
            return target.targetNpcUuid();
        }
        if (logger != null) {
            Level level = target.status() == CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED
                    ? Level.FINE
                    : Level.WARNING;
            logger.at(level).log(
                    "Skipped profile-aware lost transition (droppedNpc="
                            + droppedNpcUuid
                            + ", profile="
                            + target.profileId()
                            + ", status="
                            + target.status()
                            + ", reason="
                            + target.reason()
                            + ")."
            );
        }
        return null;
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return;
        }
        if (transitionPersistenceService != null) {
            CommandLostTransitionLogger.cancelled(
                    logger, npcUuid, transitionPersistenceService.cancel(npcUuid));
        }
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot =
                buildRecoverySnapshot(reference, store, npc);
        if (recoverySnapshot != null) {
            recoverySnapshotsByNpc.put(npcUuid, recoverySnapshot);
        } else {
            recoverySnapshotsByNpc.remove(npcUuid);
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        UUID replacementNpcUuid = snapshot != null ? snapshot.replacementNpcUuid() : null;
        if (replacementNpcUuid == null) {
            replacementNpcUuid = recoveredSourceSuppressions.replacementFor(npcUuid);
        }
        if (replacementNpcUuid != null) {
            npc.setToDespawn();
            if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Suppressed stale linked companion after strict recovery mapping (oldNpc="
                                + npcUuid
                                + ", replacementNpc="
                                + replacementNpcUuid
                                + ")."
                );
            }
            return;
        }
        if (snapshot == null) {
            return;
        }
        if (snapshotsByNpc.remove(npcUuid, snapshot)) {
            deleteSnapshot(npcUuid);
        }
    }

    public void onNpcRemoved(Ref<EntityStore> reference,
                             RemoveReason reason,
                             Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return;
        }
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot =
                buildRecoverySnapshot(reference, store, npc);
        if (recoverySnapshot != null) {
            recoverySnapshotsByNpc.put(npcUuid, recoverySnapshot);
            return;
        }
        if (reason == RemoveReason.REMOVE) {
            recoverySnapshotsByNpc.remove(npcUuid);
        }
    }

    @Nullable
    public LostLinkedNpcSnapshot getLostSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        if (snapshot == null || !snapshot.isAwaitingRecovery()) {
            return null;
        }
        return snapshot;
    }

    public boolean isLost(UUID npcUuid) {
        return getLostSnapshot(npcUuid) != null;
    }

    /**
     * Exposes accepted transitions only to entity-removal lifecycle classification.
     * Player-facing lost state remains gated on {@link #isLost(UUID)} after durable commit.
     */
    public boolean isLostOrTransitionPending(UUID npcUuid) {
        return isLost(npcUuid)
                || transitionPersistenceService != null
                && transitionPersistenceService.isPending(npcUuid);
    }

    @Nullable
    public CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot getRecoverySnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        if (stateSnapshotService != null) {
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot shared = stateSnapshotService.getSnapshot(npcUuid);
            if (shared != null) {
                return shared;
            }
        }
        return recoverySnapshotsByNpc.get(npcUuid);
    }

    @Nullable
    public UUID getReplacementUuid(UUID originalNpcUuid) {
        if (originalNpcUuid == null) {
            return null;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(originalNpcUuid);
        return snapshot != null ? snapshot.replacementNpcUuid() : null;
    }

    public void clearLostSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        if (transitionPersistenceService != null) {
            CommandLostTransitionLogger.cancelled(
                    logger, npcUuid, transitionPersistenceService.cancel(npcUuid));
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        if (snapshot == null || !snapshot.isAwaitingRecovery()) {
            return;
        }
        if (snapshotsByNpc.remove(npcUuid, snapshot)) {
            deleteSnapshot(npcUuid);
        }
    }

    public void markRecovered(UUID originalNpcUuid,
                              UUID replacementNpcUuid,
                              @Nullable Vector3d lastKnownPosition,
                              @Nullable Vector3d homePosition) {
        if (originalNpcUuid == null || replacementNpcUuid == null || originalNpcUuid.equals(replacementNpcUuid)) {
            return;
        }
        if (transitionPersistenceService != null) {
            CommandLostTransitionLogger.cancelled(
                    logger, originalNpcUuid, transitionPersistenceService.cancel(originalNpcUuid));
        }
        LostLinkedNpcSnapshot existing = snapshotsByNpc.get(originalNpcUuid);
        long now = System.currentTimeMillis();
        Vector3d resolvedLastKnown = copyVector(lastKnownPosition);
        Vector3d resolvedHome = copyVector(homePosition);
        long queuedAtMs = now;
        int retries = 0;
        if (existing != null) {
            if (resolvedLastKnown == null) {
                resolvedLastKnown = copyVector(existing.lastKnownPosition());
            }
            if (resolvedHome == null) {
                resolvedHome = copyVector(existing.homePosition());
            }
            queuedAtMs = existing.lastRelocationQueuedAtMs() != 0L
                    ? existing.lastRelocationQueuedAtMs()
                    : queuedAtMs;
            retries = Math.max(0, existing.relocationRetryAttempts());
        }
        snapshotsByNpc.put(
                originalNpcUuid,
                new LostLinkedNpcSnapshot(
                        originalNpcUuid,
                        resolvedLastKnown,
                        resolvedHome,
                        queuedAtMs,
                        now,
                        retries,
                        replacementNpcUuid,
                        now
                )
        );
        recoveredSourceSuppressions.record(originalNpcUuid, replacementNpcUuid);
        if (stateSnapshotService != null) {
            stateSnapshotService.clearSnapshot(originalNpcUuid);
        }
        recoverySnapshotsByNpc.remove(originalNpcUuid);
        persistSnapshot(snapshotsByNpc.get(originalNpcUuid));
    }

    private void loadPersistedSnapshots() {
        if (repository != null) {
            for (LostLinkedNpcSnapshot snapshot : repository.loadAll()) {
                if (snapshot == null || snapshot.npcUuid() == null) {
                    continue;
                }
                snapshotsByNpc.put(snapshot.npcUuid(), snapshot);
            }
            return;
        }
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            if (!Files.exists(persistencePath)) {
                return;
            }
            try {
                List<String> lines = Files.readAllLines(persistencePath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    LostLinkedNpcSnapshot snapshot = parseSnapshot(line);
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    snapshotsByNpc.put(snapshot.npcUuid(), snapshot);
                }
            } catch (Exception ex) {
                TameworkTelemetryEvents.recordErrorIfAvailable(
                        "lost_snapshot_load_failed",
                        ex,
                        TameworkTelemetryContext.persistence(
                                "lost",
                                "snapshot_load",
                                "read_failed",
                                "Failed to load lost linked-NPC snapshots."
                        ).build()
                );
                // Ignore persistence read issues; runtime updates still track new lost companions.
            }
        }
    }

    private void persistSnapshots() {
        if (repository != null) {
            return;
        }
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (snapshotsByNpc.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (LostLinkedNpcSnapshot snapshot : snapshotsByNpc.values()) {
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(encodeSnapshot(snapshot));
                }
                Files.writeString(persistencePath, builder.toString(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                TameworkTelemetryEvents.recordErrorIfAvailable(
                        "lost_snapshot_persist_failed",
                        ex,
                        TameworkTelemetryContext.persistence(
                                "lost",
                                "snapshot_persist",
                                "write_failed",
                                "Failed to persist lost linked-NPC snapshots."
                        ).build()
                );
                // Ignore persistence write issues; runtime tracking remains available.
            }
        }
    }

    private void persistSnapshot(@Nullable LostLinkedNpcSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        if (repository != null) {
            repository.upsertAsync(snapshot);
            return;
        }
        persistSnapshots();
    }

    private void deleteSnapshot(@Nonnull UUID npcUuid) {
        if (repository != null) {
            repository.deleteAsync(npcUuid);
            return;
        }
        persistSnapshots();
    }

    private String encodeSnapshot(LostLinkedNpcSnapshot snapshot) {
        return snapshot.npcUuid()
                + FIELD_SEPARATOR + encodeVector(snapshot.lastKnownPosition())
                + FIELD_SEPARATOR + encodeVector(snapshot.homePosition())
                + FIELD_SEPARATOR + snapshot.lastRelocationQueuedAtMs()
                + FIELD_SEPARATOR + snapshot.lostAtMs()
                + FIELD_SEPARATOR + snapshot.relocationRetryAttempts()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.replacementNpcUuid())
                + FIELD_SEPARATOR + snapshot.recoveredAtMs();
    }

    @Nullable
    private LostLinkedNpcSnapshot parseSnapshot(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length < 6) {
            return null;
        }
        UUID npcUuid = decodeNullableUuid(parts[0]);
        if (npcUuid == null) {
            return null;
        }
        Vector3d lastKnown = decodeVector(parts[1]);
        Vector3d home = decodeVector(parts[2]);
        long queuedAtMs = parseLong(parts[3], System.currentTimeMillis());
        long lostAtMs = parseLong(parts[4], queuedAtMs);
        int retries = Math.max(0, parseInt(parts[5], 0));
        UUID replacementUuid = parts.length > 6 ? decodeNullableUuid(parts[6]) : null;
        long recoveredAtMs = parts.length > 7 ? parseLong(parts[7], 0L) : 0L;
        return new LostLinkedNpcSnapshot(
                npcUuid,
                lastKnown,
                home,
                queuedAtMs,
                lostAtMs,
                retries,
                replacementUuid,
                recoveredAtMs
        );
    }

    private String encodeNullableUuid(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    @Nullable
    private UUID decodeNullableUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "";
        }
        return vector.x + VECTOR_SEPARATOR + vector.y + VECTOR_SEPARATOR + vector.z;
    }

    @Nullable
    private Vector3d decodeVector(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] values = raw.split(VECTOR_SEPARATOR, -1);
        if (values.length < 3) {
            return null;
        }
        try {
            return new Vector3d(
                    Double.parseDouble(values[0]),
                    Double.parseDouble(values[1]),
                    Double.parseDouble(values[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nullable
    private Vector3d copyVector(@Nullable Vector3d value) {
        return value != null ? new Vector3d(value) : null;
    }

    @Nullable
    private CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot buildRecoverySnapshot(Ref<EntityStore> npcRef,
                                                                                     Store<EntityStore> store,
                                                                                     NPCEntity npc) {
        if (npcRef == null || !npcRef.isValid() || store == null || npc == null || npc.getUuid() == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(npcRef, linksType) : null;
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            return null;
        }
        String[] toolIds = sanitizeToolIds(links.getToolIds());
        if (toolIds.length == 0) {
            return null;
        }

        UUID ownerId = links.getOwnerId();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent ownerComponent = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
        if (ownerComponent != null && ownerComponent.getOwnerId() != null) {
            ownerId = ownerComponent.getOwnerId();
        }
        String ownerName = ownerComponent != null ? ownerComponent.getOwnerName() : null;
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);

        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breedingComponent = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        String breedingConfigId = breedingComponent != null ? breedingComponent.getConfigId() : null;
        Double breedingHappiness = breedingComponent != null ? breedingComponent.getHappiness() : null;
        boolean breedingEnabled = breedingComponent != null && breedingComponent.isEnabled();
        long breedingCooldownUntilMs = breedingComponent != null ? breedingComponent.getCooldownUntilMs() : 0L;
        UUID breedingLastPartnerUuid = breedingComponent != null ? breedingComponent.getLastPartnerUuid() : null;

        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happinessComponent = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        String happinessConfigId = happinessComponent != null ? happinessComponent.getConfigId() : null;
        Double happinessValue = null;
        if (happinessComponent != null) {
            happinessValue = happinessComponent.getValue();
        } else if (breedingHappiness != null) {
            happinessValue = breedingHappiness;
        }
        long happinessLastUpdateMs = happinessComponent != null
                ? happinessComponent.getLastUpdateMs()
                : breedingComponent != null
                ? breedingComponent.getLastHappinessUpdateMs()
                : 0L;

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent traitsComponent = traitsType != null ? store.getComponent(npcRef, traitsType) : null;
        String traitsConfigId = traitsComponent != null ? traitsComponent.getConfigId() : null;
        long traitsRollSeed = traitsComponent != null ? traitsComponent.getRollSeed() : 0L;
        String traitsValues = traitsComponent != null ? TraitValueCodec.encode(traitsComponent.getTraitValues()) : null;
        if (traitsValues != null && traitsValues.isBlank()) {
            traitsValues = null;
        }

        ComponentType<EntityStore, TameworkLevelingComponent> levelingType = TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent levelingComponent = levelingType != null ? store.getComponent(npcRef, levelingType) : null;
        String levelingConfigId = levelingComponent != null ? levelingComponent.getConfigId() : null;
        int levelingLevel = levelingComponent != null ? levelingComponent.getLevel() : 1;
        double levelingTotalXp = levelingComponent != null ? levelingComponent.getTotalXp() : 0.0;

        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talentsComponent = talentsType != null ? store.getComponent(npcRef, talentsType) : null;
        String talentsConfigId = talentsComponent != null ? talentsComponent.getConfigId() : null;
        int talentsSpentPoints = talentsComponent != null ? talentsComponent.getSpentPoints() : 0;
        String purchasedTalentIds = talentsComponent != null ? TalentIdCodec.encode(talentsComponent.getPurchasedTalentIds()) : null;

        ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent lifeStageComponent = lifeStageType != null
                ? store.getComponent(npcRef, lifeStageType)
                : null;
        String lifeStage = lifeStageComponent != null ? lifeStageComponent.getStage() : null;
        long lifeStageBornAtMs = lifeStageComponent != null ? lifeStageComponent.getBornAtMs() : 0L;
        long lifeStageAdolescentAtMs = lifeStageComponent != null ? lifeStageComponent.getAdolescentAtMs() : 0L;
        long lifeStageAdultAtMs = lifeStageComponent != null ? lifeStageComponent.getAdultAtMs() : 0L;
        long lifeStageFullyGrownAtMs = lifeStageComponent != null ? lifeStageComponent.getFullyGrownAtMs() : 0L;
        double lifeStageBabyScale = lifeStageComponent != null ? lifeStageComponent.getBabyScale() : 0.55;
        double lifeStageAdolescentScale = lifeStageComponent != null ? lifeStageComponent.getAdolescentScale() : 0.80;
        double lifeStageAdolescentSwitchScale = lifeStageComponent != null
                ? lifeStageComponent.getAdolescentSwitchScale()
                : 0.80;
        double lifeStageAdultStartScale = lifeStageComponent != null ? lifeStageComponent.getAdultStartScale() : 0.80;
        double lifeStageAdultSwitchScale = lifeStageComponent != null ? lifeStageComponent.getAdultSwitchScale() : 1.00;
        double lifeStageAdultScale = lifeStageComponent != null ? lifeStageComponent.getAdultScale() : 1.00;
        boolean lifeStageGrowthScalingEnabled = lifeStageComponent != null
                && lifeStageComponent.isGrowthScalingEnabled();
        String lifeStageGender = lifeStageComponent != null ? lifeStageComponent.getGender() : null;

        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        TameworkAttachmentsComponent attachmentsComponent = attachmentsType != null
                ? store.getComponent(npcRef, attachmentsType)
                : null;
        String attachmentsConfigId = attachmentsComponent != null ? attachmentsComponent.getConfigId() : null;
        Map<String, String> attachmentSelections = attachmentsComponent != null
                && attachmentsComponent.getAttachmentIds() != null
                && !attachmentsComponent.getAttachmentIds().isEmpty()
                ? attachmentsComponent.getAttachmentIds()
                : CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        String attachmentsValues = CommandLinkedNpcDeathService.encodeAttachmentSelections(attachmentSelections);

        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        String roleId = resolveRoleId(npc);
        String customName = resolveCustomName(npcRef, store);
        String displayName = resolveDisplayName(npcRef, store, npc, roleId, customName);
        long snapshotAtMs = System.currentTimeMillis();

        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                npc.getUuid(),
                ownerId,
                ownerName,
                toolIds,
                roleId,
                tamed,
                customName,
                displayName,
                lastKnownPosition,
                homePosition,
                snapshotAtMs,
                snapshotAtMs,
                breedingConfigId,
                breedingHappiness,
                breedingCooldownUntilMs,
                breedingLastPartnerUuid,
                traitsConfigId,
                traitsRollSeed,
                traitsValues,
                happinessConfigId,
                happinessValue,
                happinessLastUpdateMs,
                lifeStage,
                lifeStageBornAtMs,
                lifeStageAdolescentAtMs,
                lifeStageAdultAtMs,
                lifeStageFullyGrownAtMs,
                lifeStageBabyScale,
                lifeStageAdolescentScale,
                lifeStageAdolescentSwitchScale,
                lifeStageAdultStartScale,
                lifeStageAdultSwitchScale,
                lifeStageAdultScale,
                lifeStageGrowthScalingEnabled,
                attachmentsConfigId,
                attachmentsValues,
                breedingEnabled,
                levelingConfigId,
                levelingLevel,
                levelingTotalXp,
                talentsConfigId,
                talentsSpentPoints,
                purchasedTalentIds,
                null,
                null,
                lifeStageGender
        );
    }

    @Nullable
    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        NPCPlugin plugin = NPCPlugin.get();
        if (roleIndex >= 0 && plugin != null) {
            String name = plugin.getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    @Nullable
    private String resolveCustomName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType == null) {
            return null;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, nameType);
        if (component == null || component.getName() == null || component.getName().isBlank()) {
            return null;
        }
        return component.getName();
    }

    private String resolveDisplayName(Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      NPCEntity npc,
                                      @Nullable String roleId,
                                      @Nullable String customName) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        String componentName = NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
        if (componentName != null && !componentName.isBlank()) {
            return componentName;
        }
        if (npc != null) {
            String legacy = npc.getLegacyDisplayName();
            if (legacy != null && !legacy.isBlank()) {
                return legacy;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "Lost companion";
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * Snapshot of a linked companion that can no longer be reached by relocation.
     */
    public record LostLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable Vector3d lastKnownPosition,
                                        @Nullable Vector3d homePosition,
                                        long lastRelocationQueuedAtMs,
                                        long lostAtMs,
                                        int relocationRetryAttempts,
                                        @Nullable UUID replacementNpcUuid,
                                        long recoveredAtMs) {
        public boolean isAwaitingRecovery() {
            return replacementNpcUuid == null;
        }
    }
}
