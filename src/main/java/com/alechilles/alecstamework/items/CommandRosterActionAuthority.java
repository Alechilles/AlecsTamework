package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipChangedEvent;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.command.roster.CommandFamilyRosterService;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.persistence.sqlite.CommandFamilyRosterRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Read authority for command actions backed by an owner/command-family roster.
 *
 * <p>World-thread callers only read immutable cache entries. Blocking roster/profile loads are
 * scheduled through the supplied executor, and a cache miss or expired entry fails closed while a
 * coalesced refresh runs. Physical command-tool IDs and item metadata are deliberately absent from
 * the cache key and membership decision: they are access-item state, never roster authority.</p>
 */
final class CommandRosterActionAuthority {
    static final long DEFAULT_MAX_CACHE_AGE_MS = 30_000L;

    private final CanonicalLoader loader;
    private final Executor loaderExecutor;
    private final Clock clock;
    private final long maxCacheAgeMs;
    @Nullable private final CommandFamilyRosterService mutationService;
    private final ConcurrentHashMap<RosterKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RosterKey, CompletableFuture<RefreshResult>> refreshes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RosterKey, AtomicLong> generations = new ConcurrentHashMap<>();

    CommandRosterActionAuthority(
            @Nonnull CommandFamilyRosterRepository rosterRepository,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull Executor loaderExecutor,
            @Nullable CommandFamilyRosterService mutationService) {
        this(new RepositoryCanonicalLoader(rosterRepository, profileRepository), loaderExecutor,
                Clock.systemUTC(), DEFAULT_MAX_CACHE_AGE_MS, mutationService);
    }

    CommandRosterActionAuthority(@Nonnull CanonicalLoader loader,
                                 @Nonnull Executor loaderExecutor,
                                 @Nonnull Clock clock,
                                 long maxCacheAgeMs) {
        this(loader, loaderExecutor, clock, maxCacheAgeMs, null);
    }

