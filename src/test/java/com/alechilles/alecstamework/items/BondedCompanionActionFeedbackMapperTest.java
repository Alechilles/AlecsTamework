package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Player feedback must retain meaning without exposing durable diagnostics. */
class BondedCompanionActionFeedbackMapperTest {

    @Test
    void everyPublicResultAndSupportedRefinementHasOneTypedReason() {
        for (ResultCase result : resultCases()) {
            assertEquals(result.expected(), BondedCompanionActionFeedbackMapper
                    .from(result.code(), result.diagnostic()), result.toString());
        }
    }

    @Test
    void everyPlayerFacingValueIsLocalizedAndContainsNoInternalSlug() {
        for (String language : List.of("en-US", "de-DE", "fr-CA", "fr-FR", "pt-BR")) {
            for (BondedCompanionActionBlockReason reason
                    : BondedCompanionActionBlockReason.values()) {
                String key = BondedCompanionActionFeedbackMapper.localizationKey(reason);
                String text = BondedCompanionActionFeedbackMapper.resolve(language, reason);
                assertFalse(key.isBlank());
                assertFalse(text.isBlank(), language + " missing " + key);
                assertNotEquals(key, text, language + " exposed raw " + key);
                String normalized = text.toLowerCase(Locale.ROOT);
                for (String internal : List.of(
                        "bonded-", "bonded_", "sqlite", "projection_")) {
                    assertFalse(normalized.contains(internal),
                            () -> language + " " + reason + " leaked "
                                    + internal + ": " + text);
                }
            }
        }
    }

    private static List<ResultCase> resultCases() {
        return List.of(
                result(BondedCompanionResultCode.SUCCESS, null,
                        BondedCompanionActionBlockReason.NONE),
                result(BondedCompanionResultCode.UNAVAILABLE,
                        "sqlite_unknown",
                        BondedCompanionActionBlockReason.AUTHORITY_UNAVAILABLE),
                result(BondedCompanionResultCode.NOT_FOUND,
                        "bonded-profile-not-found",
                        BondedCompanionActionBlockReason.NOT_FOUND),
                result(BondedCompanionResultCode.NOT_OWNER,
                        "bonded-transition-not_owner",
                        BondedCompanionActionBlockReason.NOT_OWNER),
                result(BondedCompanionResultCode.INVALID_STATE,
                        "bonded-transition-invalid_state",
                        BondedCompanionActionBlockReason.INVALID_STATE),
                result(BondedCompanionResultCode.REVISION_CONFLICT,
                        "bonded-store-not-committed",
                        BondedCompanionActionBlockReason.REVISION_CONFLICT),
                result(BondedCompanionResultCode.POLICY_DENIED,
                        "bonded-transition-active_capacity_reached",
                        BondedCompanionActionBlockReason.CAPACITY_REACHED),
                result(BondedCompanionResultCode.POLICY_DENIED,
                        "bonded-transition-cooldown_active",
                        BondedCompanionActionBlockReason.COOLDOWN_ACTIVE),
                result(BondedCompanionResultCode.POLICY_DENIED,
                        "bonded-transition-feature_disabled",
                        BondedCompanionActionBlockReason.FEATURE_DISABLED),
                result(BondedCompanionResultCode.POLICY_DENIED,
                        "bonded-transition-role_not_allowed",
                        BondedCompanionActionBlockReason.ROLE_NOT_ALLOWED),
                result(BondedCompanionResultCode.POLICY_DENIED,
                        "bonded-policy-denied",
                        BondedCompanionActionBlockReason.POLICY_DENIED),
                result(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                        "bonded-placement-context-required",
                        BondedCompanionActionBlockReason.PLACEMENT_UNAVAILABLE),
                result(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                        "bonded-world-context-unavailable",
                        BondedCompanionActionBlockReason.WORLD_UNAVAILABLE),
                result(BondedCompanionResultCode.VALIDATION_FAILED,
                        "bonded-payment-price-mismatch",
                        BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE),
                result(BondedCompanionResultCode.VALIDATION_FAILED,
                        "bonded-transition-snapshot_role_mismatch",
                        BondedCompanionActionBlockReason.ROLE_NOT_ALLOWED),
                result(BondedCompanionResultCode.VALIDATION_FAILED,
                        "unknown validation",
                        BondedCompanionActionBlockReason.VALIDATION_FAILED),
                result(BondedCompanionResultCode.INTERNAL_FAILURE,
                        "projection_internal_failure",
                        BondedCompanionActionBlockReason.GENERIC_FAILURE));
    }

    private static ResultCase result(BondedCompanionResultCode code,
                                     String diagnostic,
                                     BondedCompanionActionBlockReason expected) {
        return new ResultCase(code, diagnostic, expected);
    }

    private record ResultCase(
            BondedCompanionResultCode code,
            String diagnostic,
            BondedCompanionActionBlockReason expected) {}
}
