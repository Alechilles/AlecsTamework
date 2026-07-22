package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRosterActionAuthorityTest {
    private ExecutorService executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void cleanCopiesWithDifferentToolIdsResolveOneCanonicalSnapshotOffThread() throws Exception {
        UUID owner = UUID.randomUUID();
        CommandRosterActionAuthority.LoadedRoster loaded = oneMember(owner, "dragons", "profile-1");
        AtomicReference<String> loaderThread = new AtomicReference<>();
        AtomicInteger loads = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "roster-loader"));
        MutableClock clock = new MutableClock(10_000L);
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (requestedOwner, family) -> {
                    loaderThread.set(Thread.currentThread().getName());
                    loads.incrementAndGet();
                    return loaded;
                }, executor, clock, 5_000L);
        TwCommandItemConfig horn = rosterConfig("dragons");

        CommandRosterActionAuthority.Resolution firstMiss =
                authority.resolveCached(owner, horn, "clean-copy-a");
        assertEquals(CommandRosterActionAuthority.ResolutionStatus.REFRESHING, firstMiss.status());
        assertNull(firstMiss.snapshot());

        CommandRosterActionAuthority.RefreshResult refresh =
                authority.refreshAsync(owner, horn).toCompletableFuture().join();
        assertEquals(CommandRosterActionAuthority.RefreshStatus.LOADED, refresh.status());
        CommandRosterActionAuthority.Resolution copyA =
                authority.resolveCached(owner, horn, "clean-copy-a");
        CommandRosterActionAuthority.Resolution copyB =
                authority.resolveCached(owner, horn, "clean-copy-b");

        assertEquals(CommandRosterActionAuthority.ResolutionStatus.READY, copyA.status());
        assertEquals(CommandRosterActionAuthority.ResolutionStatus.READY, copyB.status());
        assertSame(copyA.snapshot(), copyB.snapshot());
        assertEquals(1, copyA.snapshot().members().size());
        assertEquals("profile-1", copyA.snapshot().members().getFirst().profileId());
        assertEquals(1, loads.get());
        assertEquals("roster-loader", loaderThread.get());
        assertNotEquals(Thread.currentThread().getName(), loaderThread.get());
    }

    @Test
    void arbitraryPhysicalToolMetadataCannotCreateOrSelectRosterMembership() throws Exception {
        UUID owner = UUID.randomUUID();
        executor = Executors.newSingleThreadExecutor();
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (requestedOwner, family) -> new CommandRosterActionAuthority.LoadedRoster(
                        new CommandFamilyRosterView(owner, "dragons", 7L, java.util.List.of(), 100L),
                        Map.of()),
                executor, new MutableClock(1_000L), 5_000L);
        TwCommandItemConfig horn = rosterConfig("dragons");

        authority.refreshAsync(owner, horn).toCompletableFuture().join();
        CommandRosterActionAuthority.Resolution forged = authority.resolveCached(
                owner, horn, "forged-tool-id:linked=someone-elses-profile");

        assertEquals(CommandRosterActionAuthority.ResolutionStatus.READY, forged.status());
        assertTrue(forged.snapshot().members().isEmpty());
        assertNull(forged.snapshot().findByProfileId("someone-elses-profile"));
    }

    @Test
    void cacheMissFailsClosedWhileOneCoalescedRefreshRuns() throws Exception {
        UUID owner = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor();
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (requestedOwner, family) -> {
                    loads.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return oneMember(owner, family, "profile-1");
                }, executor, new MutableClock(1_000L), 5_000L);
        TwCommandItemConfig horn = rosterConfig("dragons");

        CommandRosterActionAuthority.Resolution miss = authority.resolveCached(owner, horn, "a");
        assertEquals(CommandRosterActionAuthority.ResolutionStatus.REFRESHING, miss.status());
        assertNull(miss.snapshot());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<CommandRosterActionAuthority.RefreshResult> first =
                authority.refreshAsync(owner, horn).toCompletableFuture();
        CompletableFuture<CommandRosterActionAuthority.RefreshResult> second =
                authority.refreshAsync(owner, horn).toCompletableFuture();
        assertSame(first, second);
        assertFalse(first.isDone());

        release.countDown();
        assertEquals(CommandRosterActionAuthority.RefreshStatus.LOADED, first.join().status());
        assertEquals(1, loads.get());
    }

    @Test
    void expiredSnapshotRemainsReadableWhileAsyncRefreshReplacesIt() throws Exception {
        UUID owner = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor();
        MutableClock clock = new MutableClock(1_000L);
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (requestedOwner, family) -> oneMember(owner, family,
                        "profile-" + loads.incrementAndGet()),
                executor, clock, 100L);
        TwCommandItemConfig horn = rosterConfig("dragons");

        authority.refreshAsync(owner, horn).toCompletableFuture().join();
        assertEquals("profile-1", authority.resolveCached(owner, horn, "a")
                .snapshot().members().getFirst().profileId());
        clock.setMillis(1_100L);

        CommandRosterActionAuthority.Resolution expired = authority.resolveCached(owner, horn, "b");
        assertEquals(CommandRosterActionAuthority.ResolutionStatus.READY, expired.status());
        assertEquals("profile-1", expired.snapshot().members().getFirst().profileId());
        authority.refreshAsync(owner, horn).toCompletableFuture().join();
        assertEquals("profile-2", authority.resolveCached(owner, horn, "b")
                .snapshot().members().getFirst().profileId());
    }

    @Test
    void nonRosterConfigKeepsLegacyPathAndNeverTouchesSqliteLoader() {
        AtomicInteger loads = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor();
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (owner, family) -> {
                    loads.incrementAndGet();
                    throw new AssertionError("legacy config must not load roster storage");
                }, executor, new MutableClock(1_000L), 5_000L);

        CommandRosterActionAuthority.Resolution result = authority.resolveCached(
                UUID.randomUUID(), new TestCommandItemConfig(), "legacy-tool");

        assertEquals(CommandRosterActionAuthority.ResolutionStatus.LEGACY_ITEM_METADATA, result.status());
        assertNull(result.snapshot());
        assertEquals(0, loads.get());
    }

    @Test
    void mismatchedProfileFailsRefreshWithoutInstallingPartialRoster() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        CommandRosterActionAuthority.LoadedRoster loaded = oneMember(owner, "dragons", "profile-1");
        NpcProfileRepository.ProfileRecord mismatched = new NpcProfileRepository.ProfileRecord(
                "profile-1", UUID.randomUUID(), otherOwner, null, "Tamed_Dragon", "Dragon",
                null, true, null, null, new String[0], new String[0], 100L);
        CommandRosterActionAuthority.LoadedRoster invalid = new CommandRosterActionAuthority.LoadedRoster(
                loaded.roster(), Map.of("profile-1", mismatched));
        executor = Executors.newSingleThreadExecutor();
        CommandRosterActionAuthority authority = new CommandRosterActionAuthority(
                (requestedOwner, family) -> invalid,
                executor, new MutableClock(1_000L), 5_000L);
        TwCommandItemConfig horn = rosterConfig("dragons");

        CommandRosterActionAuthority.RefreshResult result =
                authority.refreshAsync(owner, horn).toCompletableFuture().join();

        assertEquals(CommandRosterActionAuthority.RefreshStatus.FAILED, result.status());
        assertEquals(CommandRosterActionAuthority.ResolutionStatus.REFRESHING,
                authority.resolveCached(owner, horn, "copy").status());
    }

    private static CommandRosterActionAuthority.LoadedRoster oneMember(
            UUID owner, String family, String profileId) {
        UUID npcUuid = UUID.nameUUIDFromBytes(("npc:" + profileId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CommandFamilyRosterMembershipView membership = new CommandFamilyRosterMembershipView(
                owner, family, profileId, "Tamed_Dragon", 3L,
                CommandFamilyRosterMemberState.ACTIVE, "flight", true,
                new Vector3View(1.0, 2.0, 3.0), 100L);
        CommandFamilyRosterView roster = new CommandFamilyRosterView(
                owner, family, 9L, java.util.List.of(membership), 100L);
        NpcProfileRepository.ProfileRecord profile = new NpcProfileRepository.ProfileRecord(
                profileId, npcUuid, owner, null, "Tamed_Dragon", "Dragon", null, true,
                null, null, new String[0], new String[0], 100L);
        return new CommandRosterActionAuthority.LoadedRoster(roster, Map.of(profileId, profile));
    }

    private static TwCommandItemConfig rosterConfig(String familyId) throws Exception {
        TwCommandItemConfig config = new TestCommandItemConfig();
        set(config, "rosterStorage", TwCommandItemConfig.RosterStorage.OwnerCommandFamily);
        set(config, "commandFamilyId", familyId);
        return config;
    }

    private static final class TestCommandItemConfig extends TwCommandItemConfig {
        private TestCommandItemConfig() {
            super();
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = findField(target.getClass(), field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static Field findField(Class<?> type, String field) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                // Test fixtures may subclass configs whose writable fields remain private.
            }
        }
        throw new NoSuchFieldException(field);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(long millis) {
            instant = new AtomicReference<>(Instant.ofEpochMilli(millis));
        }

        void setMillis(long millis) {
            instant.set(Instant.ofEpochMilli(millis));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
