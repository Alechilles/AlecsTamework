package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.sqlite.LegacyNpcProfilesApi;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileDataTransactionsApiIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void advertisesAndMapsDurableTransactionAuthority() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, UUID.randomUUID(), "Owner", "Mob_Test", "Display", null,
                    true, null, null, null, new String[0])));
            assertTrue(awaitUntil(() -> runtime.getNpcProfileRepository().resolveProfileId(npcUuid) != null));
            String profileId = runtime.getNpcProfileRepository().resolveProfileId(npcUuid);
            TameworkApi api = LegacyTameworkApiFactory.create(
                    runtime,
                    new TameworkEventBus(null),
                    null,
                    new InteractionExtensionRegistry(null),
                    new TraitEffectRegistry(
                            null,
                            new LegacyNpcProfilesApi(
                                    runtime.getNpcProfileRepository()
                            )
                    ));

            assertTrue(api.getCapabilities().contains(
                    TameworkApiCapability.PROFILE_DATA_TRANSACTIONS));
            ProfileDataCompareAndSetResult committed = api.profileData().compareAndSet(
                            new ProfileDataCompareAndSetRequest(
                                    profileId, "Alechilles:HyDragon", "state", 0L,
                                    "soul-bond:1", "{\"kind\":\"fire\"}"))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ProfileDataCompareAndSetResult.Status.COMMITTED, committed.status());
            assertEquals(1L, api.profileData().getVersioned(
                    profileId, "Alechilles:HyDragon", "state").orElseThrow().revision());
            assertEquals(ProfileDataOperationStatus.COMMITTED,
                    api.profileData().findOperation("Alechilles:HyDragon", "soul-bond:1")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS)
                            .orElseThrow().status());
        }
    }

    private boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }
}
