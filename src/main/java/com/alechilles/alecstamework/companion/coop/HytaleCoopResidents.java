package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.HytaleCompanionSpawner;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.SummaryLifeStage;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.CoopResidentReleasePositionService;
import com.alechilles.alecstamework.items.DirectLiveCoopProduceService;
import com.alechilles.alecstamework.items.HytaleDirectLiveCoopScanner;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The world side of coop residents after intake: production and morning release for the
 * schedule system, and release of every resident when a coop block breaks (spec 8.9).
 *
 * <p>Every method runs on the coop's world thread outside a system tick (inside
 * {@code world.execute}); slot writes go straight to the block entity there.
 */
public final class HytaleCoopResidents implements CoopRelease.Port {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String COOP_BLOCK_CLASS = "com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock";

    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final HytaleCompanionSpawner spawner;
    private final CoopRelease release;
    private final DirectLiveCoopProduceService produce = new DirectLiveCoopProduceService();
    private final CoopResidentReleasePositionService positions = new CoopResidentReleasePositionService();

    public HytaleCoopResidents(@Nonnull CompanionIndex index, @Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
                               @Nonnull HytaleCompanionSpawner spawner) {
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.release = new CoopRelease(Objects.requireNonNull(index, "index")::get, this, System::currentTimeMillis);
    }

    /**
     * One sweep of a coop in its roam hours: produce for every resident, then release the first
     * resident that may go now (one per sweep, as in 4.x).
     */
    public void roam(@Nonnull World world, @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop) {
        Vector3i b = coop.block();
        CoopRelease.At at = new CoopRelease.At(world.getName(), b.x, b.y, b.z);
        HytaleCoopIntake.Block block = HytaleCoopIntake.block(world, b.x, b.y, b.z);
        if (block == null || block.slots() == null) {
            return;
        }
        List<DirectLiveCoopProduceService.Resident> residents = new ArrayList<>();
        for (TameworkCoopSlotsComponent.Slot entry : block.slots().slots()) {
            if (release.resident(at, entry)) {
                residents.add(resident(entry));
            }
        }
        if (residents.isEmpty()) {
            return;
        }
        List<TameworkCoopSlotsComponent.Slot> changed = produce.produce(coop, residents,
                BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(world.getEntityStore().getStore()));
        writeEntries(block, changed);
        for (DirectLiveCoopProduceService.Resident resident : residents) {
            if (release.releasableNow(at, resident.entry())) {
                release.release(at, resident.entry(),
                        destination(world, resident.roleId(), b, coop.rotationIndex(), coop.config()));
                return;
            }
        }
    }

    /** Shows the produce-ready block state while the coop holds produce. */
    public void syncInteractionState(@Nonnull World world, @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop) {
        produce.syncInteractionState(world, coop);
    }

    /**
     * Releases every current resident of a broken coop next to where it stood. The entries come
     * from the removed block, so each slot clear finds no block and does nothing; a resident whose
     * release fails is logged (a companion stays COOP for Recover; an unowned one is lost).
     */
    public void releaseAll(@Nonnull World world, int x, int y, int z,
                           @Nonnull List<TameworkCoopSlotsComponent.Slot> entries, @Nullable TwCoopConfig config) {
        CoopRelease.At at = new CoopRelease.At(world.getName(), x, y, z);
        Vector3i block = new Vector3i(x, y, z);
        for (TameworkCoopSlotsComponent.Slot entry : entries) {
            if (!release.resident(at, entry)) {
                continue;
            }
            release.release(at, entry, destination(world, resident(entry).roleId(), block, 0, config))
                    .thenAccept(released -> {
                        if (!released) {
                            LOGGER.at(Level.WARNING).log("Could not release %s from broken coop slot %d at %s",
                                    entry.profileId() == null ? "an unowned resident" : "companion " + entry.profileId(),
                                    entry.slot(), at);
                        }
                    });
        }
    }

    /** Drops old release-failure marks. Called once per sweep. */
    public void pruneFailures() {
        release.pruneFailures();
    }

    @Override
    @Nonnull
    public CompletableFuture<RestoreFlow.Result> restore(@Nonnull RestoreFlow.Request request) {
        return restoreFlow.restore(request);
    }

    @Override
    @Nonnull
    public CompletableFuture<Boolean> spawnUnowned(@Nonnull BsonDocument entity,
                                                   @Nonnull RestoreFlow.Destination destination) {
        return spawner.spawnUnowned(entity, destination);
    }

