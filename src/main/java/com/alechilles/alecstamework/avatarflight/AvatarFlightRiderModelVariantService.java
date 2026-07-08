package com.alechilles.alecstamework.avatarflight;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves rider attachment model paths without creating runtime common assets.
 */
final class AvatarFlightRiderModelVariantService {
    private static final String GENERATED_PREFIX = "Tamework/AvatarFlight/Rider/Variants/";
    private static final String LEGACY_EQUIPMENT_PREFIX = "Tamework/AvatarFlight/Rider/Equipment/";

    private AvatarFlightRiderModelVariantService() {
    }

    @Nonnull
    static String resolveForRider(@Nonnull String model) {
        return normalizeCommonPath(model);
    }

    static boolean isGeneratedVariant(@Nullable String model) {
        String normalized = normalizeCommonPath(model);
        return normalized.startsWith(GENERATED_PREFIX) || normalized.startsWith(LEGACY_EQUIPMENT_PREFIX);
    }

    @Nonnull
    private static String normalizeCommonPath(@Nullable String model) {
        if (model == null) {
            return "";
        }
        String normalized = model.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("Common/")) {
            normalized = normalized.substring("Common/".length());
        }
        return normalized;
    }

}
