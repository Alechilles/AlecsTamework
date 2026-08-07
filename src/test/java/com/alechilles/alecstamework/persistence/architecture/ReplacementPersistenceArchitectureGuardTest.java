package com.alechilles.alecstamework.persistence.architecture;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable boundaries that keep the replacement persistence core small and composable. */
class ReplacementPersistenceArchitectureGuardTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework"
    );
    private static final Path SQLITE = MAIN.resolve("persistence/adapter/sqlite");
    private static final List<Path> REPLACEMENT_ROOTS = List.of(
            MAIN.resolve("companion/command"),
            MAIN.resolve("companion/extension"),
            MAIN.resolve("companion/identity"),
            MAIN.resolve("companion/lifecycle"),
            MAIN.resolve("companion/population"),
            MAIN.resolve("companion/profile"),
            MAIN.resolve("companion/provisioning"),
            MAIN.resolve("companion/snapshot"),
            MAIN.resolve("persistence/adapter/sqlite"),
            MAIN.resolve("persistence/facade"),
            MAIN.resolve("persistence/kernel"),
            MAIN.resolve("persistence/operation"),
            MAIN.resolve("persistence/projection"),
            MAIN.resolve("persistence/runtime")
    );

    @Test
    void transactionLocalStoresNeverOwnConnectionsCommitsThreadsOrProjectionCallbacks()
            throws Exception {
        List<String> forbidden = List.of(
                "openWriterConnection(",
                "openReadConnection(",
                ".commit(",
                ".rollback(",
                "new Thread(",
                "ExecutorService",
                "ProjectionConsumer consumer",
                "ProjectionCoordinator coordinator"
        );
        ArrayList<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SQLITE)) {
            if (!file.getFileName().toString().endsWith("Store.java")) {
                continue;
            }
            String source = Files.readString(file);
            for (String token : forbidden) {
                if (source.contains(token)) {
                    violations.add(relative(file) + " contains " + token);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void lifecycleHasOneSqlMutationPathAndOutboxHasNoCompactionPath() throws Exception {
        int lifecycleUpdates = 0;
        ArrayList<String> outboxDeletes = new ArrayList<>();
        for (Path root : REPLACEMENT_ROOTS) {
            for (Path file : javaFiles(root)) {
                String source = Files.readString(file);
                lifecycleUpdates += occurrences(source, "UPDATE companion_lifecycle");
                if (source.contains("DELETE FROM projection_outbox")) {
                    outboxDeletes.add(relative(file));
                }
            }
        }
        assertEquals(1, lifecycleUpdates, "Canonical lifecycle must have one update statement");
        assertTrue(outboxDeletes.isEmpty(),
                () -> "Outbox compaction is not proven: " + outboxDeletes);
    }

    @Test
    void durableWorkCannotReceiveLiveOrProjectionCapabilities() throws Exception {
        String work = Files.readString(
                MAIN.resolve("persistence/operation/DurableOperationWork.java")
        );
        assertTrue(work.contains("SqlitePersistenceTransactionContext transaction"));
        assertTrue(work.contains("OperationEnvelope operation"));
        assertFalse(work.contains("ProjectionConsumer"));
        assertFalse(work.contains("ProjectionCoordinator"));
        assertFalse(work.contains("Connection connection"));

        String preparedDetail = Files.readString(
                MAIN.resolve("persistence/operation/PreparedOperationDetail.java")
        );
        assertTrue(preparedDetail.contains(
                "SqlitePersistenceTransactionContext transaction"
        ));
        assertFalse(preparedDetail.contains("ProjectionConsumer"));
        assertFalse(preparedDetail.contains("ProjectionCoordinator"));
        assertFalse(preparedDetail.contains("Connection connection"));

        String coordinator = Files.readString(
                MAIN.resolve("persistence/projection/ProjectionCoordinator.java")
        );
        assertFalse(coordinator.contains("SqlitePersistenceTransactionContext"));
        assertFalse(coordinator.contains("SqliteProjectionOutboxStore"));
    }

    @Test
    void replacementCoreDoesNotDependOnSupersededSqlitePackage() throws Exception {
        ArrayList<String> violations = new ArrayList<>();
        for (Path root : REPLACEMENT_ROOTS) {
            for (Path file : javaFiles(root)) {
                String source = Files.readString(file);
                if (source.contains(
                        "com.alechilles.alecstamework.persistence.sqlite"
                )) {
                    violations.add(relative(file));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Replacement core imports superseded persistence: " + violations);
    }

    @Test
    void replacementCoreClassesRemainBelowTheProjectComplexityTarget() throws Exception {
        ArrayList<String> violations = new ArrayList<>();
        for (Path root : REPLACEMENT_ROOTS) {
            for (Path file : javaFiles(root)) {
                long lines;
                try (Stream<String> stream = Files.lines(file)) {
                    lines = stream.count();
                }
                if (lines > 500) {
                    violations.add(relative(file) + " has " + lines + " lines");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void transactionContextKeepsExactlySixPublicAuthorityAccessors() throws Exception {
        Class<?> context = Class.forName(
                "com.alechilles.alecstamework.persistence.adapter.sqlite"
                        + ".SqlitePersistenceTransactionContext"
        );
        List<String> accessors = Stream.of(context.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertEquals(
                List.of(
                        "identities",
                        "incidents",
                        "lifecycles",
                        "operations",
                        "outbox",
                        "snapshots"
                ),
                accessors
        );
    }

    @Test
    void publicRuntimeFacadeDeclaresOnlyRegisteredReplacementOperations() {
        List<String> operations = Stream.of(
                        com.alechilles.alecstamework.persistence.runtime
                                .PublicPersistenceOperations.class
                                .getDeclaredMethods()
                )
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertEquals(
                List.of(
                        "activateProvisionedCompanion",
                        "assignPopulationGroups",
                        "capture",
                        "captureToCoop",
                        "makeDormant",
                        "mutateCommandRoster",
                        "mutateExtension",
                        "mutateProfile",
                        "mutateTimedSummonLease",
                        "provisionCompanion",
                        "reconcileOwnerPopulation",
                        "registerCoopSlot",
                        "releaseCapturedCompanion",
                        "releaseFromCoop",
                        "restore",
                        "reviveCompanion",
                        "rotateAlias",
                        "transitionCommandRoster",
                        "transitionOwnerPopulation",
                        "transitionTimedSummon"
                ),
                operations
        );
    }

    @Test
    void aliasRotationIsDatabaseLocalAndNotAnExternalBoundary()
            throws Exception {
        List<String> boundaries = Stream.of(
                        com.alechilles.alecstamework.persistence.runtime
                                .PublicPersistenceLiveBoundaries.class
                                .getRecordComponents()
                )
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertEquals(
                List.of(
                        "captures",
                        "capturedReleases",
                        "restorations",
                        "coopCaptures",
                        "coopReleases",
                        "timedSummons",
                        "provisioningActivations",
                        "paidRevivals"
                ),
                boundaries
        );

        String aliases = Files.readString(
                SQLITE.resolve(
                        "SqliteCompanionAliasRotationOperations.java"
                )
        );
        assertTrue(aliases.contains(
                "SqliteDatabaseOperationCoordinator coordinator"
        ));
        assertFalse(aliases.contains("SqliteLiveOperationCoordinator"));
        assertFalse(aliases.contains("CompanionAliasLiveBoundary"));
    }

    @Test
    void productionCutoverHasNoRuntimeSelectorOrLegacyLaunchMode()
            throws Exception {
        Path runtime = MAIN.resolve("persistence/runtime");
        for (String removed : List.of(
                "PersistenceEngineMode.java",
                "PersistenceEngineSelection.java",
                "PersistenceEngineSelector.java"
        )) {
            assertFalse(
                    Files.exists(runtime.resolve(removed)),
                    removed + " would recreate a second production runtime path"
            );
        }
        String status = Files.readString(
                runtime.resolve("PublicPersistenceOperationalStatus.java")
        );
        assertTrue(status.contains("PersistenceEngineLineage engine"));
        assertFalse(status.contains("tamework.persistence.engine"));
    }

    @Test
    void publicDiagnosticsDeclaresOnlySupportedReplacementContracts()
            throws Exception {
        List<String> methods = Stream.of(
                        com.alechilles.alecstamework.api.DiagnosticsApi.class
                                .getDeclaredMethods()
                )
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();
        assertEquals(
                List.of(
                        "findPersistenceIncident",
                        "getPersistenceDiagnostics",
                        "getPersistenceResilience",
                        "getPopulationDiagnostics",
                        "queryPersistenceAvailability"
                ),
                methods
        );
        assertTrue(
                javaFiles(MAIN.resolve("persistence/health")).isEmpty(),
                "The retired health package must contain no production code"
        );

        String adapter = Files.readString(MAIN.resolve(
                "persistence/facade/ReplacementPersistenceDiagnosticsApi.java"
        ));
        for (String replacementAuthority : List.of(
                "queryPersistenceAvailability",
                "getPersistenceResilience",
                "findPersistenceIncident"
        )) {
            assertTrue(
                    adapter.contains(replacementAuthority),
                    replacementAuthority
            );
        }
    }

    @Test
    void publicQueryFacadeDeclaresOnlyValueOrReadResultQueries() {
        List<String> queries = Stream.of(
                        com.alechilles.alecstamework.persistence.runtime
                                .PublicPersistenceQueries.class
                                .getDeclaredMethods()
                )
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();

        assertEquals(
                List.of(
                        "activeCaptureFailureCooldown",
                        "diagnoseCoopCapture",
                        "diagnoseCoopRelease",
                        "findAllCommandRosters",
                        "findAllLifecycles",
                        "findAllPopulationGroupAssignments",
                        "findAllProvisioningRecords",
                        "findCommandRoster",
                        "findCommandRosterMembership",
                        "findCoopResidency",
                        "findCoopSlot",
                        "findExtension",
                        "findExtensions",
                        "findFirstActiveQuarantine",
                        "findIncidentEvidence",
                        "findOperation",
                        "findProfile",
                        "findProvisioning",
                        "findSnapshotHistory",
                        "findStalePopulationGroupProfiles",
                        "findTimedSummonLease",
                        "projectedCommandRosterActions",
                        "projectedCommandRosterRevisions",
                        "projectedCoopResidency",
                        "projectedCoopSnapshot",
                        "projectedExtension",
                        "projectedExtensions",
                        "projectedLaggingCommandRosterProfiles",
                        "projectedLaggingPopulationGroupProfiles",
                        "projectedLaggingTimedSummonProfiles",
                        "projectedOwnerPopulationCount",
                        "projectedOwnerPopulationSnapshot",
                        "projectedPopulationGroupAssignments",
                        "projectedPopulationGroupCounts",
                        "projectedProfile",
                        "projectedProfileSnapshot",
                        "projectedProvisioning",
                        "projectedProvisioningSnapshot",
                        "projectedTimedSummons"
                ),
                queries
        );
    }

    @Test
    void replacementPersistenceDoesNotEnterPerTickSystemClasses()
            throws Exception {
        ArrayList<String> violations = new ArrayList<>();
        List<String> forbiddenBlockingOrAuthorityUse = List.of(
                ".toCompletableFuture().join(",
                "Thread.sleep(",
                "LockSupport.park",
                "CountDownLatch",
                ".await(",
                "java.sql.",
                "DataSource",
                "new PersistenceBootstrap(",
                "new PublicPersistence",
                "new Sqlite",
                "facades.operations(",
                "facades.queries("
        );
        for (Path file : javaFiles(MAIN)) {
            if (!file.getFileName().toString().endsWith("System.java")) {
                continue;
            }
            String source = Files.readString(file);
            if (source.contains(
                    "com.alechilles.alecstamework.persistence.runtime"
            ) || source.contains(
                    "com.alechilles.alecstamework.persistence.adapter"
            ) || source.contains(
                    "com.alechilles.alecstamework.persistence.kernel"
            )) {
                violations.add(
                        relative(file) + " imports persistence authority"
                );
            }
            int facadeReferences = occurrences(
                    source, "PersistenceDomainFacades"
            );
            if (facadeReferences != 0) {
                violations.add(
                        relative(file) + " references the composition facade"
                );
            }
            for (String token : forbiddenBlockingOrAuthorityUse) {
                if (source.contains(token)) {
                    violations.add(
                            relative(file) + " contains " + token
                    );
                }
            }
        }
        assertTrue(
                violations.isEmpty(),
                () -> "Tick systems cannot receive persistence composition,"
                        + " storage access, or blocking collaborators: "
                        + violations
        );
    }

    @Test
    void tickProjectionCollaboratorOnlyReadsRebuildableSnapshots()
            throws Exception {
        String projections = Files.readString(
                MAIN.resolve(
                        "items/coop/DirectLiveCoopProjectionView.java"
                )
        );
        assertTrue(projections.contains(
                "facades.queries().projectedCoopSnapshot()"
        ));
        assertTrue(projections.contains(
                "facades.queries().projectedProfileSnapshot()"
        ));
        for (String forbidden : List.of(
                "facades.operations()",
                "findProfile(",
                "findCoop",
                "CompletionStage",
                ".join(",
                ".get("
        )) {
            assertFalse(projections.contains(forbidden), forbidden);
        }
    }

    @Test
    void publicApiCapabilityActivationDependsOnInterfaces() throws Exception {
        String api = Files.readString(
                MAIN.resolve("api/internal/TameworkApiImpl.java")
        );
        assertTrue(
                com.alechilles.alecstamework.api.ProfileDataApi.class
                        .isInterface()
        );
        assertTrue(api.contains(
                "@Nonnull ProfileDataApi profileDataApi"
        ));
        assertTrue(api.contains(
                "capabilities.add("
                        + "TameworkApiCapability.PROFILE_DATA_TRANSACTIONS)"
        ));
        assertFalse(api.contains(
                "@Nonnull ReplacementProfileDataApi profileDataApi"
        ));
        assertFalse(api.contains(
                "com.alechilles.alecstamework.persistence.facade"
        ));
        assertFalse(api.contains(
                "activateCompanionProvisioningRuntime"
        ));
        assertFalse(api.contains(
                "activatePopulationGroupRuntime"
        ));
    }

    private List<Path> javaFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private int occurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private String relative(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }
}
