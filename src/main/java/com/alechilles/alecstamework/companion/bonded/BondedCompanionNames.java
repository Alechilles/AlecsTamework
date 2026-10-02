package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The name a bonded companion is shown under, resolved for one viewer's language.
 *
 * <p>Order: the name the player gave it, then its role name translated for the viewer, then the
 * species text stored with it, then the generic "Companion" text. A captured or provisioned
 * companion often has no given name, so its role name is what names it.</p>
 */
public final class BondedCompanionNames {
    /** Presentation key of the role name translation key the companion's last body had. */
    public static final String NAME_KEY = "nameKey";
    private static final String DEFAULT_NAME_KEY = "tamework.ui.linkedPanel.subtitle.defaultNpcName";

    private BondedCompanionNames() {
    }

    /** The display name of a stored record for a viewer; {@code language} null means the default language. */
    @Nonnull
    public static String displayName(@Nonnull CompanionRecord record, @Nullable String language) {
        return displayName(record.displayName(), null, record.summary().nameKey(), record.roleId(), language);
    }

    /** The display name from the parts a view or panel row carries. Never blank. */
    @Nonnull
    public static String displayName(@Nullable String givenName, @Nullable String species,
                                     @Nullable String nameKey, @Nullable String roleId,
                                     @Nullable String language) {
        if (givenName != null && !givenName.isBlank()) {
            return givenName.trim();
        }
        String label = speciesLabel(species, nameKey, roleId, language);
        return label != null ? label : LocalizedText.resolve(language, DEFAULT_NAME_KEY);
    }

    /**
     * The species text of a companion for a viewer: the role name key (then the role id's own
     * name key) translated in the viewer's language, else the stored species, which is in one
     * language only. Null when neither is known, so a raw key or role id is never shown.
     */
    @Nullable
    public static String speciesLabel(@Nullable String species, @Nullable String nameKey,
                                      @Nullable String roleId, @Nullable String language) {
        // LocalizedText returns the key itself when no language file has it.
        RoleNameResolver.TranslationLookup lookup = key -> {
            if (key == null || key.isBlank()) {
                return null;
            }
            String text = LocalizedText.resolve(language, key);
            return text.isBlank() || text.equals(key.trim()) ? null : text;
        };
        String translated = RoleNameResolver.translateNameKey(lookup, nameKey);
        if (translated == null) {
            translated = RoleNameResolver.translateNameKey(lookup, roleId);
        }
        if (translated != null) {
            return translated;
        }
        return species == null || species.isBlank() ? null : species.trim();
    }
}
