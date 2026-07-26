package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.runtime
        .HytaleBondedCompanionWorldGateway;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import java.nio.file.Path;
import java.util.UUID;
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
}