    @Override
    @Nonnull
    public CompletableFuture<Void> clearSlot(@Nonnull CoopRelease.At at, @Nonnull TameworkCoopSlotsComponent.Slot entry) {
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(at.world());
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (world == null) {
            done.complete(null);
            return done;
        }
        try {
            world.execute(() -> {
                try {
                    HytaleCoopIntake.Block block = HytaleCoopIntake.block(world, at.x(), at.y(), at.z());
                    TameworkCoopSlotsComponent slots = block == null ? null : block.slots();
                    if (slots != null && sameResident(slots.get(entry.slot()), entry)) {
                        block.store().putComponent(block.ref(), TameworkCoopSlotsComponent.getComponentType(),
                                slots.without(entry.slot()));
                        block.info().markNeedsSaving(block.store());
                    }
                } finally {
                    done.complete(null);
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            done.complete(null);
        }
        return done;
    }

    /** Writes changed watermarks for entries that still hold the same resident. */
    private static void writeEntries(HytaleCoopIntake.Block block, List<TameworkCoopSlotsComponent.Slot> changed) {
        TameworkCoopSlotsComponent slots = block.slots();
        for (TameworkCoopSlotsComponent.Slot entry : changed) {
            if (sameResident(slots.get(entry.slot()), entry)) {
                slots = slots.with(entry);
            }
        }
        if (slots != block.slots()) {
            block.store().putComponent(block.ref(), TameworkCoopSlotsComponent.getComponentType(), slots);
            block.info().markNeedsSaving(block.store());
        }
    }

    /** Same slot and resident (profile and generation, or the same unowned entity); the watermark may differ. */
    private static boolean sameResident(@Nullable TameworkCoopSlotsComponent.Slot current,
                                        TameworkCoopSlotsComponent.Slot entry) {
        return current != null && current.slot() == entry.slot()
                && Objects.equals(current.profileId(), entry.profileId())
                && current.generation() == entry.generation()
                && Objects.equals(current.unownedEntity(), entry.unownedEntity());
    }

    private DirectLiveCoopProduceService.Resident resident(TameworkCoopSlotsComponent.Slot entry) {
        BsonDocument entity = entry.unownedEntity();
        if (entity == null) {
            CompanionRecord record = release.record(entry);
            return new DirectLiveCoopProduceService.Resident(entry, record == null ? null : record.roleId(),
                    record == null || record.summary() == null ? null
                            : SummaryLifeStage.of(record.summary().progression()));
        }
        BsonDocument components = document(entity, "Components");
        BsonDocument npc = document(components, "NPC");
        String role = npc != null && npc.isString("RoleName") ? npc.getString("RoleName").getValue() : null;
        return new DirectLiveCoopProduceService.Resident(entry, role, lifeStage(document(components, "TameworkLifeStage")));
    }

    @Nullable
    private static TameworkLifeStageComponent lifeStage(@Nullable BsonDocument stored) {
        if (stored == null) {
            return null;
        }
        try {
            return TameworkLifeStageComponent.CODEC.decode(stored, new ExtraInfo());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    @Nullable
    private static BsonDocument document(@Nullable BsonDocument parent, String key) {
        BsonValue value = parent == null ? null : parent.get(key);
        return value != null && value.isDocument() ? value.asDocument() : null;
    }

    /**
     * Next to the coop: block position plus the coop's release offset, checked for the role's
     * spawn rules when the role is known, else the plain offset position.
     */
    private RestoreFlow.Destination destination(World world, @Nullable String roleId, Vector3i block, int rotation,
                                                @Nullable TwCoopConfig config) {
        TwCoopConfig.SpawnOffsetSettings offset = config == null ? null
                : config.getLifecycleRules().getResidentSpawnOffset();
        double ox = offset == null ? 0.0 : offset.getX();
        double oy = offset == null ? 0.0 : offset.getY();
        double oz = offset == null ? 0.0 : offset.getZ();
        Builder<Role> builder = roleBuilder(roleId);
        Vector3d at = builder == null
                ? new Vector3d(block.x + 0.5 + ox, block.y + oy, block.z + 0.5 + oz)
                : positions.resolveSpawnPosition(world, builder, block, rotation, ox, oy, oz);
        return new RestoreFlow.Destination(world.getName(), at.x, at.y, at.z, 0.0f, 0.0f);
    }

    @Nullable
    private static Builder<Role> roleBuilder(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        try {
            NPCPlugin plugin = NPCPlugin.get();
            int index = plugin == null ? -1 : plugin.getIndex(roleId);
            return index < 0 ? null : plugin.tryGetCachedValidRole(index);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * The coop config of a block entity that is being removed: its block type is already gone, so
     * it comes from the vanilla coop component's asset, when the block has one. Reflection keeps
     * the Farming plugin optional. Null when unknown; the release then uses no offset.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static TwCoopConfig configOfRemovedBlock(@Nonnull ComponentAccessor<ChunkStore> accessor,
                                             @Nonnull Ref<ChunkStore> ref) {
        try {
            Object type = Class.forName(COOP_BLOCK_CLASS).getMethod("getComponentType").invoke(null);
            if (!(type instanceof ComponentType<?, ?> coopType)) {
                return null;
            }
            Object coop = accessor.getComponent(ref, (ComponentType<ChunkStore, Component<ChunkStore>>) (ComponentType) coopType);
            Object asset = coop == null ? null : coop.getClass().getMethod("getCoopAsset").invoke(coop);
            Object id = asset == null ? null : asset.getClass().getMethod("getId").invoke(asset);
            if (!(id instanceof String raw) || raw.isBlank()) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            TwCoopConfig config = TwCoopConfig.resolveForCoop(normalized);
            return config != null ? config : TwCoopConfig.resolveForBlockType(normalized);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
}
