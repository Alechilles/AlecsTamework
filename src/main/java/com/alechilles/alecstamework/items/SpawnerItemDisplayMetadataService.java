package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Writes base-game item display metadata for captured spawner stacks.
 */
final class SpawnerItemDisplayMetadataService {
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String CAPTURED_ENTITY_KEY = "CapturedEntity";
    private static final String CAPTURED_ENTITY_NPC_NAME_KEY = "NpcNameKey";
    private static final String GENERIC_CAPTURE_CRATE_NAME = "Capture Crate";
    private static final String GENERIC_CAPTURE_CRATE_KEY = "server.items.captureCrate.name";
    private static final String ROLE_LINE_PREFIX = "Species: ";
    private static final String GENDER_LINE_PREFIX = "Gender: ";
    private static final Gson GSON = new Gson();
    private static final Type ATTACHMENT_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private final TranslationRegistry translationRegistry;
    private final AttachmentDisplayResolver attachmentDisplayResolver;
    private final Function<ItemStack, Message> baseDescriptionResolver;
    private final ItemDisplayMetadataWriter displayMetadataWriter;

    SpawnerItemDisplayMetadataService(@Nullable TranslationRegistry translationRegistry) {
        this(translationRegistry, AttachmentDisplayResolver.ASSET_BACKED, SpawnerItemDisplayMetadataService::baseDescription);
    }

    SpawnerItemDisplayMetadataService(@Nullable TranslationRegistry translationRegistry,
                                      @Nullable AttachmentDisplayResolver attachmentDisplayResolver,
                                      @Nullable Function<ItemStack, Message> baseDescriptionResolver) {
        this(translationRegistry, attachmentDisplayResolver, baseDescriptionResolver, SpawnerItemDisplayMetadataService::writeDisplayMetadata);
    }

    SpawnerItemDisplayMetadataService(@Nullable TranslationRegistry translationRegistry,
                                      @Nullable AttachmentDisplayResolver attachmentDisplayResolver,
                                      @Nullable Function<ItemStack, Message> baseDescriptionResolver,
                                      @Nullable ItemDisplayMetadataWriter displayMetadataWriter) {
        this.translationRegistry = translationRegistry;
        this.attachmentDisplayResolver = attachmentDisplayResolver == null
                ? AttachmentDisplayResolver.ASSET_BACKED
                : attachmentDisplayResolver;
        this.baseDescriptionResolver = baseDescriptionResolver == null
                ? SpawnerItemDisplayMetadataService::baseDescription
                : baseDescriptionResolver;
        this.displayMetadataWriter = displayMetadataWriter == null
                ? SpawnerItemDisplayMetadataService::writeDisplayMetadata
                : displayMetadataWriter;
    }

    @Nullable
    ItemStack applyCapturedDisplayMetadata(@Nullable ItemStack stack, @Nullable ItemFeatureConfig config) {
        if (stack == null || config == null || !config.isSpawnerEnabled()) {
            return stack;
        }
        BsonDocument metadataDoc = stack.getMetadata();
        if (metadataDoc == null || !isCaptured(metadataDoc)) {
            return stack;
        }

        DisplayPayload payload = buildPayload(stack, config, metadataDoc);
        if (payload == null) {
            return clearDisplayMetadata(stack);
        }
        ItemDisplayMetadata metadata = new ItemDisplayMetadata(
                Message.raw(payload.name()),
                payload.description()
        );
        return displayMetadataWriter.write(stack, metadata);
    }

    @Nullable
    ItemStack clearDisplayMetadata(@Nullable ItemStack stack) {
        return stack == null ? null : displayMetadataWriter.write(stack, null);
    }

