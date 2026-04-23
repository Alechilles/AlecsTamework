package com.alechilles.alecstelemetry.crash;

import com.hypixel.hytale.common.plugin.PluginIdentifier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Attribution helper for deciding whether a crash belongs to a registered project.
 */
public final class CrashAttribution {

    private CrashAttribution() {
    }

    @Nonnull
    public static AttributionResult classify(@Nullable Throwable throwable,
                                             @Nonnull List<String> ownerPluginIdentifiers,
                                             @Nonnull List<String> packagePrefixes,
                                             @Nullable String identifiedPluginHint) {
        if (throwable == null) {
            return new AttributionResult(false, null, false, false, "unknown");
        }

        String identifiedPlugin = normalizePluginIdentifier(identifiedPluginHint);
        if (identifiedPlugin == null) {
            identifiedPlugin = identifyPlugin(throwable);
        }

        boolean matchedPluginIdentifier = matchesPluginIdentifier(identifiedPlugin, ownerPluginIdentifiers);
        boolean matchedStackPrefix = containsRegisteredPrefix(throwable, packagePrefixes);
        boolean attributed = matchedPluginIdentifier || matchedStackPrefix;
        return new AttributionResult(
                attributed,
                identifiedPlugin,
                matchedPluginIdentifier,
                matchedStackPrefix,
                buildFingerprint(throwable)
        );
    }

    @Nonnull
    public static AttributionResult explicit(@Nullable Throwable throwable,
                                             @Nullable String pluginIdentifier,
                                             @Nonnull List<String> packagePrefixes) {
        if (throwable == null) {
            return new AttributionResult(false, normalizePluginIdentifier(pluginIdentifier), false, false, "unknown");
        }
        String normalizedPlugin = normalizePluginIdentifier(pluginIdentifier);
        return new AttributionResult(
                true,
                normalizedPlugin,
                normalizedPlugin != null,
                containsRegisteredPrefix(throwable, packagePrefixes),
                buildFingerprint(throwable)
        );
    }

    public static boolean matchesPluginIdentifier(@Nullable String identifiedPlugin,
                                                  @Nonnull List<String> ownerPluginIdentifiers) {
        if (identifiedPlugin == null || ownerPluginIdentifiers.isEmpty()) {
            return false;
        }
        for (String candidate : ownerPluginIdentifiers) {
            if (candidate != null && identifiedPlugin.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsRegisteredPrefix(@Nonnull Throwable throwable,
                                                   @Nonnull List<String> packagePrefixes) {
        if (packagePrefixes.isEmpty()) {
            return false;
        }
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 8) {
            for (StackTraceElement frame : cursor.getStackTrace()) {
                if (frame == null || frame.getClassName() == null) {
                    continue;
                }
                for (String prefix : packagePrefixes) {
                    if (prefix != null && !prefix.isBlank() && frame.getClassName().startsWith(prefix.trim())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    @Nullable
    private static String identifyPlugin(@Nonnull Throwable throwable) {
        try {
            PluginIdentifier identifiedPlugin = PluginIdentifier.identifyThirdPartyPlugin(throwable);
            return identifiedPlugin == null ? null : identifiedPlugin.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String normalizePluginIdentifier(@Nullable String pluginIdentifier) {
        if (pluginIdentifier == null || pluginIdentifier.isBlank()) {
            return null;
        }
        return pluginIdentifier.trim();
    }

    @Nonnull
    private static String buildFingerprint(@Nonnull Throwable throwable) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Throwable cursor = throwable;
            int causeDepth = 0;
            while (cursor != null && causeDepth < 6) {
                updateDigest(digest, cursor.getClass().getName());
                updateDigest(digest, normalizeMessage(cursor.getMessage()));
                StackTraceElement[] stackTrace = cursor.getStackTrace();
                int frameLimit = Math.min(stackTrace.length, 24);
                for (int i = 0; i < frameLimit; i++) {
                    StackTraceElement frame = stackTrace[i];
                    updateDigest(digest, frame.getClassName());
                    updateDigest(digest, frame.getMethodName());
                    updateDigest(digest, frame.getFileName());
                    updateDigest(digest, Integer.toString(frame.getLineNumber()));
                }
                cursor = cursor.getCause();
                causeDepth++;
            }
            return toHex(digest.digest(), 24);
        } catch (Exception ignored) {
            String fallback = throwable.getClass().getName()
                    + "|"
                    + normalizeMessage(throwable.getMessage())
                    + "|"
                    + throwable.getStackTrace().length;
            return Integer.toHexString(fallback.hashCode());
        }
    }

    private static void updateDigest(@Nonnull MessageDigest digest, @Nullable String value) {
        String normalized = value == null ? "<null>" : value;
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    @Nonnull
    private static String toHex(@Nonnull byte[] hash, int maxChars) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
            if (builder.length() >= maxChars) {
                break;
            }
        }
        return builder.length() == 0 ? "unknown" : builder.toString();
    }

    @Nonnull
    private static String normalizeMessage(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "<empty>";
        }
        String trimmed = message.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    /**
     * Classification result for one throwable/project comparison.
     */
    public record AttributionResult(boolean attributed,
                                    @Nullable String identifiedPlugin,
                                    boolean matchedPluginIdentifier,
                                    boolean matchedStackPrefix,
                                    @Nonnull String fingerprint) {
    }
}
