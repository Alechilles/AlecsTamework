package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production-path coverage for role-aware public population admission. */
class RuntimePopulationPolicyAuthorityV2GroupTest {
    private static final UUID OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final PopulationAdmissionLocation LOCATION =
            new PopulationAdmissionLocation("alpha", 2, 3);

    @TempDir
    Path tempDir;

    /** Regression: V2 must not fall through the compatibility unavailable implementation. */
    @Test
    void v2RoleChangeDebitsOldAndCreditsNewGroupAllOrNone() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("v2-role-change"), null);
             OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence)) {
            PopulationGroupRegistry registry = new PopulationGroupRegistry();
            assertTrue(registry.replace(List.of(
                    group("Group_A", "test:a", "Role_A"),
                    group("Group_B", "test:b", "Role_B"),
                    group("Group_C", "test:c", "Role_C")), 1L).applied());
            assertTrue(runtime.installPopulationGroups(
                    registry,
                    persistence.getPopulationGroupRepository(),
                    persistence.getNpcProfileRepository()).get(5L, TimeUnit.SECONDS).ready());

            RuntimePopulationPolicyAuthority authority = runtime.populationPolicyAuthority();
            UUID npcA = UUID.fromString("00000000-0000-0000-0000-000000000201");
            UUID npcB = UUID.fromString("00000000-0000-0000-0000-000000000202");
            commit(authority, create("profile-a", "create-a", npcA, "Role_A"));
            commit(authority, create("profile-b", "create-b", npcB, "Role_B"));

            PopulationAdmissionDecision denied = authority.tryAdmitV2(change(
                    runtime, "profile-a", "change-a-b", npcA, "Role_B"))
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.DENIED, denied.status());
            assertEquals("population-group-owned-limit", denied.reason());
            assertCounts(persistence.getPopulationGroupRepository(), "test:a", 1, 0);
            assertCounts(persistence.getPopulationGroupRepository(), "test:b", 1, 0);

            commit(authority, change(runtime, "profile-a", "change-a-c", npcA, "Role_C"));

            assertEquals(List.of("test:c"), persistence.getPopulationGroupRepository()
                    .findClassification("profile-a").groupIds());
            assertCounts(persistence.getPopulationGroupRepository(), "test:a", 0, 0);
            assertCounts(persistence.getPopulationGroupRepository(), "test:b", 1, 0);
            assertCounts(persistence.getPopulationGroupRepository(), "test:c", 1, 0);
        }
    }

    private static PopulationAdmissionRequestV2 create(
            String profileId, String key, UUID npcUuid, String roleId) {
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, profileId, key), npcUuid,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION, null, OWNER,
                null, LOCATION, PopulationAdmissionOperation.NEW_OWNERSHIP, 1,
                PopulationAdmissionForcePolicy.ENFORCE, PopulationCompanionLifecycle.ACTIVE);
        return new PopulationAdmissionRequestV2(request, roleId, "alpha");
    }

    private static PopulationAdmissionRequestV2 change(
            OwnerPopulationRuntime runtime,
            String profileId,
            String key,
            UUID npcUuid,
            String targetRoleId) {
        long revision = runtime.index().entry(profileId).orElseThrow().revision();
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(profileId, null, key), npcUuid,
                revision, OWNER, OWNER, LOCATION, LOCATION,
                PopulationAdmissionOperation.LIFECYCLE_CHANGE, 1,
                PopulationAdmissionForcePolicy.ENFORCE, PopulationCompanionLifecycle.ACTIVE);
        return new PopulationAdmissionRequestV2(request, targetRoleId, "alpha");
    }

    private static void commit(RuntimePopulationPolicyAuthority authority,
                               PopulationAdmissionRequestV2 request) throws Exception {
        CompletionStage<PopulationAdmissionDecision> stage = authority.tryAdmitV2(request);
        assertSame(stage, authority.tryAdmitV2(request));
        PopulationAdmissionDecision reserved = stage.toCompletableFuture()
                .get(5L, TimeUnit.SECONDS);
        assertEquals(PopulationAdmissionDecision.Status.RESERVED, reserved.status());
        assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                authority.claimForApply(reserved.token()).status());
        assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                authority.commit(reserved.token()).toCompletableFuture()
                        .get(5L, TimeUnit.SECONDS).status());
    }

    private static void assertCounts(PopulationGroupRepository repository,
                                     String groupId,
                                     int committedOwned,
                                     int pendingOwned) throws Exception {
        PopulationGroupRepository.Counts counts = repository.count(
                OWNER, groupId, PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null);
        assertEquals(committedOwned, counts.committedOwned());
        assertEquals(pendingOwned, counts.pendingOwned());
    }

    private static TwPopulationGroupConfig group(
            String id, String groupId, String roleId) throws Exception {
        var constructor = TwPopulationGroupConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwPopulationGroupConfig config = constructor.newInstance();
        set(config, "id", id);
        set(config, "groupId", groupId);
        set(config, "priority", 100);
        set(config, "roleIds", new String[] {roleId});
        Object limits = field(config, "limits");
        set(limits, "maxOwnedPerOwner", 1);
        set(limits, "maxActivePerOwner", 1);
        set(limits, "scope", PopulationGroupScope.GLOBAL);
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
