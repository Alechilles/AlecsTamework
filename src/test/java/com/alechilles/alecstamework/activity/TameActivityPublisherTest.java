package com.alechilles.alecstamework.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptPublicEventMapper;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolutionEventCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.persistence.facade.ReplacementPublicSemanticEventProjection;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Behavior checks for committed tame activity publication. */
class TameActivityPublisherTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    @Test
    void publishesMappedTameOutcomeAndIgnoresUnmanagedRoles() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        TameActivityPublisher publisher = new TameActivityPublisher(
                published::add, managedRegistry());
        UUID operationId = UUID.randomUUID();

        publisher.publish(operationId, "RoleA", OWNER, COMPANION);
        publisher.publish(UUID.randomUUID(), "UnmanagedRole", OWNER, COMPANION);

        assertEquals(1, published.size());
        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals(operationId, activity.header().operationId());
        assertEquals(ActivityIds.TAME_SUCCESS, activity.header().actionId());
        assertEquals("runeteria:husbandry", activity.profileId());
        assertEquals(Set.of("runeteria:family"), activity.groupIds());
        assertEquals("RoleA", activity.roleId());
        assertEquals(OWNER, activity.ownerId());
        assertEquals(COMPANION, activity.companionId());
        assertEquals("runeteria:tame_success", activity.mappedActivityId());
    }

    @Test
    void committedCaptureAndTameUsesTheDurableOperationAndTargetAlias() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());
        try {
            CaptureAttemptPublicEventMapper.publishTameActivity(
                    CaptureAttemptResolvedEvent.complete(
                            CaptureTameAndLinkTestFixtures.OPERATION,
                            new IdempotencyKey("capture-tame-activity"),
                            CaptureTameAndLinkTestFixtures.request(),
                            CaptureTameAndLinkTestFixtures.NOW
                    )
            );
        } finally {
            ActivityRuntime.clear();
        }

        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals(CaptureTameAndLinkTestFixtures.OPERATION.value(),
                activity.header().operationId());
        assertEquals(CaptureTameAndLinkTestFixtures.OWNER.value(),
                activity.ownerId());
        assertEquals(CaptureTameAndLinkTestFixtures.ALIAS.value(),
                activity.companionId());
        assertEquals("Tamed_Dragon_Fire", activity.roleId());
    }

    @Test
    void captureProjectionPublishesTameOnlyForLiveCommit() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());
        try {
            ProjectionEvent event = captureEvent();
            new ReplacementPublicSemanticEventProjection(
                    ignored -> { }, () -> CaptureTameAndLinkTestFixtures.NOW
            ).apply(event, ProjectionPublicationContext.LIVE_COMMIT);
            assertEquals(1, published.size());

            published.clear();
            new ReplacementPublicSemanticEventProjection(
                    ignored -> { }, () -> CaptureTameAndLinkTestFixtures.NOW
            ).apply(event, ProjectionPublicationContext.RECOVERY_CONVERGENCE);
            assertTrue(published.isEmpty());
        } finally {
            ActivityRuntime.clear();
        }
    }

    private static ProjectionEvent captureEvent() {
        var request = CaptureTameAndLinkTestFixtures.request();
        long resolvedAtMs = CaptureTameAndLinkTestFixtures.NOW;
        return new ProjectionEvent(
                new ProjectionSequence(1),
                CaptureTameAndLinkTestFixtures.OPERATION,
                CaptureAttemptResolutionEventCodec.EVENT_TYPE,
                "capture-attempt:" + request.resolution().attemptId(),
                1,
                CaptureAttemptResolutionEventCodec.VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        CaptureTameAndLinkTestFixtures.OPERATION,
                        new IdempotencyKey("capture-tame-projection"),
                        request,
                        resolvedAtMs
                ),
                resolvedAtMs
        );
    }

    private static ManagedActivityConfigRegistry managedRegistry() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group(
                        "group",
                        "runeteria:family",
                        "RoleA",
                        "Tamed_Dragon_Fire"
                )), 1L
        ).applied());
        ManagedActivityConfigRegistry managed = new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig profile = decode("husbandry", """
                {
                  "ProfileId":"runeteria:husbandry",
                  "ProviderId":"runeteria:provider",
                  "ProviderContractVersion":1,
                  "RequiredCapabilities":["ACTIVITY_FEED_V2"],
                  "Domains":[{"DomainId":"runeteria:owned","Owned":true}],
                  "Families":[{"GroupId":"runeteria:family","GateKey":"runeteria:gate","Weight":1}],
                  "Activities":{
                    "Feed":"runeteria:feed",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed",
                    "TameSuccess":"runeteria:tame_success",
                    "NeedSatisfied":"runeteria:feed"
                  }
                }
                """);
        assertTrue(managed.replace(List.of(profile), 1L).applied());
        return managed;
    }

    private static TwManagedActivityConfig decode(String id, String json) throws Exception {
        TwManagedActivityConfig config = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse(json), new ExtraInfo());
        set(config, "id", id);
        return config;
    }

    private static TwPopulationGroupConfig group(
            String id,
            String groupId,
            String... roleIds
    )
            throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"GroupId":"%s","RoleIds":%s}
                        """.formatted(
                        groupId,
                        java.util.Arrays.stream(roleIds)
                                .map(roleId -> "\"" + roleId + "\"")
                                .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                )), new ExtraInfo());
        set(config, "id", id);
        set(config, "limits", new TwPopulationGroupConfig.LimitSettings());
        set(field(config, "limits"), "scope", PopulationGroupScope.GLOBAL);
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
