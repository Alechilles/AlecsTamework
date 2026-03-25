package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigSnapshot;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Field policy for the player/admin config editor.
 * Uses schema-derived descriptors when available and falls back to read-only
 * top-level JSON inspection when schema extraction fails.
 */
final class TwConfigEditorFieldPolicy {
    private static final String FIELD_PARENT = "Parent";
    private static final String DEFAULT_SECTION = "General";

    private TwConfigEditorFieldPolicy() {
    }

    static boolean isAssetEditable(@Nullable TwConfigAssetDescriptor descriptor) {
        return descriptor != null && descriptor.knownType() && descriptor.editable();
    }

    @Nonnull
    static List<EditorFieldSpec> fieldsFor(@Nullable TwConfigAssetDescriptor descriptor,
                                           @Nullable TwConfigSnapshot snapshot,
                                           @Nullable JsonObject effectiveJson) {
        if (descriptor == null) {
            return List.of();
        }
        List<EditorFieldSpec> schemaFields = TwConfigSchemaAdapter.fieldsFor(descriptor);
        if (!schemaFields.isEmpty()) {
            return schemaFields;
        }
        return buildReadOnlyTopLevelFields(effectiveJson);
    }

    @Nonnull
    static List<String> optionsFor(@Nonnull EditorFieldSpec field,
                                   @Nonnull TwConfigAssetDescriptor descriptor,
                                   @Nullable TwConfigSnapshot snapshot) {
        if (field.parentSelector()) {
            return resolveParentOptions(descriptor, snapshot);
        }
        return field.options();
    }

    @Nullable
    static EditorFieldSpec findField(@Nonnull List<EditorFieldSpec> fields, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (EditorFieldSpec field : fields) {
            if (field.id().equalsIgnoreCase(id.trim())) {
                return field;
            }
        }
        return null;
    }

    @Nonnull
    private static List<String> resolveParentOptions(@Nonnull TwConfigAssetDescriptor descriptor,
                                                     @Nullable TwConfigSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (TwConfigAssetDescriptor candidate : snapshot.getDescriptors()) {
            if (candidate == null || !candidate.knownType()) {
                continue;
            }
            if (!candidate.familyKey().equalsIgnoreCase(descriptor.familyKey())) {
                continue;
            }
            if (!candidate.sourcePackKey().equalsIgnoreCase(descriptor.sourcePackKey())) {
                continue;
            }
            if (candidate.assetId().equalsIgnoreCase(descriptor.assetId())) {
                continue;
            }
            out.add(candidate.assetId());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Nonnull
    private static List<EditorFieldSpec> buildReadOnlyTopLevelFields(@Nullable JsonObject effectiveJson) {
        if (effectiveJson == null || effectiveJson.entrySet().isEmpty()) {
            return List.of();
        }
        ArrayList<EditorFieldSpec> out = new ArrayList<>();
        for (String key : effectiveJson.keySet()) {
            out.add(new EditorFieldSpec(
                    key.toLowerCase(Locale.ROOT),
                    key,
                    key,
                    DEFAULT_SECTION,
                    EditorFieldType.HANDOFF,
                    false,
                    true,
                    false,
                    List.of(),
                    0
            ));
        }
        return List.copyOf(out);
    }

    enum EditorFieldType {
        STRING,
        INTEGER,
        DOUBLE,
        BOOLEAN,
        OPTION,
        STRING_LIST,
        HANDOFF
    }

    record EditorFieldSpec(
            @Nonnull String id,
            @Nonnull String label,
            @Nonnull String path,
            @Nonnull String section,
            @Nonnull EditorFieldType type,
            boolean editable,
            boolean handoffOnly,
            boolean parentSelector,
            @Nonnull List<String> options,
            int depth
    ) {
        boolean usesOptions() {
            return type == EditorFieldType.BOOLEAN
                    || type == EditorFieldType.OPTION
                    || parentSelector;
        }

        boolean usesTextInput() {
            return type == EditorFieldType.STRING
                    || type == EditorFieldType.INTEGER
                    || type == EditorFieldType.DOUBLE;
        }

        boolean usesTokenEditor() {
            return type == EditorFieldType.STRING_LIST;
        }

        boolean isEditableValue() {
            return editable && !handoffOnly && type != EditorFieldType.HANDOFF;
        }
    }

    @Nonnull
    static EditorFieldSpec parentField(int depth) {
        return new EditorFieldSpec(
                FIELD_PARENT.toLowerCase(Locale.ROOT),
                FIELD_PARENT,
                FIELD_PARENT,
                DEFAULT_SECTION,
                EditorFieldType.OPTION,
                true,
                false,
                true,
                List.of(),
                depth
        );
    }
}
