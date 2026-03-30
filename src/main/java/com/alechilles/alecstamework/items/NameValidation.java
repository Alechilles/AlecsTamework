package com.alechilles.alecstamework.items;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates and normalizes NPC names based on resolved naming rules.
 */
final class NameValidation {
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private NameValidation() {
    }

    static NameValidationResult validate(String rawName,
                                         NamingRules rules,
                                         boolean hasTameworkName,
                                         boolean hasAnyName) {
        if (rules == null) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.rulesUnavailable");
        }
        if (rawName == null) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.empty");
        }

        String normalized = rules.isTrimWhitespace() ? rawName.trim() : rawName;
        if (normalized.isBlank()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.empty");
        }

        if (hasTameworkName && !rules.isAllowRename()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.alreadyNamed");
        }
        if (hasAnyName && !rules.isReplaceExisting()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.alreadyHasName");
        }

        int length = normalized.length();
        if (length < rules.getMinLength()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.minLength", rules.getMinLength());
        }
        if (length > rules.getMaxLength()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.maxLength", rules.getMaxLength());
        }

        if (containsControlChars(normalized)) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.invalidCharacters");
        }

        Pattern allowedPattern = resolveAllowedPattern(rules.getAllowedChars());
        if (allowedPattern != null && !allowedPattern.matcher(normalized).matches()) {
            return NameValidationResult.fail("tamework.ui.notifications.name.validation.invalidCharacters");
        }

        return NameValidationResult.ok(normalized);
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern resolveAllowedPattern(String allowedChars) {
        if (allowedChars == null || allowedChars.isBlank()) {
            return PATTERN_CACHE.computeIfAbsent(
                    NamingRules.ALLOWED_CHARS_LETTERS_NUMBERS_SPACES,
                    NameValidation::createPatternForKey
            );
        }
        String key = allowedChars.trim();
        String normalized = key.toLowerCase(Locale.ROOT);
        if (NamingRules.ALLOWED_CHARS_ANY.toLowerCase(Locale.ROOT).equals(normalized)) {
            return null;
        }
        return PATTERN_CACHE.computeIfAbsent(key, NameValidation::createPatternForKey);
    }

    private static Pattern createPatternForKey(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "lettersnumbersspaces":
                return Pattern.compile("^[A-Za-z0-9 ]+$");
            case "lettersnumbers":
                return Pattern.compile("^[A-Za-z0-9]+$");
            case "lettersspaces":
                return Pattern.compile("^[A-Za-z ]+$");
            case "letters":
                return Pattern.compile("^[A-Za-z]+$");
            case "numbers":
                return Pattern.compile("^[0-9]+$");
            case "ascii":
                return Pattern.compile("^[\\x20-\\x7E]+$");
            default:
                break;
        }

        String pattern = key;
        String lower = normalized;
        if (lower.startsWith("regex:")) {
            pattern = key.substring(6).trim();
        }
        if (pattern.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }

    static final class NameValidationResult {
        private final boolean ok;
        private final String normalizedName;
        private final String errorKey;
        private final Object[] errorArgs;

        private NameValidationResult(boolean ok, String normalizedName, String errorKey, Object[] errorArgs) {
            this.ok = ok;
            this.normalizedName = normalizedName;
            this.errorKey = errorKey;
            this.errorArgs = errorArgs == null ? new Object[0] : errorArgs.clone();
        }

        static NameValidationResult ok(String normalizedName) {
            return new NameValidationResult(true, normalizedName, null, null);
        }

        static NameValidationResult fail(String errorKey, Object... errorArgs) {
            return new NameValidationResult(false, null, errorKey, errorArgs);
        }

        boolean isOk() {
            return ok;
        }

        String getNormalizedName() {
            return normalizedName;
        }

        String getErrorKey() {
            return errorKey;
        }

        Object[] getErrorArgs() {
            return errorArgs.clone();
        }
    }
}
