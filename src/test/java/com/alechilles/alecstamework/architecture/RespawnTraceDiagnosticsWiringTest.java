package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Guards command and default-config wiring for respawn trace diagnostics.
 */
class RespawnTraceDiagnosticsWiringTest {
    private static final Path COMMAND_ROOT = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "commands", "TameworkCommandRoot.java"
    );
    private static final Path DEFAULT_DEBUG_CONFIG = Paths.get(
            "src", "main", "resources", "Server", "Tamework", "Debug", "TwDebugDefault.json"
    );
    private static final Path TAMEWORK_PLUGIN = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
    );
    private static final Path DAMAGE_TARGET_MEMORY_SYSTEM = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "damage", "DamageTargetMemorySystem.java"
    );
    private static final Path RESPAWN_FALL_GRACE_SYSTEM = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "damage", "RespawnFallDamageGraceSystem.java"
    );
    private static final Path RESPAWN_SERVICE = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "items", "CommandRespawnService.java"
    );
    private static final Path LOST_RECOVERY_COORDINATOR = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "items", "CommandLostRecoveryCoordinator.java"
    );
    private static final Path SPAWN_PHYSICS_RESET_SERVICE = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "items", "CommandCompanionSpawnPhysicsResetService.java"
    );
    private static final Path BREEDING_OFFSPRING_SERVICE = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "BreedingOffspringService.java"
    );
    private static final Path DEATH_SERVICE = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "items", "CommandLinkedNpcDeathService.java"
    );

    @Test
    void rootCommandRegistersRespawnTraceDebugCommand() throws IOException {
        String content = Files.readString(COMMAND_ROOT, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("addSubCommand(new TameworkDebugRespawnTraceCommand());"),
                "/tw must register the debugrespawntrace command."
        );
    }

    @Test
    void defaultDebugConfigKeepsRespawnTraceDisabled() throws IOException {
        String content = Files.readString(DEFAULT_DEBUG_CONFIG, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("\"RespawnTrace\": false"),
                "Respawn trace diagnostics must be available but disabled by default."
        );
    }

    @Test
    void damageTraceCapturesNonEntitySourcesBeforeEntitySourceFilter() throws IOException {
        String content = Files.readString(DAMAGE_TARGET_MEMORY_SYSTEM, StandardCharsets.UTF_8);
        int nonEntityTrace = content.indexOf("recordRespawnTraceDamage(victimUuid, victimRef, store, null, damage);");
        int entitySourceFilter = content.indexOf("if (!(source instanceof Damage.EntitySource entitySource))");

        assertTrue(nonEntityTrace >= 0, "Respawn trace diagnostics must log non-entity damage sources.");
        assertTrue(entitySourceFilter >= 0, "Damage system must still filter entity attackers for attacker memory.");
        assertTrue(
                nonEntityTrace > entitySourceFilter,
                "The non-entity trace hook must live in the non-entity branch before returning."
        );
    }

    @Test
    void deathTraceLogsDeathComponentInfo() throws IOException {
        String content = Files.readString(DEATH_SERVICE, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("deathInfo=\" + describeDeathComponent(reference, store)"),
                "Respawn death diagnostics must include DeathComponent death info."
        );
    }

    @Test
    void respawnFallDamageGraceFilterIsRegistered() throws IOException {
        String content = Files.readString(TAMEWORK_PLUGIN, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("new RespawnFallDamageGraceSystem()"),
                "Tamework must register the respawn fall-damage grace filter."
        );
    }

    @Test
    void respawnFallDamageGraceFilterCancelsOnlyRecentFallDamage() throws IOException {
        String content = Files.readString(RESPAWN_FALL_GRACE_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("DamageModule.get().getFilterDamageGroup()"),
                "Respawn fall damage grace must run before damage is applied."
        );
        assertTrue(content.contains("FALL_CAUSE_ID = \"Fall\""), "Only fall damage should be matched.");
        assertTrue(content.contains("FALL_DAMAGE_GRACE_MS = 2_000L"), "Grace window should stay short.");
        assertTrue(content.contains("damage.setCancelled(true)"), "Matched fall damage must be cancelled.");
        assertTrue(
                content.contains("fall_damage_grace_cancelled"),
                "Cancelled fall damage should be visible in respawn trace logs when diagnostics are enabled."
        );
    }

    @Test
    void respawnReplacementClearsSpawnPhysicsBeforeTraceProbes() throws IOException {
        String content = Files.readString(RESPAWN_SERVICE, StandardCharsets.UTF_8);
        assertTrue(
                content.contains("RecentRespawnTraceService.Trace respawnTrace =")
                        && content.contains("RespawnTraceLogSupport.startTrace(traceBranch"),
                "Replacement traces must be recorded even when debug log emission is disabled."
        );
        int reset = content.indexOf("CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics");
        int recordReplacement = content.indexOf("RespawnTraceLogSupport.recordReplacement");
        int scheduleProbe = content.indexOf("RespawnTraceLogSupport.scheduleProbe");

        assertTrue(reset >= 0, "Dead-respawn replacements must clear inherited fall and velocity state.");
        assertTrue(recordReplacement >= 0, "Dead-respawn replacements must still record the trace replacement.");
        assertTrue(scheduleProbe >= 0, "Dead-respawn replacements must still schedule post-spawn probes.");
        assertTrue(reset < recordReplacement, "Spawn physics reset should run before replacement trace recording.");
        assertTrue(recordReplacement < scheduleProbe, "Trace replacement recording should happen before probes.");
    }

    @Test
    void lostRecoveryClearsPhysicsOnlyInsideDurableProjectionFlow() throws IOException {
        String content = Files.readString(LOST_RECOVERY_COORDINATOR, StandardCharsets.UTF_8);
        assertTrue(
                content.contains("projectionSpawner.spawn("),
                "Lost recovery must use the planned pre-add projection spawner."
        );
        int reset = content.indexOf("CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics");
        assertTrue(reset >= 0, "Lost-recovery replacements must clear inherited fall and velocity state.");
        assertTrue(content.contains("operationRepository.recordProjectionCreated("),
                "Visible recovery projections must be recorded durably.");
        assertTrue(content.contains("operationRepository.finalizeRecovery(finalization)"),
                "Recovery identity must finalize atomically before follow-up effects.");
        assertTrue(!content.contains("NPCPlugin.get()"),
                "The coordinator must not retain the old direct/default fallback spawn path.");
    }

    @Test
    void spawnPhysicsResetClearsFallDistanceVelocityAndQueuedInstructions() throws IOException {
        String content = Files.readString(SPAWN_PHYSICS_RESET_SERVICE, StandardCharsets.UTF_8);

        assertTrue(content.contains("npc.setCurrentFallDistance(0.0)"), "Spawn reset must clear fall distance.");
        assertTrue(content.contains("velocity.setZero()"), "Spawn reset must clear server velocity.");
        assertTrue(content.contains("velocity.setClient(0.0, 0.0, 0.0)"), "Spawn reset must clear client velocity.");
        assertTrue(content.contains("instructions.clear()"), "Spawn reset must clear queued velocity instructions.");
        assertTrue(
                content.contains("fallDistanceBefore=") && content.contains("velocityBefore="),
                "Spawn reset diagnostics should report the stale physics values it cleared."
        );
    }

    @Test
    void breedingOffspringUsesSpawnPhysicsResetAndFallDamageGrace() throws IOException {
        String content = Files.readString(BREEDING_OFFSPRING_SERVICE, StandardCharsets.UTF_8);
        int reset = content.indexOf("CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics");
        int protection = content.indexOf("RecentSpawnProtectionService.getInstance().record");
        int progression = content.indexOf("progressionService.applyOffspringState");

        assertTrue(reset >= 0, "Breeding offspring must clear inherited fall and velocity state after spawn.");
        assertTrue(protection >= 0, "Breeding offspring must receive short fall-damage grace after spawn.");
        assertTrue(progression >= 0, "Breeding offspring progression state must still be applied.");
        assertTrue(reset < protection, "Spawn physics reset should run before fall-damage grace is recorded.");
        assertTrue(protection < progression, "Spawn protection should be registered before further offspring setup.");
    }
}
