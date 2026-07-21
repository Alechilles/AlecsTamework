package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupAdmissionPolicy;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCounts;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Isolated population-group transaction fixtures with no profile or player writes. */
final class HyDragonPopulationGroupSelfTestFixture {
    private static final UUID OWNER_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final String GROUP_ID = "hydragon:self-test-miniwyvern";
    private static final String NEXT_GROUP_ID = "hydragon:self-test-dragon";

    private HyDragonPopulationGroupSelfTestFixture() {
    }

    static List<ApiSelfTestAssertion> run() {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        try {
            PopulationGroupDefinitionView definition = definition(
                    GROUP_ID, "HyDragon_SelfTest_Miniwyvern", 1L, 1L);
            PopulationGroupIndex index = isolatedIndex(List.of(definition));
            PopulationGroupBucket bucket = PopulationGroupBucket.of(OWNER_ID, definition, null);
            PopulationGroupAdmissionPolicy policy = new PopulationGroupAdmissionPolicy(index);
            PopulationGroupAdmissionPolicy.Decision decision = policy.evaluate(
                    Map.of(bucket, new PopulationGroupCounts(1L, 0L, 1L, 0L)),
                    Map.of(bucket, new PopulationGroupCountDelta(1, 1)),
                    PopulationAdmissionForcePolicy.ADMIN_OVERRIDE);
            List<String> reasons = decision.violations().stream()
                    .map(PopulationGroupAdmissionPolicy.Violation::reason)
                    .toList();
            boolean passed = !decision.allowed()
                    && reasons.equals(List.of(
                    "population-group-owned-limit",
                    "population-group-active-limit"));
            assertions.add(new ApiSelfTestAssertion(
                    "isolated population group rejects boundary overflow",
                    passed,
                    "allowed=" + decision.allowed() + " violations=" + reasons));

            ReservationHarness reservations = new ReservationHarness(policy, bucket);
            ConcurrentReservationResult concurrency = compete(reservations);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated population reservations serialize and cancel exactly once",
                    concurrency.oneWinner()
                            && concurrency.duplicateWinner()
                            && !concurrency.duplicateLoserBeforeCancel()
                            && concurrency.canceled()
                            && !concurrency.duplicateCancel()
                            && concurrency.loserRetry()
                            && reservations.pendingOwned() == 1L
                            && reservations.pendingActive() == 1L,
                    "winners=" + concurrency.winners()
                            + " duplicate=" + concurrency.duplicateWinner()
                            + " canceled=" + concurrency.canceled()
                            + " retry=" + concurrency.loserRetry()
                            + " pending=" + reservations.pendingOwned() + "/"
                            + reservations.pendingActive()));

