package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the public population authority and its commit collaborator against regrowth. */
class RuntimePopulationPolicyAuthoritySizeArchitectureTest {
    private static final Path OWNERSHIP_ROOT = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership"
    );

    @Test
    void authorityAndCommitCoordinatorRemainFocused() throws IOException {
        assertLineLimit("RuntimePopulationPolicyAuthority.java", 500);
        assertLineLimit("PublicPopulationCommitCoordinator.java", 500);
    }

    private static void assertLineLimit(String relativePath, int limit) throws IOException {
        Path path = OWNERSHIP_ROOT.resolve(relativePath);
        int lines = Files.readAllLines(path).size();
        assertTrue(lines <= limit, () -> relativePath + " has " + lines + " lines; limit is " + limit);
    }
}
