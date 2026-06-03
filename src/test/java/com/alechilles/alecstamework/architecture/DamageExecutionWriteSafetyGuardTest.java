package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces safe DamageSystems usage in runtime tick paths.
 */
class DamageExecutionWriteSafetyGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final Path NEEDS_SERVICE = MAIN_JAVA.resolve(Paths.get(
            "com", "alechilles", "alecstamework", "npc", "progression", "CompanionNeedsService.java"
    ));
    private static final Path NEEDS_SYSTEM = MAIN_JAVA.resolve(Paths.get(
            "com", "alechilles", "alecstamework", "npc", "systems", "CompanionNeedsSystem.java"
    ));
    private static final Path COMBAT_XP_SYSTEM = MAIN_JAVA.resolve(Paths.get(
            "com", "alechilles", "alecstamework", "damage", "CompanionCombatExperienceSystem.java"
    ));

    private static final Pattern STORE_DAMAGE_EXECUTION_PATTERN = Pattern.compile(
            "DamageSystems\\.executeDamage\\s*\\(\\s*[^,]+,\\s*store\\s*,",
            Pattern.MULTILINE
    );

    @Test
    void systemClassesDoNotUseStoreOverloadForDamageExecution() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path systemFile : listSystemFiles()) {
            String content = Files.readString(systemFile, StandardCharsets.UTF_8);
            if (!STORE_DAMAGE_EXECUTION_PATTERN.matcher(content).find()) {
                continue;
            }
            violations.add(toUnixRelativePath(systemFile));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "System files must not call DamageSystems.executeDamage with Store overload. "
                        + "Use CommandBuffer overload in tick/event processing paths.\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void companionNeedsSystemPathUsesCommandBufferDamageFlow() throws IOException {
        String needsSystemContent = Files.readString(NEEDS_SYSTEM, StandardCharsets.UTF_8);
        String needsServiceContent = Files.readString(NEEDS_SERVICE, StandardCharsets.UTF_8);

        assertTrue(
                needsSystemContent.contains("CompanionNeedsService.tickNeeds(ref, store, commandBuffer, roleId);"),
                "CompanionNeedsSystem must call command-buffer tickNeeds overload."
        );
        assertTrue(
                needsServiceContent.contains("@Nullable CommandBuffer<EntityStore> commandBuffer"),
                "CompanionNeedsService damage path must accept command buffer context."
        );
        assertTrue(
                needsServiceContent.contains("DamageSystems.executeDamage(npcRef, commandBuffer, damage);"),
                "CompanionNeedsService must use command-buffer damage execution when available."
        );
    }

    @Test
    void companionCombatExperienceSystemUsesCommandBufferForXpWrites() throws IOException {
        String combatXpContent = Files.readString(COMBAT_XP_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(
                combatXpContent.contains("CompanionLevelingService.awardXp(targetRef, store, commandBuffer, roleId, CompanionXpSource.COMBAT_DAMAGE_TAKEN, xp);"),
                "Combat XP taken awards must pass CommandBuffer into CompanionLevelingService."
        );
        assertTrue(
                combatXpContent.contains("CompanionLevelingService.awardXp(sourceRef, store, commandBuffer, roleId, CompanionXpSource.COMBAT_DAMAGE_DEALT, xp);"),
                "Combat XP dealt awards must pass CommandBuffer into CompanionLevelingService."
        );
    }

    private static List<Path> listSystemFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("System.java"))
                    .sorted()
                    .toList();
        }
    }

    private static String toUnixRelativePath(Path path) {
        return MAIN_JAVA.relativize(path).toString().replace('\\', '/');
    }
}