    CommandRosterActionAuthority(@Nonnull CanonicalLoader loader,
                                 @Nonnull Executor loaderExecutor,
                                 @Nonnull Clock clock,
                                 long maxCacheAgeMs,
                                 @Nullable CommandFamilyRosterService mutationService) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.loaderExecutor = Objects.requireNonNull(loaderExecutor, "loaderExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxCacheAgeMs <= 0L) {
            throw new IllegalArgumentException("maxCacheAgeMs must be positive");
        }
        this.maxCacheAgeMs = maxCacheAgeMs;
        this.mutationService = mutationService;
        if (mutationService != null) {
            mutationService.subscribeChanges(this::rosterChanged);
        }
    }

    @Nonnull
    List<LinkedNpcRecord> project(@Nonnull Snapshot snapshot) {
        ArrayList<LinkedNpcRecord> records = new ArrayList<>(snapshot.members().size());
        for (Member member : snapshot.members()) {
            Vector3d home = member.homePosition() == null ? null : new Vector3d(
                    member.homePosition().x(), member.homePosition().y(), member.homePosition().z());
            records.add(new LinkedNpcRecord(
                    member.presentationUuid(), member.profileId(), null, null, home,
                    member.displayName(), null, member.roleId(), member.state().name(),
                    member.activeForBulkCommands()
                            && (member.state() == CommandFamilyRosterMemberState.ACTIVE
                            || member.state() == CommandFamilyRosterMemberState.UNLOADED),
                    false, member.groupId()));
        }
        return List.copyOf(records);
    }

    /**
     * Resolves one command access item without consulting its local linked-record metadata.
     *
     * @param physicalToolId diagnostic identity only; different legitimate copies intentionally
     *                       resolve the same owner/family snapshot
     */
    @Nonnull
    Resolution resolveCached(@Nonnull UUID ownerUuid,
                             @Nullable TwCommandItemConfig config,
                             @Nullable String physicalToolId) {
        AccessMode access = accessMode(config);
        if (access.status() == AccessStatus.LEGACY_ITEM_METADATA) {
            return Resolution.legacy();
        }
        if (access.status() == AccessStatus.DENIED) {
            return Resolution.denied(access.reason());
        }
        RosterKey key = new RosterKey(Objects.requireNonNull(ownerUuid, "ownerUuid"),
                Objects.requireNonNull(access.commandFamilyId()));
        CacheEntry current = cache.get(key);
        long nowMs = clock.millis();
        if (current == null) {
            refresh(key);
            return Resolution.refreshing("command-roster-cache-miss");
        }
        if (current.expiredAt(nowMs, maxCacheAgeMs)) {
            refresh(key);
            return Resolution.ready(current.snapshot(), "command-roster-cache-refreshing");
        }
        return Resolution.ready(current.snapshot());
    }

    /** Starts or joins an off-thread refresh for an owner-family roster. */
    @Nonnull
    CompletionStage<RefreshResult> refreshAsync(@Nonnull UUID ownerUuid,
                                                @Nullable TwCommandItemConfig config) {
        AccessMode access = accessMode(config);
        if (access.status() == AccessStatus.LEGACY_ITEM_METADATA) {
            return CompletableFuture.completedFuture(RefreshResult.legacy());
        }
        if (access.status() == AccessStatus.DENIED) {
            return CompletableFuture.completedFuture(RefreshResult.denied(access.reason()));
        }
        RosterKey key = new RosterKey(Objects.requireNonNull(ownerUuid, "ownerUuid"),
                Objects.requireNonNull(access.commandFamilyId()));
        CacheEntry current = cache.get(key);
        if (current != null && !current.expiredAt(clock.millis(), maxCacheAgeMs)) {
            return CompletableFuture.completedFuture(RefreshResult.loaded(current.snapshot()));
        }
        return refresh(key);
    }

    /** Invalidates an owner-family snapshot; an in-flight older generation cannot reinstall it. */
    void invalidate(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId) {
        RosterKey key = new RosterKey(ownerUuid, commandFamilyId);
        generations.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        cache.remove(key);
    }

    /** Refreshes a canonical roster immediately after its committed mutation event. */
    void rosterChanged(@Nonnull CommandFamilyRosterMembershipChangedEvent event) {
        Objects.requireNonNull(event, "event");
        RosterKey key = new RosterKey(event.ownerUuid(), event.commandFamilyId());
        generations.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        cache.remove(key);
        refresh(key);
    }

    @Nonnull
    CompletionStage<Boolean> updateMember(@Nonnull UUID ownerUuid,
                                          @Nonnull TwCommandItemConfig config,
                                          @Nullable String accessItemId,
                                          @Nonnull UUID presentationUuid,
                                          @Nonnull MemberUpdate update) {
        if (mutationService == null) return CompletableFuture.completedFuture(false);
        Resolution resolution = resolveCached(ownerUuid, config, null);
        Snapshot snapshot = resolution.snapshot();
        Member member = snapshot == null ? null : snapshot.findByPresentationUuid(presentationUuid);
        if (member == null) return CompletableFuture.completedFuture(false);
        CommandFamilyRosterMutationRequest request = new CommandFamilyRosterMutationRequest(
                "Alechilles:Tamework:CommandRosterActions", UUID.randomUUID().toString(), null,
                ownerUuid, snapshot.commandFamilyId(), member.profileId(), config.getId(), accessItemId,
                update.state() == null ? member.state() : update.state(),
                update.groupSpecified() ? update.groupId() : member.groupId(),
                update.activeForBulkCommands() == null
                        ? member.activeForBulkCommands() : update.activeForBulkCommands(),
                update.homeSpecified() ? update.homePosition() : member.homePosition(),
                snapshot.rosterRevision(), member.profileRevision());
        return mutationService.upsert(request).thenApply(result -> {
            if (!result.accepted()) {
                invalidate(ownerUuid, snapshot.commandFamilyId());
                refreshAsync(ownerUuid, config);
                return false;
            }
            invalidate(ownerUuid, snapshot.commandFamilyId());
            refreshAsync(ownerUuid, config);
            return true;
        });
    }

    @Nonnull
    CompletionStage<Boolean> removeMember(@Nonnull UUID ownerUuid,
                                          @Nonnull TwCommandItemConfig config,
                                          @Nullable String accessItemId,
                                          @Nonnull UUID presentationUuid) {
        if (mutationService == null) return CompletableFuture.completedFuture(false);
        Resolution resolution = resolveCached(ownerUuid, config, null);
        Snapshot snapshot = resolution.snapshot();
        Member member = snapshot == null ? null : snapshot.findByPresentationUuid(presentationUuid);
        if (member == null) return CompletableFuture.completedFuture(false);
        CommandFamilyRosterMutationRequest request = new CommandFamilyRosterMutationRequest(
                "Alechilles:Tamework:CommandRosterActions", UUID.randomUUID().toString(), null,
                ownerUuid, snapshot.commandFamilyId(), member.profileId(), config.getId(), accessItemId,
                member.state(), member.groupId(), member.activeForBulkCommands(), member.homePosition(),
                snapshot.rosterRevision(), member.profileRevision());
        return mutationService.remove(request).thenApply(result -> {
            if (!result.accepted()) {
                invalidate(ownerUuid, snapshot.commandFamilyId());
                refreshAsync(ownerUuid, config);
                return false;
            }
            invalidate(ownerUuid, snapshot.commandFamilyId());
            refreshAsync(ownerUuid, config);
            return true;
        });
    }

    private CompletableFuture<RefreshResult> refresh(RosterKey key) {
        while (true) {
            CompletableFuture<RefreshResult> existing = refreshes.get(key);
            if (existing != null) return existing;
            CompletableFuture<RefreshResult> placeholder = new CompletableFuture<>();
            if (refreshes.putIfAbsent(key, placeholder) != null) continue;
            startRefresh(key).whenComplete((result, failure) -> {
                if (failure == null) placeholder.complete(result);
                else placeholder.completeExceptionally(failure);
                refreshes.remove(key, placeholder);
            });
            return placeholder;
        }
    }

    private CompletableFuture<RefreshResult> startRefresh(RosterKey key) {
        long generation = generations.computeIfAbsent(key, ignored -> new AtomicLong()).get();
        final CompletableFuture<RefreshResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> load(key, generation), loaderExecutor);
        } catch (RejectedExecutionException failure) {
            return CompletableFuture.completedFuture(
                    RefreshResult.failed("command-roster-refresh-rejected"));
        }
        return future;
    }

    private RefreshResult load(RosterKey key, long generation) {
        try {
            LoadedRoster loaded = loader.load(key.ownerUuid(), key.commandFamilyId());
            Snapshot snapshot = canonicalize(key, loaded, clock.millis());
            AtomicLong currentGeneration = generations.computeIfAbsent(
                    key, ignored -> new AtomicLong());
            if (currentGeneration.get() != generation) {
                return RefreshResult.discarded(snapshot, "command-roster-refresh-invalidated");
            }
            cache.put(key, new CacheEntry(snapshot, snapshot.loadedAtMs()));
            return RefreshResult.loaded(snapshot);
        } catch (Exception | LinkageError failure) {
            return RefreshResult.failed("command-roster-refresh-failed");
        }
    }

    private static Snapshot canonicalize(RosterKey key, LoadedRoster loaded, long loadedAtMs) {
        Objects.requireNonNull(loaded, "loaded");
        CommandFamilyRosterView roster = loaded.roster();
        if (roster != null && (!key.ownerUuid().equals(roster.ownerUuid())
                || !key.commandFamilyId().equals(roster.commandFamilyId()))) {
            throw new IllegalStateException("command-roster-identity-mismatch");
        }
        long revision = roster == null ? 0L : roster.revision();
        List<CommandFamilyRosterMembershipView> memberships = roster == null
                ? List.of() : roster.memberships();
        ArrayList<Member> members = new ArrayList<>(memberships.size());
        LinkedHashMap<String, Member> byProfileId = new LinkedHashMap<>();
        HashMap<UUID, Member> byPresentationUuid = new HashMap<>();
        for (CommandFamilyRosterMembershipView membership : memberships) {
            NpcProfileRepository.ProfileRecord profile = loaded.profiles().get(membership.profileId());
            Member member = canonicalMember(key, membership, profile);
            if (byProfileId.putIfAbsent(member.profileId(), member) != null
                    || byPresentationUuid.putIfAbsent(member.presentationUuid(), member) != null) {
                throw new IllegalStateException("command-roster-member-identity-collision");
            }
            members.add(member);
        }
        return new Snapshot(key.ownerUuid(), key.commandFamilyId(), revision,
                List.copyOf(members), Map.copyOf(byProfileId), Map.copyOf(byPresentationUuid),
                loadedAtMs);
    }

    private static Member canonicalMember(
            RosterKey key,
            CommandFamilyRosterMembershipView membership,
            @Nullable NpcProfileRepository.ProfileRecord profile) {
        if (!key.ownerUuid().equals(membership.ownerUuid())
                || !key.commandFamilyId().equals(membership.commandFamilyId())
                || profile == null
                || !membership.profileId().equals(profile.profileId())
                || !key.ownerUuid().equals(profile.ownerUuid())
                || profile.roleId() == null
                || !membership.roleId().equals(profile.roleId())) {
            throw new IllegalStateException("command-roster-member-profile-mismatch");
        }
        UUID presentationUuid = profile.currentNpcUuid() != null
                ? profile.currentNpcUuid() : presentationUuid(membership.profileId());
        String displayName = firstText(profile.customName(), profile.displayName());
        return new Member(key.ownerUuid(), key.commandFamilyId(), membership.profileId(),
                membership.roleId(), membership.profileRevision(), membership.state(),
                membership.groupId(), membership.activeForBulkCommands(), membership.homePosition(),
                profile.currentNpcUuid(), presentationUuid, displayName, profile.updatedAtMs());
    }

    private static AccessMode accessMode(@Nullable TwCommandItemConfig config) {
        if (config == null || !config.usesOwnerCommandFamilyRoster()) {
            return AccessMode.legacy();
        }
        String familyId = normalize(config.getCommandFamilyId());
        if (familyId == null) {
            return AccessMode.denied("command-roster-family-required");
        }
        return AccessMode.roster(familyId);
    }

    private static UUID presentationUuid(String profileId) {
        return UUID.nameUUIDFromBytes(("tamework-roster-profile\u0000" + profileId)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String firstText(@Nullable String preferred, @Nullable String fallback) {
        String first = normalize(preferred);
        return first != null ? first : normalize(fallback);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @FunctionalInterface
    interface CanonicalLoader {
        @Nonnull LoadedRoster load(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId)
                throws Exception;
    }

    record LoadedRoster(@Nullable CommandFamilyRosterView roster,
                        @Nonnull Map<String, NpcProfileRepository.ProfileRecord> profiles) {
        LoadedRoster {
            profiles = Map.copyOf(Objects.requireNonNull(profiles, "profiles"));
        }
    }

    record Member(@Nonnull UUID ownerUuid,
                  @Nonnull String commandFamilyId,
                  @Nonnull String profileId,
                  @Nonnull String roleId,
                  long profileRevision,
                  @Nonnull CommandFamilyRosterMemberState state,
                  @Nullable String groupId,
                  boolean activeForBulkCommands,
                  @Nullable Vector3View homePosition,
                  @Nullable UUID currentNpcUuid,
                  @Nonnull UUID presentationUuid,
                  @Nullable String displayName,
                  long profileUpdatedAtMs) {
        Member {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = Objects.requireNonNull(commandFamilyId, "commandFamilyId");
            profileId = Objects.requireNonNull(profileId, "profileId");
            roleId = Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(presentationUuid, "presentationUuid");
        }
    }

    record MemberUpdate(@Nullable CommandFamilyRosterMemberState state,
                        @Nullable Boolean activeForBulkCommands,
                        boolean groupSpecified,
                        @Nullable String groupId,
                        boolean homeSpecified,
                        @Nullable Vector3View homePosition) {
        static MemberUpdate active(boolean active) {
            return new MemberUpdate(null, active, false, null, false, null);
        }

        static MemberUpdate group(@Nullable String groupId) {
            return new MemberUpdate(null, null, true, groupId, false, null);
        }

        static MemberUpdate home(@Nullable Vector3View homePosition) {
            return new MemberUpdate(null, null, false, null, true, homePosition);
        }

        static MemberUpdate state(CommandFamilyRosterMemberState state) {
            return new MemberUpdate(Objects.requireNonNull(state), null, false, null, false, null);
        }
    }

    record Snapshot(@Nonnull UUID ownerUuid,
                    @Nonnull String commandFamilyId,
                    long rosterRevision,
                    @Nonnull List<Member> members,
                    @Nonnull Map<String, Member> membersByProfileId,
                    @Nonnull Map<UUID, Member> membersByPresentationUuid,
                    long loadedAtMs) {
        Snapshot {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = Objects.requireNonNull(commandFamilyId, "commandFamilyId");
            members = List.copyOf(Objects.requireNonNull(members, "members"));
            membersByProfileId = Map.copyOf(Objects.requireNonNull(
                    membersByProfileId, "membersByProfileId"));
            membersByPresentationUuid = Map.copyOf(Objects.requireNonNull(
                    membersByPresentationUuid, "membersByPresentationUuid"));
        }

        @Nullable Member findByProfileId(@Nullable String profileId) {
            return profileId == null ? null : membersByProfileId.get(profileId);
        }

        @Nullable Member findByPresentationUuid(@Nullable UUID npcUuid) {
            return npcUuid == null ? null : membersByPresentationUuid.get(npcUuid);
        }
    }

    enum ResolutionStatus { LEGACY_ITEM_METADATA, READY, REFRESHING, DENIED }

    record Resolution(@Nonnull ResolutionStatus status,
                      @Nullable Snapshot snapshot,
                      @Nullable String reason) {
        static Resolution legacy() {
            return new Resolution(ResolutionStatus.LEGACY_ITEM_METADATA, null, null);
        }

        static Resolution ready(Snapshot snapshot) {
            return new Resolution(ResolutionStatus.READY, Objects.requireNonNull(snapshot), null);
        }

        static Resolution ready(Snapshot snapshot, String reason) {
            return new Resolution(ResolutionStatus.READY, Objects.requireNonNull(snapshot), reason);
        }

        static Resolution refreshing(String reason) {
            return new Resolution(ResolutionStatus.REFRESHING, null, reason);
        }

        static Resolution denied(String reason) {
            return new Resolution(ResolutionStatus.DENIED, null, reason);
        }
    }

    enum RefreshStatus { LEGACY_ITEM_METADATA, LOADED, DISCARDED, DENIED, FAILED }

    record RefreshResult(@Nonnull RefreshStatus status,
                         @Nullable Snapshot snapshot,
                         @Nullable String reason) {
        static RefreshResult legacy() {
            return new RefreshResult(RefreshStatus.LEGACY_ITEM_METADATA, null, null);
        }

        static RefreshResult loaded(Snapshot snapshot) {
            return new RefreshResult(RefreshStatus.LOADED, Objects.requireNonNull(snapshot), null);
        }

        static RefreshResult discarded(Snapshot snapshot, String reason) {
            return new RefreshResult(RefreshStatus.DISCARDED, snapshot, reason);
        }

        static RefreshResult denied(String reason) {
            return new RefreshResult(RefreshStatus.DENIED, null, reason);
        }

        static RefreshResult failed(String reason) {
            return new RefreshResult(RefreshStatus.FAILED, null, reason);
        }
    }

    private enum AccessStatus { LEGACY_ITEM_METADATA, ROSTER, DENIED }

    private record AccessMode(@Nonnull AccessStatus status,
                              @Nullable String commandFamilyId,
                              @Nullable String reason) {
        static AccessMode legacy() {
            return new AccessMode(AccessStatus.LEGACY_ITEM_METADATA, null, null);
        }

        static AccessMode roster(String familyId) {
            return new AccessMode(AccessStatus.ROSTER, familyId, null);
        }

        static AccessMode denied(String reason) {
            return new AccessMode(AccessStatus.DENIED, null, reason);
        }
    }

    private record RosterKey(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId) {
        RosterKey {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = Objects.requireNonNull(normalize(commandFamilyId), "commandFamilyId");
        }
    }

    private record CacheEntry(@Nonnull Snapshot snapshot, long loadedAtMs) {
        boolean expiredAt(long nowMs, long maximumAgeMs) {
            return nowMs < loadedAtMs || nowMs - loadedAtMs >= maximumAgeMs;
        }
    }

    private static final class RepositoryCanonicalLoader implements CanonicalLoader {
        private final CommandFamilyRosterRepository rosters;
        private final NpcProfileRepository profiles;

        private RepositoryCanonicalLoader(CommandFamilyRosterRepository rosters,
                                          NpcProfileRepository profiles) {
            this.rosters = Objects.requireNonNull(rosters, "rosters");
            this.profiles = Objects.requireNonNull(profiles, "profiles");
        }

        @Override
        public LoadedRoster load(UUID ownerUuid, String commandFamilyId) throws Exception {
            CommandFamilyRosterView roster = rosters.find(ownerUuid, commandFamilyId);
            if (roster == null || roster.memberships().isEmpty()) {
                return new LoadedRoster(roster, Map.of());
            }
            LinkedHashMap<String, NpcProfileRepository.ProfileRecord> resolved = new LinkedHashMap<>();
            for (CommandFamilyRosterMembershipView membership : roster.memberships()) {
                NpcProfileRepository.ProfileRecord profile =
                        profiles.loadProfileById(membership.profileId());
                if (profile != null) resolved.put(membership.profileId(), profile);
            }
            return new LoadedRoster(roster, resolved);
        }
    }
}
