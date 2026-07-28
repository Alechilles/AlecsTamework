package com.alechilles.alecstamework.architecture;

import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces replacement SQLite startup resilience guardrails.
 */
class SqliteStartupResilienceGuardTest {
    private static final Path SQLITE_CONNECTION_FACTORY_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "persistence",
            "adapter",
            "sqlite",
            "SqliteConnectionFactory.java"
    );

    @Test
    void replacementConnectionsTranslateNativeLinkageErrorsAfterConstruction()
            throws IOException {
        String content = Files.readString(
                SQLITE_CONNECTION_FACTORY_PATH,
                StandardCharsets.UTF_8
        );

        assertTrue(
                content.contains("catch (LinkageError error)"),
                "Replacement SQLite connection opening must guard native linkage errors."
        );
        assertTrue(
                content.contains("throw new SQLException(\"sqlite_native_unavailable\", error);"),
                "Native SQLite linkage errors must become controlled SQL failures."
        );
        assertTrue(
                content.contains("private static void ensureDriverLoaded() throws SQLException"),
                "Replacement driver-load failures must remain SQL failures."
        );
        assertTrue(
                content.indexOf("ensureDriverLoaded();")
                        > content.indexOf("public Connection openWriterConnection()"),
                "SQLite loading must remain deferred until startup invokes a connection boundary."
        );
    }

    @Test
    void nativeLinkageFailureBecomesAControlledReadOnlyStartupReport() {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            actions.put(node, () -> CompletableFuture.completedFuture(
                    PersistenceStartupAction.Result.COMPLETE
            ));
        }
        actions.put(PersistenceStartupNode.OPEN_TARGET, () -> {
            throw new LinkageError("sqlite_native_unavailable");
        });
        PersistenceStartupCoordinator startup =
                new PersistenceStartupCoordinator(
                        PublicPersistenceFeatureRegistry.create(),
                        actions
                );

        var report = startup.advance().toCompletableFuture().join();

        assertEquals(PersistenceStartupNode.OPEN_TARGET, report.failedNode());
        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                report.readiness()
        );
        assertEquals(
                "LinkageError:sqlite_native_unavailable",
                report.detail()
        );
    }
}
