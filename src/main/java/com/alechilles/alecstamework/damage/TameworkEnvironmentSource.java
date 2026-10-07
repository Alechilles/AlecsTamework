package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Environment damage dealt by Tamework that keeps its source type. Update 7 removed
 * {@code EnvironmentSource.getType()}, so Tamework identifies its own sources through this class.
 */
public final class TameworkEnvironmentSource extends Damage.EnvironmentSource {
    private final String type;

    public TameworkEnvironmentSource(@Nonnull String type) {
        super(type);
        this.type = type;
    }

    /** The Tamework source type, or null for any source Tamework did not create. */
    @Nullable
    public static String typeOf(@Nullable Damage.Source source) {
        return source instanceof TameworkEnvironmentSource tamework ? tamework.type : null;
    }
}
