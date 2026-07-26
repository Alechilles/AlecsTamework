package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.runtime
        .HytaleBondedCompanionWorldGateway;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the isolated bonded runtime, publication order, and teardown seam. */
class BondedCompanionCompositionTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000005"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsAndServesReadsWithoutAnyGenericPersistenceRuntime() {
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> -5_000L
        );
        try {
            assertTrue(composition.api().availability().available());
            var listed = composition.api().list(OWNER, "hydragon:dragons")
                    .join();
            assertEquals(BondedCompanionResultCode.SUCCESS, listed.code());
            assertEquals(0, listed.value().size());
            assertEquals("READY", composition.diagnostics().snapshot().readiness());
            assertEquals(
                    BondedCompanionSchemaManager.VERSION,
                    composition.diagnostics().snapshot().schemaVersion()
            );
        } finally {
            composition.close();
        }

        assertFalse(composition.api().availability().available());
        assertEquals("CLOSED", composition.diagnostics().snapshot().readiness());
        composition.close();
    }

    @Test
    void captureAndPanelAbsenceDoesNotDisableCoreBondedAuthority() {
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> -5_000L
        );
        try {
            assertTrue(composition.api().availability().available());
            assertEquals(BondedCompanionResultCode.SUCCESS,
                    composition.api().list(OWNER, "hydragon:dragons")
                            .join().code());
        } finally {
            composition.close();
        }
    }

    @Test
    void provisionAndExtensionMutationsAreRealIdempotentAndPublished()
            throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosters, null, () -> -5_000L
                );
        AtomicInteger changes = new AtomicInteger();
        AutoCloseable subscription = composition.api().subscribe(
                ignored -> changes.incrementAndGet()
        );
        try {
            BondedCompanionProvisionRequest request =
                    new BondedCompanionProvisionRequest(
                            "test", "provision-1", OWNER,
                            "hydragon:dragons", "Tamed_Dragon_Fire",
                            "Ember", "Dragon", "Female",
                            Map.of("variant", "ember"), 1L
                    );

            var created = composition.api().provision(request).join();
            var replay = composition.api().provision(request).join();
            var conflictingReplay = composition.api().provision(
                    new BondedCompanionProvisionRequest(
                            "test", "provision-1", OWNER,
                            "hydragon:dragons", "Tamed_Dragon_Fire",
                            "Not Ember", "Dragon", "Female",
                            Map.of("variant", "ember"), 1L
                    )
            ).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, replay.code());
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    conflictingReplay.code());
            assertEquals(created.value().profileId(), replay.value().profileId());
            assertEquals(1, changes.get());

            BondedCompanionExtensionDataKey key =
                    new BondedCompanionExtensionDataKey(
                            OWNER, created.value().profileId(), "hydragon.combat"
                    );
            var extension = composition.api().compareAndSetExtensionData(
                    new BondedCompanionExtensionDataUpdate(
                            key, "{\"stance\":\"guard\"}", 0L
                    )
            ).join();
            assertEquals(BondedCompanionResultCode.SUCCESS, extension.code());
            assertEquals(extension.value(), composition.api()
                    .getExtensionData(key).join().value());
        } finally {
            subscription.close();
            composition.close();
        }
    }

    @Test
    void listenerFailureIsIsolatedAndUnknownWorldOutcomeIsNotPublished()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        AtomicInteger delivered = new AtomicInteger();
        publisher.subscribe(ignored -> {
            throw new IllegalStateException("listener-canary");
        });
        publisher.subscribe(ignored -> delivered.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-canary", OWNER, "hydragon:dragons",
                BondedCompanionState.STORED, BondedCompanionState.ACTIVE,
                4L, "summoned"
        );

        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.UNKNOWN
        ));
        assertEquals(0, delivered.get());
        assertTrue(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        assertEquals(1, delivered.get());

        publisher.close();
        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.NOT_REQUIRED
        ));
        assertEquals(1, delivered.get());
    }

    @Test
    void closeCannotRaceAListenerIntoTheClosedPublisher() throws Exception {
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null, () -> {
                    registrationEntered.countDown();
                    try {
                        assertTrue(allowRegistration.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
        Thread subscriber = new Thread(() -> publisher.subscribe(ignored -> { }));

        subscriber.start();
        assertTrue(registrationEntered.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(publisher::close);
        closer.start();
        allowRegistration.countDown();
        subscriber.join(5_000L);
        closer.join(5_000L);

        assertEquals(0, publisher.listenerCount());
        assertFalse(subscriber.isAlive());
        assertFalse(closer.isAlive());
    }

    @Test
    void closeWaitsForConcurrentPublishAndStartsNoLaterListener()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch allowListenerReturn = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger laterDeliveries = new AtomicInteger();
        publisher.subscribe(ignored -> {
            listenerEntered.countDown();
            try {
                assertTrue(allowListenerReturn.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        publisher.subscribe(ignored -> laterDeliveries.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-close-race", OWNER, "hydragon:dragons",
                BondedCompanionState.STORED, BondedCompanionState.ACTIVE,
                5L, "summoned"
        );

        Thread publishing = new Thread(() -> publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        publishing.start();
        assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));
        Thread closing = new Thread(() -> {
            publisher.close();
            closeReturned.countDown();
        });
        closing.start();

        assertFalse(closeReturned.await(250L, TimeUnit.MILLISECONDS));
        allowListenerReturn.countDown();
        publishing.join(5_000L);
        closing.join(5_000L);

        assertFalse(publishing.isAlive());
        assertFalse(closing.isAlive());
        assertEquals(0, laterDeliveries.get());
    }

    @Test
    void listenerCanClosePublisherWithoutDeadlockOrLaterDelivery()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        CountDownLatch listenerClosed = new CountDownLatch(1);
        AtomicInteger laterDeliveries = new AtomicInteger();
        publisher.subscribe(ignored -> {
            publisher.close();
            listenerClosed.countDown();
        });
        publisher.subscribe(ignored -> laterDeliveries.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-reentrant-close", OWNER, "hydragon:dragons",
                BondedCompanionState.STORED, BondedCompanionState.ACTIVE,
                6L, "summoned"
        );

        Thread publishing = new Thread(() -> publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        publishing.start();
        publishing.join(1_000L);

        assertFalse(publishing.isAlive());
        assertTrue(listenerClosed.await(1L, TimeUnit.SECONDS));
        assertEquals(0, laterDeliveries.get());
        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
    }

    @Test
    void cleanupIdentityRequiresExactWorldUuidProfileKindAndLeaseToken() {
        UUID target = UUID.fromString(
                "20000000-0000-0000-0000-000000000005"
        );
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-1", OWNER, "hydragon:dragons", "profile-1",
                        "lease-1", target, "world-a", "store", -1L
                );
        TameworkProjectionIdentityComponent exact =
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-1", "lease-1"
                );

        assertTrue(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target, exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-b", target, exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", UUID.randomUUID(), exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target,
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-1", "replacement-lease"
                )
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target,
                new TameworkProjectionIdentityComponent(
                        "profile-1", "lease-1",
                        TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                        null, null, 0L
                )
        ));
    }

    private BondedCompanionRosterRegistry rosterRegistry() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:dragons",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                                  "MaximumOwned": 4,
                                  "MaximumActive": 1,
                                  "Features": {"Provision": true}
                                }
                                """),
                        new ExtraInfo()
                );
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HydragonDragons");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 1L).applied());
        return registry;
    }
}
