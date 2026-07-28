package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable extension CAS operation identity. */
class BondedCompanionExtensionIdempotencyTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000051"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void independentProfilesDoNotCollideOnNamespaceAndRevision()
            throws Exception {
        TameworkBondedCompanionComposition composition = open();
        try {
            String first = provision(composition, "provision-first", "Ember");
            String second = provision(composition, "provision-second", "Cinder");

            var firstResult = composition.api().compareAndSetExtensionData(
                    update("extension-first", first,
                            "{\"stance\":\"guard\"}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();
            var secondResult = composition.api().compareAndSetExtensionData(
                    update("extension-second", second,
                            "{\"stance\":\"follow\"}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, firstResult.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, secondResult.code());
        } finally {
            composition.close();
        }
    }

    @Test
    void exactRetryReplaysAcrossRestartAndMismatchIsRejected()
            throws Exception {
        String profileId;
        BondedCompanionExtensionDataUpdate exact;
        TameworkBondedCompanionComposition first = open();
        try {
            profileId = provision(first, "provision-restart", "Ember");
            exact = update("extension-restart", profileId,
                    "{\"stance\":\"guard\"}",
                    BondedCompanionExtensionDataUpdate.MISSING_REVISION);
            var created = first.api().compareAndSetExtensionData(exact).join();
            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            assertEquals(0L, created.value().revision());
        } finally {
            first.close();
        }

        TameworkBondedCompanionComposition restarted = open();
        try {
            var replay = restarted.api().compareAndSetExtensionData(exact)
                    .join();
            var mismatch = restarted.api().compareAndSetExtensionData(
                    update("extension-restart", profileId,
                            "{\"stance\":\"follow\"}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, replay.code());
            assertEquals(0L, replay.value().revision());
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    mismatch.code());
        } finally {
            restarted.close();
        }
    }

    @Test
    void sameCallerIdentityCannotCrossProfileScope() throws Exception {
        TameworkBondedCompanionComposition composition = open();
        try {
            String first = provision(composition, "scope-first", "Ember");
            String second = provision(composition, "scope-second", "Cinder");
            var created = composition.api().compareAndSetExtensionData(
                    update("shared-operation", first, "{\"rank\":1}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();
            var conflictingScope = composition.api().compareAndSetExtensionData(
                    update("shared-operation", second, "{\"rank\":1}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    conflictingScope.code());
        } finally {
            composition.close();
        }
    }

    @Test
    void concurrentMissingWritesCannotOverwriteRevisionZero()
            throws Exception {
        TameworkBondedCompanionComposition composition = open();
        try {
            String profileId = provision(
                    composition, "provision-race", "Ember");
            CompletableFuture<?> first = composition.api()
                    .compareAndSetExtensionData(update(
                            "race-first", profileId, "{\"rank\":1}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION));
            CompletableFuture<?> second = composition.api()
                    .compareAndSetExtensionData(update(
                            "race-second", profileId, "{\"rank\":2}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION));

            var firstResult = (com.alechilles.alecstamework.api
                    .BondedCompanionResult<?>) first.join();
            var secondResult = (com.alechilles.alecstamework.api
                    .BondedCompanionResult<?>) second.join();
            long successes = List.of(firstResult, secondResult).stream()
                    .filter(result -> result.code()
                            == BondedCompanionResultCode.SUCCESS)
                    .count();

            assertEquals(1L, successes);
            assertTrue(List.of(firstResult, secondResult).stream()
                    .anyMatch(result -> result.code()
                            == BondedCompanionResultCode.REVISION_CONFLICT));
        } finally {
            composition.close();
        }
    }

    @Test
    void extensionWriteImmediatelyEnrichesProfileView() throws Exception {
        TameworkBondedCompanionComposition composition = open();
        try {
            String profileId = provision(
                    composition, "provision-view", "Ember");
            var update = composition.api().compareAndSetExtensionData(
                    update("extension-view", profileId,
                            "{\"stance\":\"guard\"}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION)
            ).join();
            var listed = composition.api().list(
                    OWNER, "hydragon:dragons").join();

            assertEquals(BondedCompanionResultCode.SUCCESS, update.code());
            assertEquals("{\"stance\":\"guard\"}", listed.value().get(0)
                    .snapshotPresentationData()
                    .get("extension:hydragon.combat"));
        } finally {
            composition.close();
        }
    }

    @Test
    void provisionRetryReplaysBeforeCurrentPolicyValidation()
            throws Exception {
        BondedCompanionRosterRegistry registry = rosterRegistry();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, registry, null, () -> -5_000L);
        BondedCompanionProvisionRequest exact = provisionRequest(
                "provision-policy-reload", "Ember");
        try {
            var created = composition.api().provision(exact).join();
            assertTrue(registry.replace(
                    List.of(rosterConfig()), 2L).applied());
            var replay = composition.api().provision(exact).join();
            var mismatch = composition.api().provision(provisionRequest(
                    "provision-policy-reload", "Not Ember")).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, replay.code());
            assertEquals(created.value().profileId(), replay.value().profileId());
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    mismatch.code());
        } finally {
            composition.close();
        }
    }

    private TameworkBondedCompanionComposition open() throws Exception {
        return TameworkBondedCompanionComposition.open(
                temporaryDirectory, rosterRegistry(), null, () -> -5_000L
        );
    }

    private String provision(
            TameworkBondedCompanionComposition composition,
            String idempotencyKey,
            String displayName
    ) {
        var result = composition.api().provision(provisionRequest(
                idempotencyKey, displayName)).join();
        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        return result.value().profileId();
    }

    private BondedCompanionProvisionRequest provisionRequest(
            String idempotencyKey,
            String displayName
    ) {
        return new BondedCompanionProvisionRequest(
                "test", idempotencyKey, OWNER, "hydragon:dragons",
                "Tamed_Dragon_Fire", displayName, "Dragon", "Female",
                Map.of("variant", displayName.toLowerCase())
        );
    }

    private BondedCompanionExtensionDataUpdate update(
            String idempotencyKey,
            String profileId,
            String payload,
            long expectedRevision
    ) {
        return new BondedCompanionExtensionDataUpdate(
                "hydragon",
                idempotencyKey,
                new BondedCompanionExtensionDataKey(
                        OWNER, profileId, "hydragon.combat"
                ),
                payload,
                expectedRevision
        );
    }

    private BondedCompanionRosterRegistry rosterRegistry() throws Exception {
        TwBondedCompanionRosterConfig config = rosterConfig();
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 1L).applied());
        return registry;
    }

    private TwBondedCompanionRosterConfig rosterConfig() throws Exception {
        TwBondedCompanionRosterConfig config = TwBondedCompanionRosterConfig
                .CODEC.decode(
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
        return config;
    }
}
