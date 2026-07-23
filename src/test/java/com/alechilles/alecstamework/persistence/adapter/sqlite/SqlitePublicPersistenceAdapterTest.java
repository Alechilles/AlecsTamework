package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Registry-driven composition checks for the complete public SQLite adapter. */
class SqlitePublicPersistenceAdapterTest {
    @TempDir
    Path tempDir;

    private SqlitePersistenceKernel kernel;

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void composesAllOperationsAndExactRegistryConsumers() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        kernel = new SqlitePersistenceKernel(connections);
        SqlitePublicPersistenceAdapter adapter =
                new SqlitePublicPersistenceAdapter(
                        PublicPersistenceFeatureRegistry.create(),
                        kernel,
                        PersistenceOperationAdmissionGate.allowAll(),
                        () -> -100,
                        (claim, operation) ->
                                LiveOperationResult.confirmed(
                                        "test_refund"
                                ).completed(),
                        event -> {
                        }
                );

        assertNotNull(adapter.profileOperations());
        assertNotNull(adapter.aliasOperations());
        assertNotNull(adapter.captureOperations());
        assertNotNull(adapter.dormantOperations());
        assertNotNull(adapter.restorationOperations());
        assertNotNull(adapter.coopSlotOperations());
        assertNotNull(adapter.coopCaptureOperations());
        assertNotNull(adapter.coopReleaseOperations());
        assertNotNull(adapter.extensionOperations());
        assertNotNull(adapter.profileReader());
        assertNotNull(adapter.coopReader());
        assertNotNull(adapter.extensionReader());
        assertNotNull(adapter.coopIndex());
        assertNotSame(
                adapter.publicOperations().engine(),
                adapter.recoveryOperations().engine()
        );
        assertEquals(
                1,
                adapter.projections().requiredFor(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                2,
                adapter.projections().requiredFor(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                0,
                adapter.projections().requiredFor(
                        ProfileExtensionMutationDefinition.INSTANCE.kind()
                ).size()
        );
    }
}
