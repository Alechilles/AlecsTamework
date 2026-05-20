package com.alechilles.alecstamework.assets.patches;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One raw or macro operation inside an optional NPC template patch.
 */
public final class NpcTemplatePatchOperation {
    private final String id;
    private final String op;
    private final String path;
    private final String position;
    private final String macro;
    private final boolean required;
    private final JsonElement value;
    private final JsonObject find;
    private final JsonObject existing;
    private final JsonObject options;

    private NpcTemplatePatchOperation(@Nonnull String id,
                                      @Nonnull String op,
                                      @Nullable String path,
                                      @Nullable String position,
                                      @Nullable String macro,
                                      boolean required,
                                      @Nullable JsonElement value,
                                      @Nullable JsonObject find,
                                      @Nullable JsonObject existing,
                                      @Nullable JsonObject options) {
        this.id = id;
        this.op = op;
        this.path = path;
        this.position = position;
        this.macro = macro;
        this.required = required;
        this.value = value == null ? null : value.deepCopy();
        this.find = find == null ? null : find.deepCopy();
        this.existing = existing == null ? null : existing.deepCopy();
        this.options = options == null ? null : options.deepCopy();
    }

    @Nonnull
    static NpcTemplatePatchOperation parse(@Nonnull JsonObject object, @Nonnull String patchId, int index) {
        String op = NpcTemplatePatchDefinition.readRequiredString(object, "Op", patchId + " operation " + index);
        String id = NpcTemplatePatchDefinition.readString(object, "Id", patchId + "#" + index);
        String path = NpcTemplatePatchDefinition.readString(object, "Path", null);
        String position = NpcTemplatePatchDefinition.readString(object, "Position", null);
        String macro = NpcTemplatePatchDefinition.readString(object, "Macro", null);
        boolean required = NpcTemplatePatchDefinition.readBoolean(object, "Required", true);
        return new NpcTemplatePatchOperation(
                id,
                op,
                path,
                position,
                macro,
                required,
                object.get("Value"),
                readObject(object, "Find"),
                readObject(object, "Existing"),
                readObject(object, "Options")
        );
    }

    @Nonnull
    public static NpcTemplatePatchOperation raw(@Nonnull String id,
                                                @Nonnull String op,
                                                @Nullable String path,
                                                @Nullable String position,
                                                boolean required,
                                                @Nullable JsonElement value,
                                                @Nullable JsonObject find,
                                                @Nullable JsonObject existing) {
        return new NpcTemplatePatchOperation(id, op, path, position, null, required, value, find, existing, null);
    }

    @Nonnull
    public String getId() {
        return id;
    }

    @Nonnull
    public String getOp() {
        return op;
    }

    @Nullable
    public String getPath() {
        return path;
    }

    @Nullable
    public String getPosition() {
        return position;
    }

    @Nullable
    public String getMacro() {
        return macro;
    }

    public boolean isRequired() {
        return required;
    }

    @Nullable
    public JsonElement getValue() {
        return value == null ? null : value.deepCopy();
    }

    @Nullable
    public JsonObject getFind() {
        return find == null ? null : find.deepCopy();
    }

    @Nullable
    public JsonObject getExisting() {
        return existing == null ? null : existing.deepCopy();
    }

    @Nullable
    public JsonObject getOptions() {
        return options == null ? null : options.deepCopy();
    }

    @Nullable
    private static JsonObject readObject(@Nonnull JsonObject object, @Nonnull String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object.");
        }
        return element.getAsJsonObject();
    }
}
