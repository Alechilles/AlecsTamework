package com.alechilles.alecstamework.metrics;

import com.hypixel.hytale.common.plugin.PluginIdentifier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Attribution helper for deciding whether a crash belongs to Tamework.
 */
public final class CrashAttribution {

    public static final String TAMEWORK_PACKAGE_PREFIX = "com.alechilles.alecstamework";

    private CrashAttribution() {
    }

    @Nonnull
    public static AttributionResult classify(@Nullable Throwable throwable,
                                             @Nullable PluginIdentifier tameworkIdentifier) {
        if (throwable == null) {
            return new AttributionResult(false, null, false, false, "unknown");
        }

        PluginIdentifier identifiedPlugin = null;
        try {
            identifiedPlugin = PluginIdentifier.identifyThirdPartyPlugin(throwable);
        } catch (Exception ignored) {
            identifiedPlugin = null;
        }

        boolean matchedPluginIdentifier = pluginMatches(identifiedPlugin, tameworkIdentifier);
        boolean matchedStackPrefix = containsTameworkStackPrefix(throwable);
        boolean attributed = matchedPluginIdentifier || matchedStackPrefix;
        String fingerprint = buildFingerprint(throwable);

        return new AttributionResult(
                attributed,
                identifiedPlugin == null ? null : identifiedPlugin.toString(),
                matchedPluginIdentifier,
                matchedStackPrefix,
                fingerprint
        );
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
            byte[] hash = digest.digest();
            return toHex(hash, 24);
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

    private static boolean pluginMatches(@Nullable PluginIdentifier identified,
                                         @Nullable PluginIdentifier tameworkIdentifier) {
        if (identified == null || tameworkIdentifier == null) {
            return false;
        }
        if (identified.equals(tameworkIdentifier)) {
            return true;
        }
        return identified.toString().equalsIgnoreCase(tameworkIdentifier.toString());
    }

    private static boolean containsTameworkStackPrefix(@Nonnull Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 8) {
            StackTraceElement[] stackTrace = cursor.getStackTrace();
            for (StackTraceElement frame : stackTrace) {
                if (frame != null
                        && frame.getClassName() != null
                        && frame.getClassName().startsWith(TAMEWORK_PACKAGE_PREFIX)) {
                    return true;
                }
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    @Nonnull
    private static String normalizeMessage(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "<empty>";
        }
        String trimmed = message.trim();
        if (trimmed.length() > 2000) {
            return trimmed.substring(0, 2000);
        }
        return trimmed;
    }

    public record AttributionResult(boolean attributed,
                                    @Nullable String identifiedPlugin,
                                    boolean matchedPluginIdentifier,
                                    boolean matchedStackPrefix,
                                    @Nonnull String fingerprint) {
    }
}
