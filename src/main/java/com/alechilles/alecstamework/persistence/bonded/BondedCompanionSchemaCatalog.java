package com.alechilles.alecstamework.persistence.bonded;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Loads the exact final fresh-world bonded schema and its SHA-256 hash. */
final class BondedCompanionSchemaCatalog {
    static final int VERSION = 1;
    private static final String RESOURCE = "/persistence/bonded/v1.sql";
    private final String script;
    private final String hash;

    BondedCompanionSchemaCatalog() {
        script = load();
        hash = sha256(script);
    }

    String script() {
        return script;
    }

    String hash() {
        return hash;
    }

    private String load() {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing bonded schema resource: " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to load bonded schema resource " + RESOURCE,
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
