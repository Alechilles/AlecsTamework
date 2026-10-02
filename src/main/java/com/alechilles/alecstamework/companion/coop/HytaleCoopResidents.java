package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.CompanionWorldTime;
import com.alechilles.alecstamework.companion.flow.HytaleCompanionSpawner;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.CoopResidentReleasePositionService;
import com.alechilles.alecstamework.items.DirectLiveCoopProduceService;
import com.alechilles.alecstamework.items.HytaleDirectLiveCoopScanner;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Sweeps (one per second) a loaded chunk must show no coop before its imported residents are moved out. */
    private static final int SWEEPS_WITHOUT_COOP = 5;

    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final HytaleCompanionSpawner spawner;
    private final CoopRelease release;
    private final CoopImportedResidents imports;
    private final DirectLiveCoopProduceService produce = new DirectLiveCoopProduceService();
    private final CoopResidentReleasePositionService positions = new CoopResidentReleasePositionService();
    private final HytaleDirectLiveCoopScanner scanner = new HytaleDirectLiveCoopScanner();
    /** Slots whose morning release failure was logged; cleared when that slot releases. */
    private final Set<String> failureLogged = ConcurrentHashMap.newKeySet();
    /** Unowned residents whose morning spawn was running when their coop broke (the break skipped them). */
    private final Set<String> brokenWhileInFlight = ConcurrentHashMap.newKeySet();

    public HytaleCoopResidents(@Nonnull CompanionIndex index, @Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
                               @Nonnull HytaleCompanionSpawner spawner) {
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        Objects.requireNonNull(index, "index");
        this.release = new CoopRelease(index::get, this, System::currentTimeMillis);
        this.imports = new CoopImportedResidents(index);
    }

    /** True while {@code world} has residents imported from 3.x or 4.x that are not in a coop block yet (plan 7 R20). */
    public boolean importsPendingIn(@Nonnull World world) {
        return imports.pendingIn(world.getName());
    }

    /** True while this coop has imported residents to place; the sweep then fills it before any intake. */
    public boolean importsPendingAt(@Nonnull World world, @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop) {
        Vector3i b = coop.block();
        return imports.pendingAt(world.getName(), b.x, b.y, b.z);
    }

    /**
     * Writes the imported residents of a loaded coop into its block, once (plan 7 R20). An old
     * block has no slots component, so the first write adds it. Residents that find no free slot
     * leave as from a broken coop ({@link #moveOutImported}). A block that cannot be read keeps
     * its residents waiting for the next sweep.
     */
    public void ensureImportedResidents(@Nonnull World world, @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop) {
        Vector3i b = coop.block();
        TwCoopConfig config = coop.config();
        if (config == null || !imports.pendingAt(world.getName(), b.x, b.y, b.z)) {
            return;
        }
        HytaleCoopIntake.Block block = HytaleCoopIntake.block(world, b.x, b.y, b.z);
        if (block == null || TameworkCoopSlotsComponent.getComponentType() == null) {
            return;
        }
        CoopImportedResidents.Fill fill = imports.fill(world.getName(), b.x, b.y, b.z, block.slots(),
                config.getLifecycleRules().getMaxResidents(),
                CompanionWorldTime.gameTimeMs(world.getEntityStore().getStore()));
        if (fill.slots() != null) {
            block.store().putComponent(block.ref(), TameworkCoopSlotsComponent.getComponentType(), fill.slots());
            block.info().markNeedsSaving(block.store());
            LOGGER.at(Level.INFO).log("Imported coop residents rejoined the coop at %s in world %s (%d slot entries)",
                    b, world.getName(), fill.slots().slots().size());
        }
        for (CompanionRecord record : fill.overflow()) {
            moveOutImported(world, record, b, coop.rotationIndex(), config, "the coop is full");
        }
    }

    /**
     * The sites in {@code world} whose chunk has been loaded without a managed coop block for
     * {@link #SWEEPS_WITHOUT_COOP} sweeps in a row and whose position holds no coop block of any
     * kind (air or another block): the coop was broken while the server ran 4.x. While a coop block
     * still stands there without an enabled config (its content pack did not load, say) the
     * residents wait and one INFO line per site and server run says so. Read-only apart from that
     * bookkeeping.
     */
    @Nonnull
    public List<CoopImportedResidents.Site> importSitesWithoutCoop(
            @Nonnull World world, @Nonnull List<HytaleDirectLiveCoopScanner.LoadedCoop> coops) {
        List<CoopImportedResidents.Site> gone = new ArrayList<>();
        for (CoopImportedResidents.Site site : imports.sitesIn(world.getName())) {
            if (hasCoop(coops, site)) {
                continue;
            }
            if (!chunkLoaded(world, site)) {
                imports.notLoaded(site);
            } else if (coopBlockStands(world, site)) {
                if (imports.waitsForCoop(site)) {
                    LOGGER.at(Level.INFO).log("Imported coop residents wait for the coop at %d, %d, %d in world %s:"
                            + " its block is there but has no enabled coop config (is its content pack loaded?)",
                            site.x(), site.y(), site.z(), world.getName());
                }
            } else if (imports.seenWithoutCoop(site, SWEEPS_WITHOUT_COOP)) {
                gone.add(site);
            }
        }
        return gone;
    }

    /** Moves the imported residents of a site with no coop block out next to where the coop stood. */
    public void releaseImportedWithoutCoop(@Nonnull World world, @Nonnull CoopImportedResidents.Site site) {
        if (!chunkLoaded(world, site) || coopBlockStands(world, site)) {
            return;
        }
        Vector3i block = new Vector3i(site.x(), site.y(), site.z());
        for (CompanionRecord record : imports.withoutCoop(site)) {
            moveOutImported(world, record, block, 0, null, "its coop is gone");
        }
    }

    /** A coop block of any kind, or one that cannot be told apart, at the site; an unreadable chunk store counts too. */
    private boolean coopBlockStands(World world, CoopImportedResidents.Site site) {
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> store = chunkStore == null ? null : chunkStore.getStore();
        return store == null || scanner.coopBlockOfAnyKindAt(world, store, site.x(), site.y(), site.z());
    }

    private static boolean chunkLoaded(World world, CoopImportedResidents.Site site) {
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(site.x(), site.z()));
        return chunk != null && HytaleChunkAccess.isOwnedBy(chunk, world);
    }

    private static boolean hasCoop(List<HytaleDirectLiveCoopScanner.LoadedCoop> coops, CoopImportedResidents.Site site) {
        for (HytaleDirectLiveCoopScanner.LoadedCoop coop : coops) {
            Vector3i b = coop.block();
            if (b.x == site.x() && b.y == site.y() && b.z == site.z()) {
                return true;
            }
        }
        return false;
    }

    /** Releases an imported resident beside the coop position, as a broken coop does; LOST when that fails. */
    private void moveOutImported(World world, CompanionRecord record, Vector3i block, int rotation,
                                 @Nullable TwCoopConfig config, String why) {
        imports.moveOut(record, destination(world, record.roleId(), block, rotation, config), restoreFlow::restore)
                .thenAccept(released -> LOGGER.at(Level.INFO).log(
                        "Imported coop resident %s (%s) of the coop at %s in world %s: %s; %s", record.profileId(),
                        record.roleId(), block, world.getName(), why, released ? "released beside it"
                                : "not released (if it is now LOST its owner can recover it)"));
    }

    /**
     * One sweep of a coop in its roam hours: produce for every resident on the world's game time
     * (once per roam window each), save changed watermarks on the block, then release the first resident that may go now (one per sweep, as in 4.x).
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
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<TameworkCoopSlotsComponent.Slot> changed = produce.produce(coop, residents,
                CompanionWorldTime.gameTimeMs(store));
        writeEntries(block, changed);
        for (DirectLiveCoopProduceService.Resident resident : residents) {
            TameworkCoopSlotsComponent.Slot entry = resident.entry();
            if (release.releasableNow(at, entry)) {
                release.release(at, entry, destination(world, resident.roleId(), b, coop.rotationIndex(), coop.config()))
                        .thenAccept(outcome -> morningOutcome(at, entry, resident.roleId(), outcome));
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
     * release fails is logged (a companion stays COOP for Recover; an unowned one is lost). An
     * unowned resident whose morning spawn is still running is left to that spawn.
     */
    public void releaseAll(@Nonnull World world, int x, int y, int z,
                           @Nonnull List<TameworkCoopSlotsComponent.Slot> entries, @Nullable TwCoopConfig config) {
        CoopRelease.At at = new CoopRelease.At(world.getName(), x, y, z);
        Vector3i block = new Vector3i(x, y, z);
        for (TameworkCoopSlotsComponent.Slot entry : entries) {
            if (!release.resident(at, entry)) {
                if (entry.unownedEntity() != null) {
                    // Always occupied, so it is in flight: its spawn outcome decides whether it is lost.
                    brokenWhileInFlight.add(CoopRelease.key(at, entry.slot()));
                }
                continue;
            }
            String role = resident(entry).roleId();
            release.release(at, entry, destination(world, role, block, 0, config))
                    .thenAccept(outcome -> {
                        if (outcome.released()) {
                            return;
                        }
                        if (entry.profileId() == null) {
                            LOGGER.at(Level.WARNING).log("Lost an unowned %s resident from broken coop slot %d at %s:"
                                    + " it could not be spawned", role, entry.slot(), at);
                        } else {
                            LOGGER.at(Level.WARNING).log("Could not release companion %s (%s) from broken coop slot %d"
                                    + " at %s (%s); it stays in the coop for Recover", entry.profileId(), role,
                                    entry.slot(), at, outcome.cause());
                        }
                    });
        }
    }

    /** The coop config of a block entity being removed, resolved as the sweep resolves it; null when unknown. */
    @Nullable
    public TwCoopConfig configOf(@Nonnull ComponentAccessor<ChunkStore> accessor, @Nonnull Ref<ChunkStore> block) {
        return scanner.configOf(accessor, block);
    }

    /**
     * Logs the first failed morning release of a slot (INFO, once until it releases), and WARNs
     * when an unowned resident's spawn failed after its coop broke, since its only copy is gone.
     */
    private void morningOutcome(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry, @Nullable String role,
                                CoopRelease.Outcome outcome) {
        String key = CoopRelease.key(at, entry.slot());
        boolean broken = entry.unownedEntity() != null && brokenWhileInFlight.remove(key);
        if (outcome.released()) {
            failureLogged.remove(key);
            return;
        }
        if (broken) {
            LOGGER.at(Level.WARNING).log("Lost an unowned %s resident of coop slot %d at %s: its coop broke during"
                    + " its release and it could not be spawned", role, entry.slot(), at);
            return;
        }
        if (!"BUSY".equals(outcome.cause()) && failureLogged.add(key)) {
            LOGGER.at(Level.INFO).log("Coop slot %d at %s could not release its %s resident (%s); retrying every %d s",
                    entry.slot(), at, role, outcome.cause(), CoopRelease.RETRY_DELAY_MS / 1_000L);
        }
    }

    @Override
    @Nonnull
    public CompletableFuture<RestoreFlow.Result> restore(@Nonnull RestoreFlow.Request request) {
        return restoreFlow.restore(request);
    }

    /** The spawn runs on the coop's world (the destination is next to it), so the clear runs in that task. */
    @Override
    @Nonnull
    public CompletableFuture<Boolean> spawnUnowned(@Nonnull CoopRelease.At at, @Nonnull TameworkCoopSlotsComponent.Slot entry,
                                                   @Nonnull RestoreFlow.Destination destination) {
        return spawner.spawnUnowned(Objects.requireNonNull(entry.unownedEntity(), "unownedEntity"), destination, () -> {
            World world = world(at);
            if (world != null) {
                clear(world, at, entry);
            }
        });
    }

    @Override
    @Nonnull
    public CompletableFuture<Void> clearSlot(@Nonnull CoopRelease.At at, @Nonnull TameworkCoopSlotsComponent.Slot entry) {
        World world = world(at);
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (world == null) {
            done.complete(null);
            return done;
        }
        try {
            world.execute(() -> {
                try {
                    clear(world, at, entry);
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

    @Nullable
    private static World world(CoopRelease.At at) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getWorld(at.world());
    }

    /** Removes {@code entry} when the slot still holds that resident. Call on the coop's world thread. */
    private static void clear(World world, CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry) {
        HytaleCoopIntake.Block block = HytaleCoopIntake.block(world, at.x(), at.y(), at.z());
        TameworkCoopSlotsComponent slots = block == null ? null : block.slots();
        if (slots != null && sameResident(slots.get(entry.slot()), entry)) {
            block.store().putComponent(block.ref(), TameworkCoopSlotsComponent.getComponentType(),
                    slots.without(entry.slot()));
            block.info().markNeedsSaving(block.store());
        }
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
            return new DirectLiveCoopProduceService.Resident(entry, record == null ? null : record.roleId());
        }
        BsonDocument components = document(entity, "Components");
        BsonDocument npc = document(components, "NPC");
        String role = npc != null && npc.isString("RoleName") ? npc.getString("RoleName").getValue() : null;
        return new DirectLiveCoopProduceService.Resident(entry, role);
    }

    @Nullable
    private static BsonDocument document(@Nullable BsonDocument parent, String key) {
        BsonValue value = parent == null ? null : parent.get(key);
        return value != null && value.isDocument() ? value.asDocument() : null;
    }

    /**
     * Next to the coop: block position plus the coop's release offset turned by the block's
     * rotation, checked for the role's spawn rules when the role is known, else that plain
     * position.
     */
    private RestoreFlow.Destination destination(World world, @Nullable String roleId, Vector3i block, int rotation,
                                                @Nullable TwCoopConfig config) {
        TwCoopConfig.SpawnOffsetSettings offset = config == null ? null
                : config.getLifecycleRules().getResidentSpawnOffset();
        double ox = offset == null ? 0.0 : offset.getX();
        double oy = offset == null ? 0.0 : offset.getY();
        double oz = offset == null ? 0.0 : offset.getZ();
        Builder<Role> builder = roleBuilder(roleId);
        Vector3d at;
        if (builder == null) {
            Vector3d turned = positions.rotateHorizontalOffset(rotation, ox, oy, oz);
            at = new Vector3d(block.x + 0.5 + turned.x, block.y + turned.y, block.z + 0.5 + turned.z);
        } else {
            at = positions.resolveSpawnPosition(world, builder, block, rotation, ox, oy, oz);
        }
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
}
