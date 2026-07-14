package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LostRecoveryPreparedAliasArchitectureTest {
    @Test
    void recoveryReservesPlannedAliasBeforeProjectionSpawn() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLostRecoveryCoordinator.java"));
        int acquire = source.indexOf("aliasLease.acquire()");
        int spawn = source.indexOf("projectionSpawner.spawn(", acquire);
        int release = source.indexOf("aliasLease.releaseBeforeVisibility()", spawn);

        assertTrue(acquire >= 0, "recovery must acquire a prepared identity alias");
        assertTrue(spawn > acquire, "the prepared alias must be visible before entity spawn");
        assertTrue(release > spawn, "failed spawn must release the prepared alias");
    }

    @Test
    void productionHandlerReceivesThePopulationIdentityResolver() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        int handler = source.indexOf("commandItemFeatureHandler = new CommandItemFeatureHandler(");
        int resolver = source.indexOf("ownerPopulationRuntime.identityResolver()", handler);

        assertTrue(handler >= 0 && resolver > handler,
                "lost recovery must share the live population identity resolver");
    }
}