            PopulationGroupDefinitionView nextDefinition = definition(
                    NEXT_GROUP_ID, "HyDragon_SelfTest_Dragon", 1L, 1L);
            PopulationGroupIndex roleChangeIndex = isolatedIndex(List.of(definition, nextDefinition));
            PopulationGroupBucket nextBucket = PopulationGroupBucket.of(
                    OWNER_ID, nextDefinition, null);
            LinkedHashMap<PopulationGroupBucket, PopulationGroupCounts> roleCounts = new LinkedHashMap<>();
            roleCounts.put(bucket, new PopulationGroupCounts(1L, 0L, 1L, 0L));
            roleCounts.put(nextBucket, PopulationGroupCounts.ZERO);
            LinkedHashMap<PopulationGroupBucket, PopulationGroupCountDelta> roleDeltas = new LinkedHashMap<>();
            roleDeltas.put(bucket, new PopulationGroupCountDelta(-1, -1));
            roleDeltas.put(nextBucket, new PopulationGroupCountDelta(1, 1));
            PopulationGroupAdmissionPolicy.Decision roleChange =
                    new PopulationGroupAdmissionPolicy(roleChangeIndex).evaluate(
                            roleCounts, roleDeltas, PopulationAdmissionForcePolicy.ENFORCE);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated population role change is evaluated all-or-none",
                    roleChange.allowed()
                            && roleDeltas.get(bucket).owned() == -1
                            && roleDeltas.get(nextBucket).owned() == 1,
                    "allowed=" + roleChange.allowed() + " oldDelta=-1/-1 newDelta=1/1"));

            PopulationGroupIndex unavailableIndex = isolatedIndex(List.of());
            PopulationGroupAdmissionPolicy.Decision unavailable =
                    new PopulationGroupAdmissionPolicy(unavailableIndex).evaluate(
                            Map.of(nextBucket, PopulationGroupCounts.ZERO),
                            Map.of(nextBucket, new PopulationGroupCountDelta(1, 1)),
                            PopulationAdmissionForcePolicy.ENFORCE);
            List<String> unavailableReasons = unavailable.violations().stream()
                    .map(PopulationGroupAdmissionPolicy.Violation::reason)
                    .toList();
            assertions.add(new ApiSelfTestAssertion(
                    "isolated unavailable population config fails closed without reservation",
                    !unavailable.allowed()
                            && unavailableReasons.equals(List.of("population-group-definition-unavailable")),
                    "allowed=" + unavailable.allowed() + " violations=" + unavailableReasons));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            assertions.add(new ApiSelfTestAssertion(
                    "isolated population group fixtures execute",
                    false,
                    failure.getClass().getSimpleName() + ": isolated-fixture-failed"));
        }
        return List.copyOf(assertions);
    }

    private static ConcurrentReservationResult compete(ReservationHarness reservations) {
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "tamework-population-selftest");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> reserveAfterStart(
                    reservations, "concurrent-a", ready, start));
            Future<Boolean> second = executor.submit(() -> reserveAfterStart(
                    reservations, "concurrent-b", ready, start));
            if (!ready.await(2, TimeUnit.SECONDS)) {
                return ConcurrentReservationResult.failed();
            }
            start.countDown();
            boolean firstWon = first.get(2, TimeUnit.SECONDS);
            boolean secondWon = second.get(2, TimeUnit.SECONDS);
            String winner = firstWon ? "concurrent-a" : "concurrent-b";
            String loser = firstWon ? "concurrent-b" : "concurrent-a";
            boolean duplicateWinner = reservations.reserve(winner);
            boolean duplicateLoser = reservations.reserve(loser);
            boolean canceled = reservations.cancel(winner);
            boolean duplicateCancel = reservations.cancel(winner);
            boolean loserRetry = reservations.reserve(loser);
            return new ConcurrentReservationResult(
                    (firstWon ? 1 : 0) + (secondWon ? 1 : 0), duplicateWinner,
                    duplicateLoser, canceled, duplicateCancel, loserRetry);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ConcurrentReservationResult.failed();
        } catch (Exception failure) {
            return ConcurrentReservationResult.failed();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static boolean reserveAfterStart(
            ReservationHarness reservations,
            String operationId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return reservations.reserve(operationId);
    }

    private record ConcurrentReservationResult(
            int winners,
            boolean duplicateWinner,
            boolean duplicateLoserBeforeCancel,
            boolean canceled,
            boolean duplicateCancel,
            boolean loserRetry) {
        private boolean oneWinner() {
            return winners == 1;
        }

        private static ConcurrentReservationResult failed() {
            return new ConcurrentReservationResult(0, false, false, false, false, false);
        }
    }

    private static PopulationGroupDefinitionView definition(
            String groupId,
            String roleId,
            long maxOwned,
            long maxActive) {
        return new PopulationGroupDefinitionView(
                roleId,
                1L,
                groupId,
                Set.of(roleId),
                maxOwned,
                maxActive,
                PopulationGroupScope.GLOBAL);
    }

    /** Builds the immutable production index directly so the fixture is independent of live assets. */
    private static PopulationGroupIndex isolatedIndex(List<PopulationGroupDefinitionView> definitions)
            throws ReflectiveOperationException {
        Constructor<PopulationGroupIndex> constructor = PopulationGroupIndex.class.getDeclaredConstructor(
                long.class, Map.class, Map.class);
        constructor.setAccessible(true);
        LinkedHashMap<String, PopulationGroupDefinitionView> byGroup = new LinkedHashMap<>();
        LinkedHashMap<String, List<PopulationGroupDefinitionView>> byRole = new LinkedHashMap<>();
        for (PopulationGroupDefinitionView definition : definitions) {
            byGroup.put(definition.groupId(), definition);
            for (String roleId : definition.roleIds()) {
                byRole.computeIfAbsent(roleId, ignored -> new ArrayList<>()).add(definition);
            }
        }
        return constructor.newInstance(
                1L,
                byGroup,
                byRole);
    }

    /** Minimal CAS-style reservation shell around the production pure policy. */
    private static final class ReservationHarness {
        private final PopulationGroupAdmissionPolicy policy;
        private final PopulationGroupBucket bucket;
        private final Set<String> reservations = new java.util.LinkedHashSet<>();

        private ReservationHarness(PopulationGroupAdmissionPolicy policy, PopulationGroupBucket bucket) {
            this.policy = policy;
            this.bucket = bucket;
        }

        private synchronized boolean reserve(String operationId) {
            if (reservations.contains(operationId)) {
                return true;
            }
            PopulationGroupCounts counts = new PopulationGroupCounts(
                    0L, reservations.size(), 0L, reservations.size());
            PopulationGroupAdmissionPolicy.Decision decision = policy.evaluate(
                    Map.of(bucket, counts),
                    Map.of(bucket, new PopulationGroupCountDelta(1, 1)),
                    PopulationAdmissionForcePolicy.ENFORCE);
            if (!decision.allowed()) {
                return false;
            }
            reservations.add(operationId);
            return true;
        }

        private synchronized boolean cancel(String operationId) {
            return reservations.remove(operationId);
        }

        private synchronized long pendingOwned() {
            return reservations.size();
        }

        private synchronized long pendingActive() {
            return reservations.size();
        }
    }
}
