package com.alechilles.alecstamework.persistence.incidents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PersistenceScopeFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void installationSaltProducesStableRemoteSafeScopeHashes() throws Exception {
        Path salt = tempDir.resolve("identity-salt.bin");
        UUID owner = UUID.randomUUID();

        PersistenceScope first = PersistenceScopeFactory.loadOrCreate(salt).ownerGlobal(owner);
        PersistenceScope reloaded = PersistenceScopeFactory.loadOrCreate(salt).ownerGlobal(owner);

        assertEquals(32, Files.size(salt));
        assertEquals(first.scopeHash(), reloaded.scopeHash());
        assertFalse(first.scopeHash().contains(owner.toString()));
        assertEquals(64, first.scopeHash().length());
    }

    @Test
    void scopeTypeAndInstallationSaltSeparateOtherwiseEqualKeys() throws Exception {
        String key = "same-key";
        PersistenceScopeFactory first = PersistenceScopeFactory.loadOrCreate(
                tempDir.resolve("first.bin"));
        PersistenceScopeFactory second = PersistenceScopeFactory.loadOrCreate(
                tempDir.resolve("second.bin"));

        assertNotEquals(first.scope(PersistenceScopeType.PROFILE, key, null).scopeHash(),
                first.scope(PersistenceScopeType.OPERATION, key, null).scopeHash());
        assertNotEquals(first.scope(PersistenceScopeType.PROFILE, key, null).scopeHash(),
                second.scope(PersistenceScopeType.PROFILE, key, null).scopeHash());
    }
}
