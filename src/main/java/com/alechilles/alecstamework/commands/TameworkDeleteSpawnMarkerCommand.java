package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.math.TameworkRotationUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.hypixel.hytale.server.spawning.blockstates.SpawnMarkerBlock;
import com.hypixel.hytale.server.spawning.blockstates.SpawnMarkerBlockReference;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Deletes the closest loaded spawn marker entity in the player's view ray.
 */
public final class TameworkDeleteSpawnMarkerCommand extends AbstractPlayerCommand {
    private static final int MAX_NPC_SUMMARY_ROWS = 4;

    public TameworkDeleteSpawnMarkerCommand() {
        super("delete", "Delete the loaded spawn marker you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkDeleteSpawnMarkerCommandSupport.ParseResult parse =
                TameworkDeleteSpawnMarkerCommandSupport.parse(commandContext.getInputString());
        if (parse.mode() == TameworkDeleteSpawnMarkerCommandSupport.Mode.INVALID) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw deletespawnmarker [range]"));
            return;
        }

        TransformComponent playerTransform = store.getComponent(ref, TransformComponent.getComponentType());
        if (playerTransform == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve your position."));
            return;
        }

        Vector3d playerPosition = new Vector3d(playerTransform.getPosition());
        Vector3d forward = resolveForward(store, ref, playerTransform);
        TargetMarker target = findTargetMarker(store, playerPosition, forward, parse.range());
        if (target == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "No loaded spawn marker found in front of you within "
                            + formatNumber(parse.range())
                            + " blocks."
            ));
            return;
        }

        if (target.ref == null || !target.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("Target spawn marker is no longer valid."));
            return;
        }

        store.removeEntity(target.ref, RemoveReason.REMOVE);
        boolean removedSourceBlockComponent = removeSourceBlockMarkerComponent(world, target.blockPosition);
        commandContext.sender().sendMessage(Message.raw(
                "Deleted loaded spawn marker "
                        + target.markerId
                        + " at "
                        + formatPosition(target.position)
                        + ", npc="
                        + target.npcSummary
                        + ", distance="
                        + formatNumber(target.score.forwardDistance())
                        + target.blockBackedSuffix(removedSourceBlockComponent)
                        + "."
        ));
    }

    @Nullable
    private static TargetMarker findTargetMarker(@Nonnull Store<EntityStore> store,
                                                 @Nonnull Vector3d playerPosition,
                                                 @Nonnull Vector3d forward,
                                                 double range) {
        List<TargetMarker> candidates = new ArrayList<>();
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                SpawnMarkerEntity marker = chunk.getComponent(i, SpawnMarkerEntity.getComponentType());
                if (marker == null) {
                    continue;
                }
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) {
                    continue;
                }

                Vector3d markerPosition = new Vector3d(transform.getPosition());
                TameworkDeleteSpawnMarkerCommandSupport.TargetScore score =
                        TameworkDeleteSpawnMarkerCommandSupport.scoreCandidate(
                                playerPosition,
                                forward,
                                markerPosition,
                                range
                        );
                if (score == null || !score.matches()) {
                    continue;
                }

                SpawnMarker spawnMarker = marker.getCachedMarker();
                if (spawnMarker == null) {
                    spawnMarker = resolveSpawnMarker(marker.getSpawnMarkerId());
                }
                SpawnMarkerBlockReference blockReference =
                        chunk.getComponent(i, SpawnMarkerBlockReference.getComponentType());
                candidates.add(new TargetMarker(
                        chunk.getReferenceTo(i),
                        marker.getSpawnMarkerId(),
                        describeNpcOptions(spawnMarker),
                        markerPosition,
                        score,
                        blockReference != null ? blockReference.getBlockPosition() : null
                ));
            }
        });

        TargetMarker best = null;
        for (TargetMarker candidate : candidates) {
            if (best == null || candidate.score.isBetterThan(best.score)) {
                best = candidate;
            }
        }
        return best;
    }

    @Nonnull
    private static Vector3d resolveForward(@Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> playerRef,
                                           @Nonnull TransformComponent playerTransform) {
        Rotation3f rotation = TameworkRotationUtil.copyOrDefault(playerTransform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        Rotation3f headRot = headRotation != null ? headRotation.getRotation() : rotation;

        return TameworkRotationUtil.directionFrom(headRot).normalize();
    }

    private static String describeNpcOptions(SpawnMarker spawnMarker) {
        if (spawnMarker == null || spawnMarker.getWeightedConfigurations() == null) {
            return "unknown";
        }

        List<String> npcIds = new ArrayList<>();
        spawnMarker.getWeightedConfigurations().forEach(configuration -> {
            if (configuration != null && configuration.getNpc() != null && !configuration.getNpc().isBlank()) {
                npcIds.add(configuration.getNpc());
            }
        });
        return TameworkShowSpawnMarkersCommandSupport.formatNpcSummary(npcIds, MAX_NPC_SUMMARY_ROWS);
    }

    private static SpawnMarker resolveSpawnMarker(String markerId) {
        if (markerId == null || markerId.isBlank()) {
            return null;
        }
        try {
            return SpawnMarker.getAssetMap().getAsset(markerId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean removeSourceBlockMarkerComponent(@Nonnull World world, @Nullable Vector3i blockPosition) {
        if (blockPosition == null || world.getChunkStore() == null) {
            return false;
        }

        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z));
        if (chunk == null) {
            return false;
        }
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(blockPosition.x, blockPosition.y, blockPosition.z);
        if (blockRef == null || !blockRef.isValid()) {
            return false;
        }

        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        boolean removed = chunkStore.removeComponentIfExists(blockRef, SpawnMarkerBlock.getComponentType());
        if (removed) {
            BlockModule.BlockStateInfo blockStateInfo =
                    chunkStore.getComponent(blockRef, BlockModule.BlockStateInfo.getComponentType());
            if (blockStateInfo != null) {
                blockStateInfo.markNeedsSaving(chunkStore);
            }
        }
        return removed;
    }

    private static String formatPosition(@Nonnull Vector3d position) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", position.x, position.y, position.z);
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record TargetMarker(Ref<EntityStore> ref,
                                String markerId,
                                String npcSummary,
                                Vector3d position,
                                TameworkDeleteSpawnMarkerCommandSupport.TargetScore score,
                                Vector3i blockPosition) {
        String blockBackedSuffix(boolean removedSourceBlockComponent) {
            if (blockPosition == null) {
                return "";
            }
            return ", sourceBlock=("
                    + blockPosition.x
                    + ", "
                    + blockPosition.y
                    + ", "
                    + blockPosition.z
                    + "), sourceBlockMarkerRemoved="
                    + removedSourceBlockComponent;
        }
    }
}
