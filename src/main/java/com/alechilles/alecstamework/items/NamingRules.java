package com.alechilles.alecstamework.items;

/**
 * Resolved naming rules used by the naming interaction flow.
 */
final class NamingRules {
    static final String ALLOWED_CHARS_ANY = "Any";
    static final String ALLOWED_CHARS_LETTERS_NUMBERS_SPACES = "LettersNumbersSpaces";
    static final String ALLOWED_CHARS_LETTERS_NUMBERS = "LettersNumbers";
    static final String ALLOWED_CHARS_LETTERS_SPACES = "LettersSpaces";
    static final String ALLOWED_CHARS_LETTERS = "Letters";
    static final String ALLOWED_CHARS_NUMBERS = "Numbers";
    static final String ALLOWED_CHARS_ASCII = "Ascii";

    static final int DEFAULT_MIN_LENGTH = 1;
    static final int DEFAULT_MAX_LENGTH = 24;

    private final boolean requireTamed;
    private final boolean requireOwner;
    private final boolean allowRename;
    private final boolean replaceExisting;
    private final boolean consumeItem;
    private final boolean trimWhitespace;
    private final int minLength;
    private final int maxLength;
    private final int cooldownMs;
    private final String allowedChars;
    private final String soundEvent;
    private final String particleSystem;

    private NamingRules(Builder builder) {
        int resolvedMin = builder.minLength > 0 ? builder.minLength : DEFAULT_MIN_LENGTH;
        int resolvedMax = builder.maxLength > 0 ? builder.maxLength : DEFAULT_MAX_LENGTH;
        if (resolvedMax < resolvedMin) {
            resolvedMax = resolvedMin;
        }
        String resolvedChars = builder.allowedChars;
        if (resolvedChars == null || resolvedChars.isBlank()) {
            resolvedChars = ALLOWED_CHARS_LETTERS_NUMBERS_SPACES;
        }

        this.requireTamed = builder.requireTamed;
        this.requireOwner = builder.requireOwner;
        this.allowRename = builder.allowRename;
        this.replaceExisting = builder.replaceExisting;
        this.consumeItem = builder.consumeItem;
        this.trimWhitespace = builder.trimWhitespace;
        this.minLength = resolvedMin;
        this.maxLength = resolvedMax;
        this.cooldownMs = Math.max(0, builder.cooldownMs);
        this.allowedChars = resolvedChars;
        this.soundEvent = builder.soundEvent;
        this.particleSystem = builder.particleSystem;
    }

    static Builder builder() {
        return new Builder();
    }

    boolean isRequireTamed() {
        return requireTamed;
    }

    boolean isRequireOwner() {
        return requireOwner;
    }

    boolean isAllowRename() {
        return allowRename;
    }

    boolean isReplaceExisting() {
        return replaceExisting;
    }

    boolean isConsumeItem() {
        return consumeItem;
    }

    boolean isTrimWhitespace() {
        return trimWhitespace;
    }

    int getMinLength() {
        return minLength;
    }

    int getMaxLength() {
        return maxLength;
    }

    int getCooldownMs() {
        return cooldownMs;
    }

    String getAllowedChars() {
        return allowedChars;
    }

    String getSoundEvent() {
        return soundEvent;
    }

    String getParticleSystem() {
        return particleSystem;
    }

    static final class Builder {
        private boolean requireTamed = true;
        private boolean requireOwner = true;
        private boolean allowRename = true;
        private boolean replaceExisting = true;
        private boolean consumeItem;
        private boolean trimWhitespace = true;
        private int minLength = DEFAULT_MIN_LENGTH;
        private int maxLength = DEFAULT_MAX_LENGTH;
        private int cooldownMs;
        private String allowedChars = ALLOWED_CHARS_LETTERS_NUMBERS_SPACES;
        private String soundEvent;
        private String particleSystem;

        Builder requireTamed(boolean requireTamed) {
            this.requireTamed = requireTamed;
            return this;
        }

        Builder requireOwner(boolean requireOwner) {
            this.requireOwner = requireOwner;
            return this;
        }

        Builder allowRename(boolean allowRename) {
            this.allowRename = allowRename;
            return this;
        }

        Builder replaceExisting(boolean replaceExisting) {
            this.replaceExisting = replaceExisting;
            return this;
        }

        Builder consumeItem(boolean consumeItem) {
            this.consumeItem = consumeItem;
            return this;
        }

        Builder trimWhitespace(boolean trimWhitespace) {
            this.trimWhitespace = trimWhitespace;
            return this;
        }

        Builder minLength(int minLength) {
            this.minLength = minLength;
            return this;
        }

        Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        Builder cooldownMs(int cooldownMs) {
            this.cooldownMs = cooldownMs;
            return this;
        }

        Builder allowedChars(String allowedChars) {
            this.allowedChars = allowedChars;
            return this;
        }

        Builder soundEvent(String soundEvent) {
            this.soundEvent = soundEvent;
            return this;
        }

        Builder particleSystem(String particleSystem) {
            this.particleSystem = particleSystem;
            return this;
        }

        NamingRules build() {
            return new NamingRules(this);
        }
    }
}
