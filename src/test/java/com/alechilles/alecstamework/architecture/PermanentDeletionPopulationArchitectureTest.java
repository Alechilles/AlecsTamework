package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents permanent deletion paths from bypassing canonical owner-population authority. */
class PermanentDeletionPopulationArchitectureTest {
    private static final Path MAIN = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework"
    );

    @Test
    void linkedPanelCullSchedulesPermanentReleaseBeforeAnyDestructiveEffect() throws IOException {
        String handler = read("items", "CommandItemFeatureHandler.java");
        assertTrue(handler.contains("ownerCullService.cull("));
        assertFalse(handler.contains("DeathComponent.tryAddComponent"));
        assertFalse(handler.contains("removeNpcFromAllCommandToolRecords"));

        String service = read("items", "CommandOwnerCullService.java");
        int schedule = service.indexOf("scheduler.schedulePermanentRelease(");
        int applied = service.indexOf("public void onApplied", schedule);
        int clearLinks = service.indexOf("clearNpcCommandLinks(", applied);
        int clearTools = service.indexOf("removeNpcFromAllCommandToolRecords(", applied);
        int fatalDamage = service.indexOf("DeathComponent.tryAddComponent(", applied);
        assertTrue(schedule >= 0 && applied > schedule);
        assertTrue(clearLinks > applied && clearTools > applied && fatalDamage > applied);
        String beforeApplied = service.substring(0, applied);
        assertFalse(beforeApplied.contains("clearNpcCommandLinks("));
        assertFalse(beforeApplied.contains("removeNpcFromAllCommandToolRecords("));
        assertFalse(beforeApplied.contains("DeathComponent.tryAddComponent("));
        assertTrue(service.contains("\"command-cull:\" + npcUuid"));
        assertTrue(service.contains("callbacks(target.world(), playerUuid, npcUuid"));
        assertFalse(service.contains("callbacks(player, npcUuid"));
        assertTrue(service.contains("resolveLivePlayer(world, playerUuid)"));

        int denied = service.indexOf("public void onDenied", schedule);
        String deniedBody = service.substring(denied, applied);
        assertFalse(deniedBody.contains("clearNpcCommandLinks("));
        assertFalse(deniedBody.contains("removeNpcFromAllCommandToolRecords("));
        assertFalse(deniedBody.contains("DeathComponent.tryAddComponent("));

        String scheduler = read("ownership", "OwnerMutationScheduler.java");
        assertTrue(scheduler.contains("public boolean schedulePermanentRelease("));
        assertTrue(scheduler.contains("CompanionLifecycleState.RELEASED"));
        assertTrue(scheduler.contains("OwnerPopulationOperation.OWNER_CLEAR"));
        String planFactory = read("ownership", "OwnerMutationAdmissionPlanFactory.java");
        assertTrue(planFactory.contains("json.addProperty(\"permanentRelease\", true)"));
    }

    @Test
    void npcCleanChecksCanonicalOwnershipBeforeBulkRemoval() throws IOException {
        String command = read("commands", "TameworkNpcCleanCommand.java");
        int ownerGuard = command.indexOf("ownershipGuard.isProtectedOwnedCompanion(");
        int removal = command.indexOf("commandBuffer.removeEntity(");

        assertTrue(ownerGuard >= 0 && removal > ownerGuard);
        assertTrue(command.contains("plugin.getCompanionIdentityResolver()"));
        assertTrue(command.contains("plugin.getOwnerPopulationIndex()"));
        assertTrue(command.contains("ownershipGuard.readyForDestructiveCleanup()"));
        assertTrue(command.contains("Skipped \" + protectedNpcCount + \" owned companion(s)."));
    }

    @Test
    void naturalPermanentDeathIsJournaledBeforeNativeDeathApplication() throws IOException {
        String gate = read("npc", "systems", "CompanionPermanentDeathDamageGateSystem.java");
        assertTrue(gate.contains("Order.AFTER, DamageModule.get().getFilterDamageGroup()"));
        assertTrue(gate.contains("Order.BEFORE, DamageSystems.ApplyDamage.class"));
        assertTrue(gate.contains("coordinator.interceptLethalDamage("));
        assertTrue(gate.indexOf("coordinator.interceptLethalDamage(")
                < gate.indexOf("damage.setCancelled(true)"));

        String coordinator = read("ownership", "CompanionPermanentDeathCoordinator.java");
        int schedule = coordinator.indexOf("scheduler.schedulePermanentRelease(");
        int callback = coordinator.indexOf("public void onApplied", schedule);
        int nativeDeath = coordinator.indexOf("DeathComponent.tryAddComponent", callback);
        assertTrue(schedule >= 0 && callback > schedule && nativeDeath > callback);
        assertTrue(coordinator.contains("context.addProperty(\"permanentDeath\", true)"));
        assertTrue(coordinator.contains("beforeApply"));

        String fallback = read("npc", "systems", "CompanionPermanentDeathFallbackSystem.java");
        assertFalse(fallback.contains("tryRemoveComponent"));
        assertTrue(fallback.contains("Order.AFTER, NPCSystems.OnDeathSystem.class"));
        assertTrue(fallback.contains("CompanionPermanentDeathHold.create("));
        assertTrue(fallback.contains("commandBuffer.putComponent("));

        String retention = read("npc", "systems", "CompanionPermanentDeathRetentionSystem.java");
        assertTrue(retention.contains("Order.BEFORE, DeathSystems.TickCorpseRemoval.class"));
        assertTrue(retention.contains("Order.BEFORE, DeathSystems.CorpseRemoval.class"));
        assertTrue(retention.contains("isDurablyReleased(npcUuid)"));
        assertTrue(retention.contains("CompanionPermanentDeathHold.isHold(current)"));
        assertTrue(retention.contains("coordinator.interceptExistingDeath("));

        String registration = read("ownership", "reconciliation", "CompanionPopulationSystemRegistration.java");
        assertTrue(registration.contains("new CompanionPermanentDeathDamageGateSystem("));
        assertTrue(registration.contains("new CompanionPermanentDeathFallbackSystem("));
        assertTrue(registration.contains("new CompanionPermanentDeathRetentionSystem("));
    }

    private static String read(String... segments) throws IOException {
        Path path = MAIN;
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
