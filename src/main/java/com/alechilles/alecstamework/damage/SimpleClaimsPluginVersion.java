package com.alechilles.alecstamework.damage;

import javax.annotation.Nullable;

/** Version gate for the verified SimpleClaims reflection contract. */
final class SimpleClaimsPluginVersion {
    private SimpleClaimsPluginVersion() {
    }

    static boolean isSupported(@Nullable String version) {
        Release release = parseRelease(version);
        return release != null
                && release.major() == 1
                && release.minor() == 0
                && release.patch() >= 38;
    }

    @Nullable
    private static Release parseRelease(@Nullable String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String normalized = version.trim();
        int metadataSeparator = normalized.indexOf('+');
        String release = metadataSeparator < 0
                ? normalized
                : normalized.substring(0, metadataSeparator);
        if (release.indexOf('-') >= 0 || !validMetadata(normalized, metadataSeparator)) {
            return null;
        }
        String[] parts = release.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Release(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean validMetadata(String version, int separator) {
        if (separator < 0) {
            return true;
        }
        if (separator == version.length() - 1 || version.indexOf('+', separator + 1) >= 0) {
            return false;
        }
        return version.substring(separator + 1)
                .matches("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*");
    }

    private record Release(int major, int minor, int patch) {
    }
}
