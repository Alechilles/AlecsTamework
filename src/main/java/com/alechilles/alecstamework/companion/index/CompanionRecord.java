package com.alechilles.alecstamework.companion.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable index entry for one companion (spec 6.2). While the companion has a body
 * in a world, that body's components are authoritative; this record says where the
 * companion is and holds what UI and admission need when it is not live.
 *
 * <p>Revision and generation start at zero, and zero is valid. World-time fields keep
 * their sign, and zero means unset.</p>
 */
public record CompanionRecord(
        @Nonnull UUID profileId,
        long revision,
        long generation,
        @Nullable UUID ownerUuid,
        @Nullable String ownerName,
        @Nonnull String roleId,
        @Nullable String displayName,
        @Nonnull CompanionLocation location,
        @Nonnull RecordScope scope,
        @Nullable String homeWorld,
        @Nullable UUID currentNpcUuid,
        @Nonnull CompanionSummary summary,
        @Nullable String rosterId,
        int rosterSlot,
        boolean bonded,
        long summonedUntilMs,
        long summonCooldownUntilMs,
        long reviveAvailableAtMs,
        long diedAtMs,
        long lastSnapshotAtMs,
        @Nullable String originNamespace,
        @Nullable String originKey,
        @Nonnull List<String> toolIds,
        @Nonnull Map<String, ExtensionEntry> extensions,
        @Nonnull List<DomainClaim> domainClaims,
        long updatedAtMs
) {
    public CompanionRecord {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(location, "location");
        // The scope is derived from the location so a record's scope can never disagree with where it is written.
        scope = RecordScope.of(location.kind(), ownerUuid);
        Objects.requireNonNull(summary, "summary");
        if (revision < 0 || generation < 0) {
            throw new IllegalArgumentException("revision and generation must be non-negative");
        }
        if ((originNamespace == null) != (originKey == null)) {
            throw new IllegalArgumentException("origin namespace and key must be set together");
        }
        toolIds = List.copyOf(toolIds);
        extensions = Map.copyOf(extensions);
        domainClaims = List.copyOf(domainClaims);
    }

    /** True when this record counts toward its owner's owned limit (spec 8.14). */
    public boolean countsAsOwned() {
        return ownerUuid != null && location.kind().countsAsOwned();
    }

    /** True when this record counts toward its owner's deployed limit. */
    public boolean isDeployed() {
        return location.kind() == LocationKind.LIVE;
    }

    @Nonnull
    public static Builder builder(@Nonnull UUID profileId, @Nonnull String roleId, @Nonnull CompanionLocation location) {
        return new Builder(profileId, roleId, location);
    }

    @Nonnull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder; every setter returns this builder. */
    public static final class Builder {
        private UUID profileId;
        private long revision;
        private long generation;
        private UUID ownerUuid;
        private String ownerName;
        private String roleId;
        private String displayName;
        private CompanionLocation location;
        private String homeWorld;
        private UUID currentNpcUuid;
        private CompanionSummary summary = CompanionSummary.EMPTY;
        private String rosterId;
        private int rosterSlot = -1;
        private boolean bonded;
        private long summonedUntilMs;
        private long summonCooldownUntilMs;
        private long reviveAvailableAtMs;
        private long diedAtMs;
        private long lastSnapshotAtMs;
        private String originNamespace;
        private String originKey;
        private List<String> toolIds = new ArrayList<>();
        private Map<String, ExtensionEntry> extensions = new LinkedHashMap<>();
        private List<DomainClaim> domainClaims = new ArrayList<>();
        private long updatedAtMs;

        private Builder(UUID profileId, String roleId, CompanionLocation location) {
            this.profileId = profileId;
            this.roleId = roleId;
            this.location = location;
        }

        private Builder(CompanionRecord r) {
            profileId = r.profileId;
            revision = r.revision;
            generation = r.generation;
            ownerUuid = r.ownerUuid;
            ownerName = r.ownerName;
            roleId = r.roleId;
            displayName = r.displayName;
            location = r.location;
            homeWorld = r.homeWorld;
            currentNpcUuid = r.currentNpcUuid;
            summary = r.summary;
            rosterId = r.rosterId;
            rosterSlot = r.rosterSlot;
            bonded = r.bonded;
            summonedUntilMs = r.summonedUntilMs;
            summonCooldownUntilMs = r.summonCooldownUntilMs;
            reviveAvailableAtMs = r.reviveAvailableAtMs;
            diedAtMs = r.diedAtMs;
            lastSnapshotAtMs = r.lastSnapshotAtMs;
            originNamespace = r.originNamespace;
            originKey = r.originKey;
            toolIds = new ArrayList<>(r.toolIds);
            extensions = new LinkedHashMap<>(r.extensions);
            domainClaims = new ArrayList<>(r.domainClaims);
            updatedAtMs = r.updatedAtMs;
        }

        public Builder revision(long v) { revision = v; return this; }
        public Builder generation(long v) { generation = v; return this; }
        public Builder ownerUuid(@Nullable UUID v) { ownerUuid = v; return this; }
        public Builder ownerName(@Nullable String v) { ownerName = v; return this; }
        public Builder roleId(@Nonnull String v) { roleId = v; return this; }
        public Builder displayName(@Nullable String v) { displayName = v; return this; }
        public Builder location(@Nonnull CompanionLocation v) { location = v; return this; }
        public Builder homeWorld(@Nullable String v) { homeWorld = v; return this; }
        public Builder currentNpcUuid(@Nullable UUID v) { currentNpcUuid = v; return this; }
        public Builder summary(@Nonnull CompanionSummary v) { summary = v; return this; }
        public Builder rosterId(@Nullable String v) { rosterId = v; return this; }
        public Builder rosterSlot(int v) { rosterSlot = v; return this; }
        public Builder bonded(boolean v) { bonded = v; return this; }
        public Builder summonedUntilMs(long v) { summonedUntilMs = v; return this; }
        public Builder summonCooldownUntilMs(long v) { summonCooldownUntilMs = v; return this; }
        public Builder reviveAvailableAtMs(long v) { reviveAvailableAtMs = v; return this; }
        public Builder diedAtMs(long v) { diedAtMs = v; return this; }
        public Builder lastSnapshotAtMs(long v) { lastSnapshotAtMs = v; return this; }
        public Builder origin(@Nullable String namespace, @Nullable String key) { originNamespace = namespace; originKey = key; return this; }
        public Builder toolIds(@Nonnull List<String> v) { toolIds = new ArrayList<>(v); return this; }
        public Builder extension(@Nonnull String key, @Nullable ExtensionEntry v) {
            if (v == null) { extensions.remove(key); } else { extensions.put(key, v); }
            return this;
        }
        public Builder extensions(@Nonnull Map<String, ExtensionEntry> v) { extensions = new LinkedHashMap<>(v); return this; }
        public Builder domainClaims(@Nonnull List<DomainClaim> v) { domainClaims = new ArrayList<>(v); return this; }
        public Builder updatedAtMs(long v) { updatedAtMs = v; return this; }

        @Nonnull
        public CompanionRecord build() {
            return new CompanionRecord(profileId, revision, generation, ownerUuid, ownerName, roleId, displayName,
                    location, RecordScope.WORLD_BOUND, homeWorld, currentNpcUuid, summary, rosterId, rosterSlot, bonded,
                    summonedUntilMs, summonCooldownUntilMs, reviveAvailableAtMs, diedAtMs, lastSnapshotAtMs,
                    originNamespace, originKey, toolIds, extensions, domainClaims, updatedAtMs);
        }
    }
}
