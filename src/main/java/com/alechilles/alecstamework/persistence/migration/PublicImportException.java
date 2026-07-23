package com.alechilles.alecstamework.persistence.migration;

import javax.annotation.Nonnull;

/** Import refusal caused by unbounded source identity or shape corruption. */
final class PublicImportException extends Exception {
    private final String code;

    PublicImportException(@Nonnull String code, @Nonnull String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Import failure code is required");
        }
        this.code = code;
    }

    @Nonnull
    String code() {
        return code;
    }
}
