package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protects repeated NPC bootstrap from reopening unchanged role assets. */
class CompanionRoleAppearanceCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void reusesRoleFilesUntilExplicitInvalidation() throws IOException {
        Path template = writeRole("Template_Livestock_Tamed.json", "{\"Appearance\":\"Bison\"}");
        Path role = writeRole(
                "Tamed_Bison.json",
                "{\"Type\":\"Variant\",\"Reference\":\"Template_Livestock_Tamed\"}"
        );
        AtomicInteger reads = new AtomicInteger();
        CompanionRoleAppearanceCache cache = new CompanionRoleAppearanceCache(path -> {
            reads.incrementAndGet();
            return CompanionAdultScaleResolver.readJsonObject(path);
        });

        assertEquals("Bison", cache.resolve(role, this::resolveReference));
        assertEquals("Bison", cache.resolve(role, this::resolveReference));
        assertEquals(2, reads.get());

        Files.writeString(template, "{\"Appearance\":\"Bison_Armored\"}");
        cache.clear();

        assertEquals("Bison_Armored", cache.resolve(role, this::resolveReference));
        assertEquals(4, reads.get());
    }

    @Test
    void cachesMissingAppearanceUntilExplicitInvalidation() throws IOException {
        Path role = writeRole("Tamed_Bison.json", "{\"Type\":\"Variant\"}");
        AtomicInteger reads = new AtomicInteger();
        CompanionRoleAppearanceCache cache = new CompanionRoleAppearanceCache(path -> {
            reads.incrementAndGet();
            return CompanionAdultScaleResolver.readJsonObject(path);
        });

        assertNull(cache.resolve(role, this::resolveReference));
        assertNull(cache.resolve(role, this::resolveReference));
        assertEquals(1, reads.get());
    }

    private Path resolveReference(String roleId) {
        return tempDir.resolve(roleId + ".json");
    }

    private Path writeRole(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }
}
