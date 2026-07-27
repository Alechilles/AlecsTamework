package com.alechilles.alecstamework.companion.bonded.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards player-attachment ordering for retained revival-payment recovery. */
class BondedCompanionPaymentRecoveryArchitectureTest {
    @Test
    void recoveryStartsFromThePostAttachPlayerSystemAndDefersByUuid()
            throws Exception {
        String system = read("src/main/java/com/alechilles/alecstamework/"
                + "companion/bonded/runtime/"
                + "BondedCompanionPaymentRecoverySystem.java");
        String recovery = read("src/main/java/com/alechilles/alecstamework/"
                + "items/HytaleBondedCompanionPaymentRecovery.java");
        String composition = read("src/main/java/com/alechilles/"
                + "alecstamework/TameworkBondedCompanionComposition.java");
        String plugin = read(
                "src/main/java/com/alechilles/alecstamework/Tamework.java");

        assertTrue(system.contains("extends RefSystem<EntityStore>"));
        assertTrue(system.contains("Player.getComponentType()"));
        assertTrue(system.contains(
                "composition.onPlayerPaymentReady(world, player.getUuid())"));
        assertTrue(recovery.contains(
                "world.execute(() -> recover(world, ownerUuid))"));
        assertFalse(recovery.contains("PlayerRef"));
        assertFalse(composition.substring(
                composition.indexOf("public void onPlayerAdded(\n"),
                composition.indexOf("public void onPlayerPaymentReady("))
                .contains("paymentRecovery"));
        assertTrue(plugin.indexOf("new BondedCompanionPaymentRecoverySystem(")
                > plugin.indexOf("new BondedCompanionMaintenanceSystem("));
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
