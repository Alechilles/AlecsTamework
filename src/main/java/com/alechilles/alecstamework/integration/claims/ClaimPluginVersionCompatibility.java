package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nullable;

/**
 * Release-only version gates for reflected claim-provider contracts.
 *
 * <p>Build metadata does not change a release contract, but prerelease builds are intentionally
 * rejected until their exact API surface has been verified.</p>
 */
public final class ClaimPluginVersionCompatibility {
    private ClaimPluginVersionCompatibility() {
    }

    public static boolean supportsQuestLinesClaims(@Nullable String version) {
        Release release = verifiedRelease(version);
        return release != null
                && release.major() == 1
                && release.minor() == 3
                && release.patch() == 1;
    }

    public static boolean supportsSimpleClaims(@Nullable String version) {
        Release release = verifiedRelease(version);
        return release != null
                && release.major() == 1
                && release.minor() == 0
                && release.patch() >= 38;
    }

    @Nullable
    private static Release verifiedRelease(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String normalized = version.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        int metadataSeparator = normalized.indexOf('+');
        String release = metadataSeparator < 0
                ? normalized
                : normalized.substring(0, metadataSeparator);
        if (release.indexOf('-') >= 0 || !validBuildMetadata(normalized, metadataSeparator)) {
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

    private static boolean validBuildMetadata(String version, int separator) {
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
