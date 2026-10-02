package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coop residents imported from 3.x or 4.x (plan 7 R20). The importer writes each one as a COOP
 * record at generation 0, but an old coop block has no {@link TameworkCoopSlotsComponent}: 4.x
 * kept residency in its database. This puts those records back into their block's slots the first
 * time the block is seen loaded, and moves out the ones whose coop is gone or full.
 *
 * <p>An imported resident is a record that is COOP at generation 0. A 5.0 intake always commits
 * generation+1, so no resident taken in by 5.0 matches, and an intake that has committed but not
 * yet written its slot is never mistaken for an import.
 *
 * <p>The sites are collected from the index once, on first use (the index is loaded before worlds
 * start), and each site is dropped once handled, so a server with nothing left to place pays one
 * empty-map check per sweep. Records are re-read from the index when a site is handled, so one
 * that moved on meanwhile (a Recover, say) is skipped. Handling a site again after a restart finds
 * its entries already on the block and changes nothing.
 *
 * <p>Thread use: a site is touched only on its world's thread; different worlds use different
 * sites of the same concurrent map.
 */
public final class CoopImportedResidents {
    /** LOST cause of an imported resident whose coop is gone or full and that could not be released beside it. */
    public static final String CAUSE_COOP_MISSING = "IMPORTED_COOP_MISSING";

    /** A coop block. The world name is lower case: 4.x stored normalized world keys. */
    public record Site(@Nonnull String world, int x, int y, int z) {
        @Nonnull
        public static Site of(@Nonnull String world, int x, int y, int z) {
            return new Site(world.toLowerCase(Locale.ROOT), x, y, z);
        }
    }

    /**
     * The outcome of filling one coop: {@code slots} is the component to put on the block, null
     * when nothing changed; {@code overflow} holds the imported residents that found no free slot.
     */
    public record Fill(@Nullable TameworkCoopSlotsComponent slots, @Nonnull List<CompanionRecord> overflow) {
    }

    private static final class Waiting {
        private final Set<UUID> profiles = ConcurrentHashMap.newKeySet();
        private int sweepsWithoutCoop;
    }

    private final CompanionIndex index;
    @Nullable private volatile Map<Site, Waiting> waiting;