    @Nullable
    private DisplayPayload buildPayload(ItemStack stack, ItemFeatureConfig config, BsonDocument metadataDoc) {
        String roleId = firstNonBlank(
                readString(metadataDoc, TameworkMetadataKeys.CAPTURE_ROLE_ID),
                readCapturedEntityNpcNameKey(metadataDoc)
        );
        String roleNameKey = firstNonBlank(
                readString(metadataDoc, TameworkMetadataKeys.CAPTURE_NAME_KEY),
                readCapturedEntityRoleNameKey(metadataDoc),
                RoleNameResolver.resolveRoleNameKey(roleId)
        );
        String roleDisplay = resolveRoleDisplay(roleId, roleNameKey);
        String tooltipDisplayName = sanitizeTooltipDisplayName(
                readString(metadataDoc, TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME),
                roleDisplay,
                roleId,
                roleNameKey
        );
        String itemName = formatItemName(tooltipDisplayName, roleDisplay);
        if (itemName == null || itemName.isBlank()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        if (roleDisplay != null && !roleDisplay.isBlank()) {
            lines.add(ROLE_LINE_PREFIX + roleDisplay);
        }
        String gender = readString(metadataDoc, TameworkMetadataKeys.LIFE_STAGE_GENDER);
        if (gender != null && !gender.isBlank()) {
            lines.add(GENDER_LINE_PREFIX + gender);
        }
        for (ResolvedAttachmentDisplay display : resolveAttachmentDisplays(metadataDoc, roleId)) {
            String line = display.toTooltipLine();
            if (line != null && !line.isBlank()) {
                lines.add(line);
            }
        }

        Message description = buildDescription(stack, config, lines);
        return new DisplayPayload(itemName, description);
    }

    @Nullable
    private Message buildDescription(ItemStack stack, ItemFeatureConfig config, List<String> lines) {
        ItemFeatureConfig.SpawnerTooltipMode mode = config.getSpawnerTooltipMode();
        if (mode == null) {
            mode = ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
        }
        String tameworkDescription = String.join("\n", lines);
        if (mode == ItemFeatureConfig.SpawnerTooltipMode.REPLACE) {
            return tameworkDescription.isBlank() ? null : Message.raw(tameworkDescription);
        }
        Message baseDescription = baseDescriptionResolver.apply(stack);
        String baseDescriptionText = messageText(baseDescription);
        if (baseDescriptionText == null || baseDescriptionText.isBlank()) {
            return tameworkDescription.isBlank() ? null : Message.raw(tameworkDescription);
        }
        if (tameworkDescription.isBlank()) {
            return Message.raw(baseDescriptionText);
        }
        return Message.raw(baseDescriptionText + "\n" + tameworkDescription);
    }

    @Nullable
    private String resolveRoleDisplay(@Nullable String roleId, @Nullable String roleNameKey) {
        if ((roleId == null || roleId.isBlank()) && (roleNameKey == null || roleNameKey.isBlank())) {
            return null;
        }
        I18nModule i18n = null;
        try {
            i18n = I18nModule.get();
        } catch (Throwable ignored) {
            // Unit tests and early startup may not have i18n initialized yet.
        }
        I18nModule resolvedI18n = i18n;
        return RoleNameResolver.resolveDisplayName(
                roleId,
                roleNameKey,
                key -> {
                    String translated = resolveI18nMessage(resolvedI18n, key);
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                    return translationRegistry != null ? translationRegistry.get(key) : null;
                }
        );
    }

    @Nullable
    private static String resolveI18nMessage(@Nullable I18nModule i18n, String key) {
        if (i18n == null || key == null || key.isBlank()) {
            return null;
        }
        String translated = i18n.getMessage(DEFAULT_LANGUAGE, key);
        if (translated == null || translated.isBlank() || translated.equals(key)) {
            return null;
        }
        return translated;
    }

    private List<ResolvedAttachmentDisplay> resolveAttachmentDisplays(BsonDocument metadataDoc, @Nullable String roleId) {
        Map<String, String> attachments = readAttachmentMap(metadataDoc);
        if (attachments.isEmpty()) {
            return List.of();
        }
        String modelId = readString(metadataDoc, TameworkMetadataKeys.CAPTURE_MODEL_ID);
        return attachmentDisplayResolver.resolveAll(roleId, modelId, attachments);
    }

    private static Map<String, String> readAttachmentMap(@Nullable BsonDocument metadataDoc) {
        String attachmentsJson = readString(metadataDoc, TameworkMetadataKeys.ATTACHMENTS);
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = GSON.fromJson(attachmentsJson, ATTACHMENT_MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static boolean isCaptured(BsonDocument metadataDoc) {
        BsonValue captured = metadataDoc.get(TameworkMetadataKeys.CAPTURED);
        if (captured != null && captured.isBoolean() && captured.asBoolean().getValue()) {
            return true;
        }
        BsonValue capturedEntity = metadataDoc.get(CAPTURED_ENTITY_KEY);
        return capturedEntity != null && capturedEntity.isDocument();
    }

    @Nullable
    private static String readString(@Nullable BsonDocument metadataDoc, String key) {
        if (metadataDoc == null || key == null || !metadataDoc.containsKey(key)) {
            return null;
        }
        BsonValue value = metadataDoc.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            String raw = value.asString().getValue();
            return raw == null || raw.isBlank() ? null : raw;
        }
        String raw = value.toString();
        return raw == null || raw.isBlank() ? null : raw;
    }

    @Nullable
    private static String readCapturedEntityNpcNameKey(@Nullable BsonDocument metadataDoc) {
        if (metadataDoc == null || !metadataDoc.containsKey(CAPTURED_ENTITY_KEY)) {
            return null;
        }
        BsonValue capturedEntity = metadataDoc.get(CAPTURED_ENTITY_KEY);
        if (capturedEntity == null || !capturedEntity.isDocument()) {
            return null;
        }
        return readString(capturedEntity.asDocument(), CAPTURED_ENTITY_NPC_NAME_KEY);
    }

    @Nullable
    private static String readCapturedEntityRoleNameKey(@Nullable BsonDocument metadataDoc) {
        if (metadataDoc == null || !metadataDoc.containsKey(CAPTURED_ENTITY_KEY)) {
            return null;
        }
        BsonValue capturedEntity = metadataDoc.get(CAPTURED_ENTITY_KEY);
        if (capturedEntity == null || !capturedEntity.isDocument()) {
            return null;
        }
        BsonDocument capturedDoc = capturedEntity.asDocument();
        return firstNonBlank(
                readString(capturedDoc, "RoleNameKey"),
                readString(capturedDoc, "NameTranslationKey"),
                readString(capturedDoc, "RoleNameTranslationKey")
        );
    }

    @Nullable
    private static String formatItemName(@Nullable String displayName, @Nullable String roleName) {
        String resolvedRole = roleName == null || roleName.isBlank() ? null : roleName;
        String resolvedDisplay = displayName == null || displayName.isBlank() ? null : displayName;
        if (resolvedDisplay == null) {
            return resolvedRole;
        }
        if (resolvedRole == null) {
            return resolvedDisplay;
        }
        if (resolvedDisplay.equalsIgnoreCase(resolvedRole)) {
            return resolvedRole;
        }
        return resolvedDisplay + " (" + resolvedRole + ")";
    }

    @Nullable
    private static String sanitizeTooltipDisplayName(@Nullable String tooltipDisplayName,
                                                     @Nullable String roleDisplay,
                                                     @Nullable String roleId,
                                                     @Nullable String roleNameKey) {
        if (tooltipDisplayName == null) {
            return null;
        }
        String trimmed = tooltipDisplayName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equalsIgnoreCase(GENERIC_CAPTURE_CRATE_NAME) || trimmed.equalsIgnoreCase(GENERIC_CAPTURE_CRATE_KEY)) {
            return null;
        }
        if (roleDisplay != null && !roleDisplay.isBlank() && trimmed.equalsIgnoreCase(roleDisplay)) {
            return null;
        }
        if (roleId != null && !roleId.isBlank() && trimmed.equalsIgnoreCase(roleId)) {
            return null;
        }
        if (roleNameKey != null && !roleNameKey.isBlank() && trimmed.equalsIgnoreCase(roleNameKey)) {
            return null;
        }
        return trimmed;
    }

    @Nullable
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static Message baseDescription(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            return stack.getItem().getDescriptionTranslationMessage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static boolean isBlank(@Nullable Message message) {
        String text = messageText(message);
        return text == null || text.isBlank();
    }

    @Nullable
    private static String messageText(@Nullable Message message) {
        if (message == null) {
            return null;
        }
        String text = message.getAnsiMessage();
        if (text == null || text.isBlank()) {
            text = message.getRawText();
        }
        if ((text == null || text.isBlank()) && message.getMessageId() != null) {
            text = message.getMessageId();
        }
        return text;
    }

    private static ItemStack writeDisplayMetadata(ItemStack stack, @Nullable ItemDisplayMetadata metadata) {
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, metadata);
    }

    @FunctionalInterface
    interface ItemDisplayMetadataWriter {
        ItemStack write(ItemStack stack, @Nullable ItemDisplayMetadata metadata);
    }

    private record DisplayPayload(String name, @Nullable Message description) {
    }
}
