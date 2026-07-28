package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Converts internal outcomes into a closed set of localized panel messages. */
/**
 * Sole boundary that turns durable bonded diagnostics into player-facing
 * categories and localized messages.
 */
public final class BondedCompanionActionFeedbackMapper {
    private static final String PREFIX =
            "tamework.ui.linkedPanel.action.unavailable.";

    private BondedCompanionActionFeedbackMapper() {}

    @Nonnull
    public static BondedCompanionActionBlockReason from(
            @Nonnull BondedCompanionResultCode code,
            @Nullable String diagnostic) {
        Objects.requireNonNull(code, "code");
        BondedCompanionActionBlockReason detailed = fromDiagnostic(diagnostic);
        return detailed != null ? detailed : fromCode(code);
    }

    @Nullable
    private static BondedCompanionActionBlockReason fromDiagnostic(
            @Nullable String diagnostic) {
        String detail = normalized(diagnostic);
        if (contains(detail, "cooldown")) {
            return BondedCompanionActionBlockReason.COOLDOWN_ACTIVE;
        }
        if (contains(detail, "capacity")) {
            return BondedCompanionActionBlockReason.CAPACITY_REACHED;
        }
        if (contains(detail, "feature_disabled")) {
            return BondedCompanionActionBlockReason.FEATURE_DISABLED;
        }
        if (contains(detail, "role_not_allowed")
                || contains(detail, "snapshot_role")) {
            return BondedCompanionActionBlockReason.ROLE_NOT_ALLOWED;
        }
        if (contains(detail, "placement")) {
            return BondedCompanionActionBlockReason.PLACEMENT_UNAVAILABLE;
        }
        if (contains(detail, "payment") || contains(detail, "price")
                || contains(detail, "charge") || contains(detail, "escrow")
                || contains(detail, "afford") || contains(detail, "cost")) {
            return BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE;
        }
        return null;
    }

    @Nonnull
    private static BondedCompanionActionBlockReason fromCode(
            @Nonnull BondedCompanionResultCode code) {
        return switch (code) {
            case UNAVAILABLE ->
                    BondedCompanionActionBlockReason.AUTHORITY_UNAVAILABLE;
            case NOT_FOUND -> BondedCompanionActionBlockReason.NOT_FOUND;
            case NOT_OWNER -> BondedCompanionActionBlockReason.NOT_OWNER;
            case INVALID_STATE ->
                    BondedCompanionActionBlockReason.INVALID_STATE;
            case REVISION_CONFLICT ->
                    BondedCompanionActionBlockReason.REVISION_CONFLICT;
            case POLICY_DENIED ->
                    BondedCompanionActionBlockReason.POLICY_DENIED;
            case WORLD_UNAVAILABLE ->
                    BondedCompanionActionBlockReason.WORLD_UNAVAILABLE;
            case VALIDATION_FAILED ->
                    BondedCompanionActionBlockReason.VALIDATION_FAILED;
            case INTERNAL_FAILURE ->
                    BondedCompanionActionBlockReason.GENERIC_FAILURE;
            case SUCCESS -> BondedCompanionActionBlockReason.NONE;
        };
    }

    @Nonnull
    public static String localizationKey(
            @Nonnull BondedCompanionActionBlockReason reason) {
        Objects.requireNonNull(reason, "reason");
        return PREFIX + switch (reason) {
            case NONE -> "ready";
            case REFRESHING -> "refreshing";
            case REFRESH_FAILED -> "refreshFailed";
            case AUTHORITY_UNAVAILABLE -> "authority";
            case COOLDOWN_ACTIVE -> "cooldown";
            case CAPACITY_REACHED -> "capacity";
            case FEATURE_DISABLED -> "featureDisabled";
            case POLICY_DENIED -> "policy";
            case ROLE_NOT_ALLOWED -> "role";
            case PLACEMENT_UNAVAILABLE -> "placement";
            case WORLD_UNAVAILABLE -> "world";
            case PAYMENT_UNAVAILABLE -> "payment";
            case REVISION_CONFLICT -> "revision";
            case NOT_FOUND -> "notFound";
            case NOT_OWNER -> "notOwner";
            case INVALID_STATE -> "invalidState";
            case VALIDATION_FAILED -> "validation";
            case GENERIC_FAILURE -> "generic";
        };
    }

    @Nonnull
    public static String resolve(
            @Nullable String language,
            @Nonnull BondedCompanionActionBlockReason reason) {
        return LocalizedText.resolve(language, localizationKey(reason));
    }

    private static String normalized(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }

    private static boolean contains(String value, String fragment) {
        return value.contains(fragment);
    }
}