    public CoopImportedResidents(@Nonnull CompanionIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    /** True while {@code world} has an imported resident that is not placed or moved out yet. */
    public boolean pendingIn(@Nonnull String world) {
        Map<Site, Waiting> sites = sites();
        if (sites.isEmpty()) {
            return false;
        }
        String key = world.toLowerCase(Locale.ROOT);
        for (Site site : sites.keySet()) {
            if (site.world().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** True while the coop block at this position has imported residents to place. */
    public boolean pendingAt(@Nonnull String world, int x, int y, int z) {
        Map<Site, Waiting> sites = sites();
        return !sites.isEmpty() && sites.containsKey(Site.of(world, x, y, z));
    }

    /** The sites in {@code world} that still wait for their coop block. */
    @Nonnull
    public List<Site> sitesIn(@Nonnull String world) {
        String key = world.toLowerCase(Locale.ROOT);
        List<Site> out = new ArrayList<>();
        for (Site site : sites().keySet()) {
            if (site.world().equals(key)) {
                out.add(site);
            }
        }
        return out;
    }

    /**
     * Counts one sweep in which {@code site}'s chunk was loaded and held no coop; true once that
     * happened {@code sweeps} times in a row, so a block still loading is not taken for a missing one.
     */
    public boolean seenWithoutCoop(@Nonnull Site site, int sweeps) {
        Waiting entry = sites().get(site);
        return entry != null && ++entry.sweepsWithoutCoop >= sweeps;
    }

    /** Starts the count of {@link #seenWithoutCoop} over: the site's chunk is not loaded. */
    public void notLoaded(@Nonnull Site site) {
        Waiting entry = sites().get(site);
        if (entry != null) {
            entry.sweepsWithoutCoop = 0;
        }
    }

    /**
     * Places the imported residents of the coop at this position into {@code current} and marks
     * the site handled. Each goes into its old slot when that is free and below
     * {@code maxResidents}, else the lowest free slot; its record is moved to that slot (and to the
     * world's exact name) so the entry counts as occupied. A resident whose entry is already on the
     * block is left alone. The production watermark of a new entry is {@code gameTimeMs}, as an
     * intake stamps it, so nothing is produced for the time before the fill.
     *
     * @param world   the coop world's exact name
     * @param current the block's component, null when it has none
     */
    @Nonnull
    public Fill fill(@Nonnull String world, int x, int y, int z, @Nullable TameworkCoopSlotsComponent current,
                     int maxResidents, long gameTimeMs) {
        Site site = Site.of(world, x, y, z);
        TameworkCoopSlotsComponent slots = current == null ? new TameworkCoopSlotsComponent() : current;
        List<CompanionRecord> overflow = new ArrayList<>();
        boolean changed = false;
        for (CompanionRecord record : take(site)) {
            if (held(slots, record, world, x, y, z)) {
                continue;
            }
            int slot = record.location().slot();
            if (slot >= maxResidents || CoopSlots.taken(slots.slots(), slot, index::get, world, x, y, z)) {
                slot = CoopSlots.firstFree(slots.slots(), maxResidents, index::get, world, x, y, z);
            }
            if (slot < 0) {
                overflow.add(record);
                continue;
            }
            CompanionRecord placed = moveTo(record, CompanionLocation.coop(world, x, y, z, slot));
            if (placed != null) {
                slots = slots.with(new TameworkCoopSlotsComponent.Slot(slot, placed.profileId(), placed.generation(),
                        null, gameTimeMs));
                changed = true;
            }
        }
        return new Fill(changed ? slots : null, overflow);
    }

    /**
     * The imported residents of a site whose coop block is gone, marking the site handled. The
     * caller moves each out with {@link #moveOut}.
     */
    @Nonnull
    public List<CompanionRecord> withoutCoop(@Nonnull Site site) {
        return take(site);
    }

    /**
     * Moves an imported resident out of a coop that is gone or full, as a broken coop releases its
     * residents: {@code restore} gets a COOP_RELEASE at the record's generation. When that does not
     * restore it, the record becomes LOST with {@link #CAUSE_COOP_MISSING}; its generation and
     * snapshot are kept, so its owner can recover it. Completes true when released into the world,
     * false when LOST or when the record had moved on by itself. Never completes exceptionally.
     */
    @Nonnull
    public CompletableFuture<Boolean> moveOut(
            @Nonnull CompanionRecord record, @Nonnull RestoreFlow.Destination destination,
            @Nonnull Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Result>> restore) {
        CompletableFuture<RestoreFlow.Result> restored;
        try {
            restored = restore.apply(RestoreFlow.Request.of(record.profileId(), RestoreRules.Reason.COOP_RELEASE,
                    destination).withGeneration(record.generation()));
        } catch (RuntimeException failure) {
            restored = CompletableFuture.failedFuture(failure);
        }
        return restored.handle((result, failure) -> {
            if (failure == null && result == RestoreFlow.Result.RESTORED) {
                return true;
            }
            CompanionRecord now = index.get(record.profileId());
            if (now != null && now.location().kind() == LocationKind.COOP && now.generation() == record.generation()) {
                moveTo(now, CompanionLocation.lost(CAUSE_COOP_MISSING));
            }
            return false;
        });
    }

    /** Removes the site and returns its records that are still imported residents of it, by old slot. */
    private List<CompanionRecord> take(Site site) {
        Waiting entry = sites().remove(site);
        if (entry == null) {
            return List.of();
        }
        List<CompanionRecord> out = new ArrayList<>();
        for (UUID profileId : entry.profiles) {
            CompanionRecord record = index.get(profileId);
            if (record != null && site.equals(siteOf(record))) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparingInt((CompanionRecord r) -> r.location().slot())
                .thenComparing(CompanionRecord::profileId));
        return out;
    }

    /** The record at {@code location}, or null when it changed meanwhile; unchanged when already there. */
    @Nullable
    private CompanionRecord moveTo(CompanionRecord record, CompanionLocation location) {
        if (record.location().equals(location)) {
            return record;
        }
        return index.update(record.profileId(), record.revision(), b -> b.location(location)).after();
    }

    /** True when the block already has this resident's current entry. */
    private static boolean held(TameworkCoopSlotsComponent slots, CompanionRecord record, String world,
                                int x, int y, int z) {
        for (TameworkCoopSlotsComponent.Slot entry : slots.slots()) {
            if (record.profileId().equals(entry.profileId()) && CoopSlots.occupied(entry, record, world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** The coop site of an imported resident, null for any other record. */
    @Nullable
    private static Site siteOf(CompanionRecord record) {
        CompanionLocation at = record.location();
        if (at.kind() != LocationKind.COOP || record.generation() != 0L || at.world() == null) {
            return null;
        }
        return Site.of(at.world(), (int) at.x(), (int) at.y(), (int) at.z());
    }

    private Map<Site, Waiting> sites() {
        Map<Site, Waiting> sites = waiting;
        if (sites == null) {
            synchronized (this) {
                sites = waiting;
                if (sites == null) {
                    Map<Site, Waiting> built = new ConcurrentHashMap<>();
                    index.forEach(record -> {
                        Site site = siteOf(record);
                        if (site != null) {
                            built.computeIfAbsent(site, key -> new Waiting()).profiles.add(record.profileId());
                        }
                    });
                    waiting = sites = built;
                }
            }
        }
        return sites;
    }
}
