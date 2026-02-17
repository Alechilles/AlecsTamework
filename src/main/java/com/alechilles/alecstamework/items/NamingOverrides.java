package com.alechilles.alecstamework.items;

/**
 * Optional overrides for naming interactions.
 */
public final class NamingOverrides {
    private final Boolean requireTamed;
    private final Boolean requireOwner;
    private final Boolean allowRename;
    private final Integer minLength;
    private final Integer maxLength;
    private final String allowedChars;
    private final Boolean trimWhitespace;
    private final Boolean replaceExisting;
    private final Boolean consumeItem;
    private final Integer cooldownMs;

    public NamingOverrides(Boolean requireTamed,
                           Boolean requireOwner,
                           Boolean allowRename,
                           Integer minLength,
                           Integer maxLength,
                           String allowedChars,
                           Boolean trimWhitespace,
                           Boolean replaceExisting,
                           Boolean consumeItem,
                           Integer cooldownMs) {
        this.requireTamed = requireTamed;
        this.requireOwner = requireOwner;
        this.allowRename = allowRename;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.allowedChars = allowedChars;
        this.trimWhitespace = trimWhitespace;
        this.replaceExisting = replaceExisting;
        this.consumeItem = consumeItem;
        this.cooldownMs = cooldownMs;
    }

    public Boolean getRequireTamed() {
        return requireTamed;
    }

    public Boolean getRequireOwner() {
        return requireOwner;
    }

    public Boolean getAllowRename() {
        return allowRename;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public String getAllowedChars() {
        return allowedChars;
    }

    public Boolean getTrimWhitespace() {
        return trimWhitespace;
    }

    public Boolean getReplaceExisting() {
        return replaceExisting;
    }

    public Boolean getConsumeItem() {
        return consumeItem;
    }

    public Integer getCooldownMs() {
        return cooldownMs;
    }
}
