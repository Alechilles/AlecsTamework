package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

/**
 * Releases every resident of a coop block that is broken (spec 8.9), as vanilla does; vanilla
 * drops the produce. Spike B confirmed a ChunkStore {@code RefSystem} sees a break as
 * {@code REMOVE} with the block position still readable. A chunk unload ({@code UNLOAD}) keeps
 * the residents in the saved block.
 *
 * <p>The query is the Tamework slots component, so this needs no Farming type at setup. The
 * block entity is going away, so the entries and position are read here and the releases run in
 * a {@code world.execute} task after this callback.
 */
public final class CoopBreakSystem extends RefSystem<ChunkStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final HytaleCoopResidents residents;
    private final ComponentType<ChunkStore, TameworkCoopSlotsComponent> slotsType;

    public CoopBreakSystem(@Nonnull HytaleCoopResidents residents,
                           @Nonnull ComponentType<ChunkStore, TameworkCoopSlotsComponent> slotsType) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.slotsType = Objects.requireNonNull(slotsType, "slotsType");
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return slotsType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store,
                              @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (reason == RemoveReason.UNLOAD) {
            return;
        }
        TameworkCoopSlotsComponent slots = commandBuffer.getComponent(ref, slotsType);
        if (slots == null || slots.slots().isEmpty()) {
            return;
        }
        List<TameworkCoopSlotsComponent.Slot> entries = slots.slots();
        ComponentType<ChunkStore, BlockModule.BlockStateInfo> infoType = BlockModule.BlockStateInfo.getComponentType();
        BlockModule.BlockStateInfo info = infoType == null ? null : commandBuffer.getComponent(ref, infoType);
        Vector3i at = new Vector3i();
        if (info == null || !info.fillWorldPos(commandBuffer, at)) {
            LOGGER.at(Level.WARNING).log("A removed coop block with %d slot entries had no position; residents not released",
                    entries.size());
            return;
        }
        World world = commandBuffer.getExternalData().getWorld();
        TwCoopConfig config = residents.configOf(commandBuffer, ref);
        try {
            world.execute(() -> residents.releaseAll(world, at.x, at.y, at.z, entries, config));
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks (it is shutting down).
            LOGGER.at(Level.WARNING).withCause(notAccepting).log(
                    "Could not queue the release of residents from the broken coop at %s", at);
        }
    }
}
