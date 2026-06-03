package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Persists deterministic attachment-set selections used for offspring appearance inheritance.
 */
public final class TameworkAttachmentsComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkAttachmentsComponent> CODEC = BuilderCodec.builder(
            TameworkAttachmentsComponent.class,
            TameworkAttachmentsComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkAttachmentsComponent::setConfigId,
            TameworkAttachmentsComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("AttachmentIds", MapCodec.STRING_HASH_MAP_CODEC),
            TameworkAttachmentsComponent::setAttachmentIds,
            TameworkAttachmentsComponent::getAttachmentIds
        )
        .add()
        .build();

    private String configId;
    private Map<String, String> attachmentIds = Collections.emptyMap();

    public TameworkAttachmentsComponent() {
    }

    public TameworkAttachmentsComponent(@Nullable String configId,
                                        @Nullable Map<String, String> attachmentIds) {
        this.configId = configId;
        this.attachmentIds = sanitizeAttachmentIds(attachmentIds);
    }

    public static ComponentType<EntityStore, TameworkAttachmentsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getAttachmentsComponentType() : null;
    }

    @Nullable
    public String getConfigId() {
        return configId;
    }

    public void setConfigId(@Nullable String configId) {
        this.configId = configId;
    }

    public Map<String, String> getAttachmentIds() {
        return attachmentIds == null ? Collections.emptyMap() : attachmentIds;
    }

    public void setAttachmentIds(@Nullable Map<String, String> attachmentIds) {
        this.attachmentIds = sanitizeAttachmentIds(attachmentIds);
    }

    @Override
    public TameworkAttachmentsComponent clone() {
        TameworkAttachmentsComponent copy = new TameworkAttachmentsComponent();
        copy.configId = configId;
        copy.attachmentIds = getAttachmentIds();
        return copy;
    }

    private static Map<String, String> sanitizeAttachmentIds(@Nullable Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> cleaned = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            cleaned.put(key, value);
        }
        return cleaned.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(cleaned);
    }
}
