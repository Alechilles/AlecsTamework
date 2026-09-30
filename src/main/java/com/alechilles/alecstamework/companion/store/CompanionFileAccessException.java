package com.alechilles.alecstamework.companion.store;

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * A companion file exists but could not be read at the I/O level (for example a sharing
 * violation or a permission error). Unlike a file whose contents fail to parse, the data may be
 * intact, so callers must never quarantine or overwrite the file because of this failure.
 */
public final class CompanionFileAccessException extends IOException {
    public CompanionFileAccessException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
