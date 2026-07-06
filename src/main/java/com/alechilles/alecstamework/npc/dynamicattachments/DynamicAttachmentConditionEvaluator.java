package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure evaluator for dynamic attachment rule conditions. */
public final class DynamicAttachmentConditionEvaluator {
    private DynamicAttachmentConditionEvaluator() {
    }

    public static boolean matches(@Nullable TwDynamicAttachmentsConfig.Condition condition,
                                  @Nullable DynamicAttachmentNpcSnapshot snapshot) {
        if (condition == null || snapshot == null) {
            return false;
        }
        return switch (normalizeType(condition.getType())) {
            case "displaynameequals" -> stringEquals(snapshot.getDisplayName(), condition.getValue(),
                    condition.isIgnoreCase());
            case "ownerpresent" -> booleanEquals(snapshot.getOwnerPresent(), condition.expectedOrTrue());
            case "ownerequals" -> ownerEquals(condition, snapshot);
            case "tamedstate" -> booleanEquals(snapshot.getTamed(), condition.expectedOrTrue());
            case "gender" -> stringEquals(snapshot.getGender(), condition.getValue(), condition.isIgnoreCase());
            case "lifestage" -> stringEquals(snapshot.getLifeStage(), condition.getValue(), condition.isIgnoreCase());
            case "traitpresent" -> traitPresent(condition, snapshot);
            case "traitvalue" -> traitValue(condition, snapshot);
            case "happinessatleast" -> atLeast(snapshot.getHappiness(), threshold(snapshot.getHappiness(), condition));
            case "happinessbelow" -> below(snapshot.getHappiness(), threshold(snapshot.getHappiness(), condition));
            case "needatleast" -> atLeast(
                    snapshot.getNeed(condition.getNeed()),
                    threshold(snapshot.getNeed(condition.getNeed()), condition)
            );
            case "needbelow" -> below(
                    snapshot.getNeed(condition.getNeed()),
                    threshold(snapshot.getNeed(condition.getNeed()), condition)
            );
            case "stateequals" -> stringEquals(
                    snapshot.getCommandState(condition.getState()),
                    condition.getValue(),
                    condition.isIgnoreCase()
            );
            default -> false;
        };
    }

    private static boolean ownerEquals(@Nonnull TwDynamicAttachmentsConfig.Condition condition,
                                       @Nonnull DynamicAttachmentNpcSnapshot snapshot) {
        List<String> expectedValues = expectedValues(condition);
        if (expectedValues.isEmpty()) {
            return false;
        }
        String ownerId = snapshot.getOwnerId() != null ? snapshot.getOwnerId().toString() : null;
        String ownerName = snapshot.getOwnerName();
        for (String expected : expectedValues) {
            if (stringEquals(ownerId, expected, true)
                    || stringEquals(ownerName, expected, condition.isIgnoreCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean traitPresent(@Nonnull TwDynamicAttachmentsConfig.Condition condition,
                                        @Nonnull DynamicAttachmentNpcSnapshot snapshot) {
        String traitId = condition.getTraitId();
        return traitId != null && snapshot.hasTrait(traitId) == condition.expectedOrTrue();
    }

    private static boolean traitValue(@Nonnull TwDynamicAttachmentsConfig.Condition condition,
                                      @Nonnull DynamicAttachmentNpcSnapshot snapshot) {
        String traitId = condition.getTraitId();
        Double expected = condition.getNumber();
        if (traitId == null || expected == null) {
            return false;
        }
        Double actual = snapshot.getTrait(traitId);
        return actual != null && Double.compare(actual, expected) == 0;
    }

    private static boolean booleanEquals(@Nullable Boolean actual, boolean expected) {
        return actual != null && actual == expected;
    }

    private static boolean atLeast(@Nullable Double actual, @Nullable Double threshold) {
        return actual != null && threshold != null && actual >= threshold;
    }

    private static boolean below(@Nullable Double actual, @Nullable Double threshold) {
        return actual != null && threshold != null && actual < threshold;
    }

    @Nullable
    private static Double threshold(@Nullable Double actual, @Nonnull TwDynamicAttachmentsConfig.Condition condition) {
        Double number = condition.getNumber();
        if (number != null) {
            return number;
        }
        Double percent = condition.getPercent();
        if (actual == null || percent == null) {
            return null;
        }
        if (actual <= 1.0) {
            return percent > 1.0 ? percent / 100.0 : percent;
        }
        return percent <= 1.0 ? percent * 100.0 : percent;
    }

    private static boolean stringEquals(@Nullable String actual, @Nullable String expected, boolean ignoreCase) {
        if (actual == null || actual.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        return ignoreCase ? actual.equalsIgnoreCase(expected) : actual.equals(expected);
    }

    @Nonnull
    private static List<String> expectedValues(@Nonnull TwDynamicAttachmentsConfig.Condition condition) {
        ArrayList<String> values = new ArrayList<>();
        String value = condition.getValue();
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
        for (String entry : condition.getValues()) {
            if (entry != null && !entry.isBlank()) {
                values.add(entry);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    @Nonnull
    private static String normalizeType(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '_' && character != '-' && !Character.isWhitespace(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }
}
