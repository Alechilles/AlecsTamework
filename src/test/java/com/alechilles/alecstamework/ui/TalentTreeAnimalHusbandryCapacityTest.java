package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TalentTreeAnimalHusbandryCapacityTest {
    private static final Path AH_TALENT_DIR = resolveAnimalHusbandryTalentDir();
    private static final Pattern ID_PATTERN = Pattern.compile("\"Id\"\\s*:");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("\"Branch\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQUIRES_PATTERN = Pattern.compile("\"RequiresTalentIds\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    @Test
    void animalHusbandryTalentTreesFitScrollableCanvasSlotBudgets() throws IOException {
        assertTrue(Files.isDirectory(AH_TALENT_DIR), "Animal Husbandry talent config directory should exist beside Tamework.");
        try (var files = Files.list(AH_TALENT_DIR)) {
            for (Path talentFile : files
                    .filter(path -> path.getFileName().toString().startsWith("AHTalent"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList()) {
                String json = Files.readString(talentFile, StandardCharsets.UTF_8);
                int nodeCount = count(ID_PATTERN, json);
                int connectorCount = countRequiredTalentIds(json);
                int branchCount = countBranches(json);

                assertTrue(
                        nodeCount <= TalentTreeViewModel.MAX_NODE_SLOTS,
                        talentFile + " should fit within the talent tree node slot budget."
                );
                assertTrue(
                        connectorCount <= TalentTreeViewModel.MAX_CONNECTOR_SLOTS,
                        talentFile + " should fit within the talent tree connector slot budget."
                );
                assertTrue(
                        branchCount <= TalentTreeViewModel.MAX_BRANCH_SLOTS,
                        talentFile + " should fit within the talent tree branch label budget."
                );
            }
        }
    }

    private static int count(Pattern pattern, String content) {
        int count = 0;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countBranches(String content) {
        HashSet<String> branches = new HashSet<>();
        Matcher matcher = BRANCH_PATTERN.matcher(content);
        while (matcher.find()) {
            branches.add(matcher.group(1));
        }
        return branches.size();
    }

    private static int countRequiredTalentIds(String content) {
        int count = 0;
        Matcher arrays = REQUIRES_PATTERN.matcher(content);
        while (arrays.find()) {
            Matcher strings = STRING_PATTERN.matcher(arrays.group(1));
            while (strings.find()) {
                count++;
            }
        }
        return count;
    }

    private static Path resolveAnimalHusbandryTalentDir() {
        Path checkoutParent = Path.of("").toAbsolutePath().getParent();
        if (checkoutParent != null) {
            Path sibling = checkoutParent.resolve("Alec's Animal Husbandry!")
                    .resolve("Server")
                    .resolve("Tamework")
                    .resolve("Talents");
            if (Files.isDirectory(sibling)) {
                return sibling;
            }
        }
        return Path.of(
                System.getProperty("user.home"),
                "AppData",
                "Roaming",
                "Hytale",
                "Modding",
                "Alec's Animal Husbandry!",
                "Server",
                "Tamework",
                "Talents"
        );
    }
}
