package com.alechilles.alecstamework.persistence.incidents;

import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds exact local scope keys and installation-local, remote-safe HMAC fingerprints. */
public final class PersistenceScopeFactory {
    private static final int SALT_BYTES = 32;
    private final byte[] identitySalt;

    public PersistenceScopeFactory(@Nonnull byte[] identitySalt) {
        if (identitySalt.length < 16) throw new IllegalArgumentException("identitySalt");
        this.identitySalt = identitySalt.clone();
    }

    @Nonnull
    public static PersistenceScopeFactory loadOrCreate(@Nonnull Path saltPath) throws Exception {
        Path normalized = saltPath.toAbsolutePath().normalize();
        byte[] salt;
        if (Files.exists(normalized)) {
            salt = Files.readAllBytes(normalized);
            if (salt.length != SALT_BYTES) throw new IllegalStateException("identity_salt_invalid");
        } else {
            Path parent = normalized.getParent();
            if (parent != null) Files.createDirectories(parent);
            salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(temporary, salt);
            installSalt(temporary, normalized);
            salt = Files.readAllBytes(normalized);
        }
        return new PersistenceScopeFactory(salt);
    }

    @Nonnull
    public static PersistenceScopeFactory ephemeral() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return new PersistenceScopeFactory(salt);
    }

    @Nonnull
    public PersistenceScope scope(@Nonnull PersistenceScopeType type,
                                  @Nonnull String key,
                                  @Nullable String authorityDimension) {
        String normalized = key.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("scope key");
        return new PersistenceScope(type, normalized, hash(type, normalized), authorityDimension);
    }

    @Nonnull
    public PersistenceScope profile(@Nonnull String profileId) {
        return scope(PersistenceScopeType.PROFILE, profileId, "canonical_profile_catalog");
    }

    @Nonnull
    public PersistenceScope ownerGlobal(@Nonnull UUID ownerId) {
        return scope(PersistenceScopeType.OWNER_GLOBAL, ownerId.toString(), "owner_population_catalog");
    }

    @Nonnull
    public PersistenceScope ownerWorld(@Nonnull UUID ownerId, @Nullable String worldName) {
        String world = worldName == null || worldName.isBlank() ? "<unknown>" : worldName.trim();
        return scope(PersistenceScopeType.OWNER_WORLD, ownerId + "|" + world,
                "owner_population_catalog");
    }

    @Nonnull
    public PersistenceScope operation(@Nonnull String operationId) {
        return scope(PersistenceScopeType.OPERATION, operationId, "operation_journal");
    }

    @Nonnull
    private String hash(PersistenceScopeType type, String key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(identitySalt, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal((type.name() + "\n" + key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("scope_hmac_unavailable", unavailable);
        }
    }

    private static void installSalt(Path temporary, Path destination) throws Exception {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            moveWithoutReplacing(temporary, destination);
        } catch (FileAlreadyExistsException raceWinnerInstalledSalt) {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveWithoutReplacing(Path temporary, Path destination) throws Exception {
        try {
            Files.move(temporary, destination);
        } catch (FileAlreadyExistsException raceWinnerInstalledSalt) {
            Files.deleteIfExists(temporary);
        }
    }
}
