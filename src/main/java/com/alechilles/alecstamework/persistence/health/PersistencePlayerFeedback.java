package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.hypixel.hytale.server.core.entity.entities.Player;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps stable persistence availability outcomes to localized, identity-safe player feedback. */
public final class PersistencePlayerFeedback {
    private static final String KEY_PREFIX = "tamework.ui.notifications.persistence.";

    private PersistencePlayerFeedback() {
    }

    @Nonnull
    public static String resolve(@Nullable Player player,
                                 @Nonnull PersistenceDomain domain,
                                 @Nonnull PersistenceMutationAvailabilityDecision decision) {
        return switch (decision.status()) {
            case ALLOW -> "";
            case RETRYABLE_DENIAL -> LocalizedText.resolve(player, KEY_PREFIX + "retrying");
            case QUARANTINED -> incidentBacked(player, protectedKey(domain), decision.incidentId());
            case AUTHORITY_NOT_READY -> LocalizedText.resolve(player, KEY_PREFIX + "authorityNotReady");
            case FEATURE_PAUSED -> LocalizedText.resolve(player, KEY_PREFIX + "featurePaused");
            case GLOBAL_READ_ONLY -> incidentBacked(
                    player, KEY_PREFIX + "globalReadOnly", decision.incidentId());
        };
    }

    @Nonnull
    private static String protectedKey(PersistenceDomain domain) {
        return switch (domain) {
            case MANAGED_COOP_INTAKE, MANAGED_COOP_RELEASE, MANAGED_COOP_AUTOMATION ->
                    KEY_PREFIX + "coopProtected";
            case BREEDING_PAIRING, BREEDING_BIRTH, BREEDING ->
                    KEY_PREFIX + "breedingProtected";
            default -> KEY_PREFIX + "profileProtected";
        };
    }

    @Nonnull
    private static String incidentBacked(@Nullable Player player,
                                         @Nonnull String baseKey,
                                         @Nullable String incidentId) {
        String shortId = shortIncidentId(incidentId);
        return shortId == null
                ? LocalizedText.resolve(player, baseKey)
                : LocalizedText.format(player, baseKey + "WithReference", shortId);
    }

    @Nullable
    public static String shortIncidentId(@Nullable String incidentId) {
        if (incidentId == null || incidentId.isBlank()) {
            return null;
        }
        String normalized = incidentId.trim();
        return normalized.substring(0, Math.min(8, normalized.length()));
    }
}
