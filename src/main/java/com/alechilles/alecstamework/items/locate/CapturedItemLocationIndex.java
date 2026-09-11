package com.alechilles.alecstamework.items.locate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Bounded, advisory sightings of captured items. This index never establishes
 * companion ownership or containment; it only describes the latest item source
 * that Tamework observed.
 */
public final class CapturedItemLocationIndex {
    private static final int MAX_SIGHTINGS = 8_192;
    private static final UUID NO_ALIAS = new UUID(0, 0);
    private static final Comparator<CaptureKey> CAPTURE_ORDER = Comparator
            .comparing(CaptureKey::profileId,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(CaptureKey::snapshotId,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(key -> key.profileId() == null ? key.npcUuid() : NO_ALIAS);
    private static final Comparator<Sighting> SIGHTING_ORDER = Comparator
            .comparingLong(Sighting::observedAtMs)
            .thenComparing(Sighting::capture, CAPTURE_ORDER);

    private final Map<CaptureKey, Sighting> sightings = new HashMap<>();
    private final Map<SourceKey, Set<CaptureKey>> capturesBySource =
            new HashMap<>();
    private final TreeSet<Sighting> evictionOrder = new TreeSet<>(SIGHTING_ORDER);
    private long revision;

    /** Records the complete captured-item membership seen in one loaded source. */
    public synchronized void observe(
            Holder holder,
            Collection<CaptureKey> captures,
            long now
    ) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(captures, "captures");
        SourceKey source = SourceKey.of(holder);
        Set<CaptureKey> observed = validatedCaptures(captures);
        Set<CaptureKey> previous = capturesBySource.get(source);
        boolean changed = false;

        if (previous != null) {
            for (CaptureKey capture : Set.copyOf(previous)) {
                if (!observed.contains(capture)) {
                    Sighting current = sightings.get(capture);
                    if (current != null
                            && source.equals(SourceKey.of(current.holder()))) {
                        removeSighting(capture);
                        changed = true;
                    }
                }
            }
        }

        if (observed.isEmpty()) {
            if (capturesBySource.remove(source) != null) {
                changed = true;
            }
        } else {
            capturesBySource.put(source, new HashSet<>(observed));
            if (!observed.equals(previous)) {
                changed = true;
            }
        }

        for (CaptureKey capture : observed) {
            Sighting previousSighting = sightings.get(capture);
            if (previousSighting != null) {
                SourceKey previousSource = SourceKey.of(
                        previousSighting.holder()
                );
                if (!source.equals(previousSource)) {
                    removeMembership(previousSource, capture);
                    changed = true;
                }
            }
            Sighting next = new Sighting(capture, holder, now, true);
            if (!next.equals(previousSighting)) {
                replaceSighting(next);
                changed = true;
            }
        }

        changed |= trimToCapacity();
        if (changed) {
            revision++;
        }
    }

    /** Keeps sightings from an unloaded source as explicitly stale last-known data. */
    public synchronized void unload(Holder holder) {
        Objects.requireNonNull(holder, "holder");
        SourceKey source = SourceKey.of(holder);
        Set<CaptureKey> captures = capturesBySource.get(source);
        if (captures == null || captures.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (CaptureKey capture : captures) {
            Sighting current = sightings.get(capture);
            if (current != null && source.equals(SourceKey.of(current.holder()))
                    && current.loaded()) {
                replaceSighting(new Sighting(
                        capture, current.holder(), current.observedAtMs(), false
                ));
                changed = true;
            }
        }
        if (changed) {
            revision++;
        }
    }

    public synchronized Optional<Sighting> find(CaptureKey capture) {
        return Optional.ofNullable(sightings.get(
                Objects.requireNonNull(capture, "capture")
        ));
    }

    /** Returns a stable copy for diagnostics or persistence. */
    public synchronized List<Sighting> snapshot() {
        return List.copyOf(sightings.values());
    }

    /**
     * Replaces this advisory index with persisted sightings. Loaded state is
     * intentionally discarded because a restart cannot verify it.
     */
    public synchronized void restore(Collection<Sighting> restored) {
        Objects.requireNonNull(restored, "restored");
        Map<CaptureKey, Sighting> before = new HashMap<>(sightings);
        sightings.clear();
        capturesBySource.clear();
        evictionOrder.clear();

        for (Sighting sighting : restored) {
            Objects.requireNonNull(sighting, "sighting");
            Sighting stale = new Sighting(
                    sighting.capture(), sighting.holder(),
                    sighting.observedAtMs(), false
            );
            Sighting replaced = sightings.get(stale.capture());
            if (replaced != null) {
                removeMembership(SourceKey.of(replaced.holder()),
                        replaced.capture());
            }
            replaceSighting(stale);
            capturesBySource.computeIfAbsent(SourceKey.of(stale.holder()),
                    ignored -> new HashSet<>()).add(stale.capture());
        }
        trimToCapacity();
        if (!before.equals(sightings)) {
            revision++;
        }
    }

    public synchronized void clear() {
        if (sightings.isEmpty() && capturesBySource.isEmpty()) {
            return;
        }
        sightings.clear();
        capturesBySource.clear();
        evictionOrder.clear();
        revision++;
    }

    /** Monotonic in-memory change counter for callers that coalesce saves. */
    public synchronized long revision() {
        return revision;
    }

    private Set<CaptureKey> validatedCaptures(Collection<CaptureKey> captures) {
        Set<CaptureKey> result = new HashSet<>();
        for (CaptureKey capture : captures) {
            result.add(Objects.requireNonNull(capture, "capture"));
        }
        return result;
    }

    private void removeMembership(SourceKey source, CaptureKey capture) {
        Set<CaptureKey> memberships = capturesBySource.get(source);
        if (memberships == null) {
            return;
        }
        memberships.remove(capture);
        if (memberships.isEmpty()) {
            capturesBySource.remove(source);
        }
    }

    private void replaceSighting(Sighting sighting) {
        Sighting previous = sightings.put(sighting.capture(), sighting);
        if (previous != null) {
            evictionOrder.remove(previous);
        }
        evictionOrder.add(sighting);
    }

    private void removeSighting(CaptureKey capture) {
        Sighting removed = sightings.remove(capture);
        if (removed != null) {
            evictionOrder.remove(removed);
        }
    }

    private boolean trimToCapacity() {
        boolean changed = false;
        while (sightings.size() > MAX_SIGHTINGS) {
            Sighting oldest = evictionOrder.pollFirst();
            if (oldest == null) {
                throw new IllegalStateException("Sighting eviction index is empty");
            }
            sightings.remove(oldest.capture());
            removeMembership(SourceKey.of(oldest.holder()), oldest.capture());
            changed = true;
        }
        return changed;
    }

    public enum Kind {
        PLAYER,
        CONTAINER,
        DROPPED
    }

    /**
     * Current captures use profile, snapshot, and NPC alias. Legacy captures
     * carry only the non-null NPC alias.
     */
    public record CaptureKey(String profileId, String snapshotId, UUID npcUuid) {
        public CaptureKey {
            boolean current = text(profileId) && text(snapshotId);
            boolean legacy = profileId == null && snapshotId == null;
            if (!current && !legacy || npcUuid == null) {
                throw new IllegalArgumentException(
                        "Capture key must be complete current or legacy identity"
                );
            }
        }

        // Runtime aliases may change; current captures are identified by profile and receipt.
        @Override public boolean equals(Object other) {
            if (!(other instanceof CaptureKey key)) return false;
            return Objects.equals(profileId, key.profileId) && Objects.equals(snapshotId, key.snapshotId)
                    && (profileId != null || npcUuid.equals(key.npcUuid));
        }

        @Override public int hashCode() {
            return profileId == null ? npcUuid.hashCode() : Objects.hash(profileId, snapshotId);
        }

        private static boolean text(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** A holder's stable source identity is kind, world, and id only. */
    public record Holder(
            Kind kind,
            String worldName,
            String id,
            String name,
            double x,
            double y,
            double z
    ) {
        public Holder {
            if (kind == null || !text(worldName) || !text(id)
                    || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Holder must be complete");
            }
        }

        private static boolean text(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Sighting(
            CaptureKey capture,
            Holder holder,
            long observedAtMs,
            boolean loaded
    ) {
        public Sighting {
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(holder, "holder");
        }
    }

    private record SourceKey(Kind kind, String worldName, String id) {
        private static SourceKey of(Holder holder) {
            return new SourceKey(holder.kind(), holder.worldName(), holder.id());
        }
    }
}
