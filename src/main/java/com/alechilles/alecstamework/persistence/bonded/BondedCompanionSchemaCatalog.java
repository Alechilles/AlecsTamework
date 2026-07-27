package com.alechilles.alecstamework.persistence.bonded;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Loads immutable bonded schema migrations and their exact SHA-256 hashes. */
final class BondedCompanionSchemaCatalog {
    static final int VERSION = 7;
    private static final String RESOURCE_PREFIX = "/persistence/bonded/v";
    private final String[] scripts = new String[VERSION + 1];
    private final String[] hashes = new String[VERSION + 1];

    BondedCompanionSchemaCatalog() {
        for (int version = 1; version <= VERSION; version++) {
            scripts[version] = load(RESOURCE_PREFIX + version + ".sql");
            hashes[version] = sha256(scripts[version]);
        }
    }

    String script(int version) {
        return scripts[requireVersion(version)];
    }

    String hash(int version) {
        return hashes[requireVersion(version)];
    }

    String[] hashesThrough(int version) {
        int checked = requireVersion(version);
        String[] result = new String[checked];
        System.arraycopy(hashes, 1, result, 0, checked);
        return result;
    }

    private int requireVersion(int version) {
        if (version < 1 || version > VERSION) {
            throw new IllegalArgumentException("unsupported bonded schema version");
        }
        return version;
    }

    private String load(String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing bonded schema resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load bonded schema resource " + resource,
                    failure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
