package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks linked NPC deaths so command tools can distinguish dead companions from unloaded companions.
 */
public final class CommandLinkedNpcDeathService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String VECTOR_SEPARATOR = ",";
    private static final String ARRAY_SEPARATOR = ";";
    private static final String ATTACHMENT_KV_SEPARATOR = ",";

    private final ConcurrentHashMap<UUID, DeadLinkedNpcSnapshot> deadByNpc = new ConcurrentHashMap<>();
    private final Path persistencePath;
    private final Object persistenceLock = new Object();

    public CommandLinkedNpcDeathService() {
        this(null);
    }

    public CommandLinkedNpcDeathService(@Nullable Path persistencePath) {
        this.persistencePath = persistencePath != null
                ? persistencePath.toAbsolutePath().normalize()
                : null;
        loadPersistedSnapshots();
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid != null) {
            if (deadByNpc.remove(npcUuid) != null) {
                persistSnapshots();
            }
        }
    }

    public void onNpcRemoved(Ref<EntityStore> reference, RemoveReason reason, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        UUID npcUuid = npc.getUuid();
        if (!wasDeathRemoval(reference, reason, store)) {
            if (deadByNpc.remove(npcUuid) != null) {
                persistSnapshots();
            }
            return;
        }
        TameworkCommandLinksComponent links = store.getComponent(reference, TameworkCommandLinksComponent.getComponentType());
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            if (deadByNpc.remove(npcUuid) != null) {
                persistSnapshots();
            }
            return;
        }

        UUID ownerId = links.getOwnerId();
        TameworkOwnerComponent ownerComponent = store.getComponent(reference, TameworkOwnerComponent.getComponentType());
        if (ownerComponent != null && ownerComponent.getOwnerId() != null) {
            ownerId = ownerComponent.getOwnerId();
        }
        String ownerName = ownerComponent != null ? ownerComponent.getOwnerName() : null;
        boolean tamed = TamedStateResolver.isTamed(reference, store);
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breedingComponent = breedingType != null
                ? store.getComponent(reference, breedingType)
                : null;
        String breedingConfigId = breedingComponent != null ? breedingComponent.getConfigId() : null;
        Double breedingHappiness = breedingComponent != null ? breedingComponent.getHappiness() : null;
        long breedingCooldownUntilMs = breedingComponent != null ? breedingComponent.getCooldownUntilMs() : 0L;
        UUID breedingLastPartnerUuid = breedingComponent != null ? breedingComponent.getLastPartnerUuid() : null;
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happinessComponent = happinessType != null
                ? store.getComponent(reference, happinessType)
                : null;
        String happinessConfigId = happinessComponent != null ? happinessComponent.getConfigId() : null;
        Double happinessValue = null;
        if (happinessComponent != null) {
            happinessValue = happinessComponent.getValue();
        } else if (breedingHappiness != null) {
            happinessValue = breedingHappiness;
        }
        long happinessLastUpdateMs = happinessComponent != null
                ? happinessComponent.getLastUpdateMs()
                : breedingComponent != null
                ? breedingComponent.getLastHappinessUpdateMs()
                : 0L;
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent traitsComponent = traitsType != null
                ? store.getComponent(reference, traitsType)
                : null;
        String traitsConfigId = traitsComponent != null ? traitsComponent.getConfigId() : null;
        long traitsRollSeed = traitsComponent != null ? traitsComponent.getRollSeed() : 0L;
        String traitsValues = traitsComponent != null ? TraitValueCodec.encode(traitsComponent.getTraitValues()) : null;
        if (traitsValues != null && traitsValues.isBlank()) {
            traitsValues = null;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent lifeStageComponent = lifeStageType != null
                ? store.getComponent(reference, lifeStageType)
                : null;
        String lifeStage = lifeStageComponent != null ? lifeStageComponent.getStage() : null;
        long lifeStageBornAtMs = lifeStageComponent != null ? lifeStageComponent.getBornAtMs() : 0L;
        long lifeStageAdolescentAtMs = lifeStageComponent != null ? lifeStageComponent.getAdolescentAtMs() : 0L;
        long lifeStageAdultAtMs = lifeStageComponent != null ? lifeStageComponent.getAdultAtMs() : 0L;
        long lifeStageFullyGrownAtMs = lifeStageComponent != null ? lifeStageComponent.getFullyGrownAtMs() : 0L;
        double lifeStageBabyScale = lifeStageComponent != null ? lifeStageComponent.getBabyScale() : 0.55;
        double lifeStageAdolescentScale = lifeStageComponent != null ? lifeStageComponent.getAdolescentScale() : 0.80;
        double lifeStageAdolescentSwitchScale = lifeStageComponent != null
                ? lifeStageComponent.getAdolescentSwitchScale()
                : 0.80;
        double lifeStageAdultStartScale = lifeStageComponent != null ? lifeStageComponent.getAdultStartScale() : 0.80;
        double lifeStageAdultSwitchScale = lifeStageComponent != null ? lifeStageComponent.getAdultSwitchScale() : 1.00;
        double lifeStageAdultScale = lifeStageComponent != null ? lifeStageComponent.getAdultScale() : 1.00;
        boolean lifeStageGrowthScalingEnabled = lifeStageComponent != null
                && lifeStageComponent.isGrowthScalingEnabled();
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        TameworkAttachmentsComponent attachmentsComponent = attachmentsType != null
                ? store.getComponent(reference, attachmentsType)
                : null;
        String attachmentsConfigId = attachmentsComponent != null ? attachmentsComponent.getConfigId() : null;
        Map<String, String> attachmentSelections = attachmentsComponent != null
                && attachmentsComponent.getAttachmentIds() != null
                && !attachmentsComponent.getAttachmentIds().isEmpty()
                ? attachmentsComponent.getAttachmentIds()
                : CompanionModelAttachmentService.resolveCurrentAttachments(reference, store);
        String attachmentsValues = encodeAttachmentSelections(attachmentSelections);

        TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        String roleId = resolveRoleId(npc);
        String customName = resolveCustomName(reference, store);
        String displayName = resolveDisplayName(reference, store, npc, roleId, customName);
        long diedAtMs = System.currentTimeMillis();
        long respawnAvailableAtMs = diedAtMs + resolveRespawnCooldownMs();

        deadByNpc.put(
                npcUuid,
                new DeadLinkedNpcSnapshot(
                        npcUuid,
                        ownerId,
                        ownerName,
                        sanitizeToolIds(links.getToolIds()),
                        roleId,
                        tamed,
                        customName,
                        displayName,
                        lastKnownPosition,
                        homePosition,
                        diedAtMs,
                        respawnAvailableAtMs,
                        breedingConfigId,
                        breedingHappiness,
                        breedingCooldownUntilMs,
                        breedingLastPartnerUuid,
                        traitsConfigId,
                        traitsRollSeed,
                        traitsValues,
                        happinessConfigId,
                        happinessValue,
                        happinessLastUpdateMs,
                        lifeStage,
                        lifeStageBornAtMs,
                        lifeStageAdolescentAtMs,
                        lifeStageAdultAtMs,
                        lifeStageFullyGrownAtMs,
                        lifeStageBabyScale,
                        lifeStageAdolescentScale,
                        lifeStageAdolescentSwitchScale,
                        lifeStageAdultStartScale,
                        lifeStageAdultSwitchScale,
                        lifeStageAdultScale,
                        lifeStageGrowthScalingEnabled,
                        attachmentsConfigId,
                        attachmentsValues
                )
        );
        persistSnapshots();
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return deadByNpc.get(npcUuid);
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshotForTool(UUID npcUuid, String toolId, @Nullable UUID ownerUuid) {
        DeadLinkedNpcSnapshot snapshot = getDeadSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.containsToolId(toolId)) {
            return null;
        }
        if (snapshot.ownerId() != null && ownerUuid != null && !snapshot.ownerId().equals(ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    public void clearDeadSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        if (deadByNpc.remove(npcUuid) != null) {
            persistSnapshots();
        }
    }

    private void loadPersistedSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            if (!Files.exists(persistencePath)) {
                return;
            }
            try {
                List<String> lines = Files.readAllLines(persistencePath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    DeadLinkedNpcSnapshot snapshot = parseSnapshot(line);
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    deadByNpc.put(snapshot.npcUuid(), snapshot);
                }
            } catch (Exception ignored) {
                // Ignore persistence read issues; runtime tracking still works for newly dead NPCs.
            }
        }
    }

    private void persistSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (deadByNpc.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (DeadLinkedNpcSnapshot snapshot : deadByNpc.values()) {
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(encodeSnapshot(snapshot));
                }
                Files.writeString(persistencePath, builder.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Ignore persistence write issues; runtime tracking remains available.
            }
        }
    }

    private String encodeSnapshot(DeadLinkedNpcSnapshot snapshot) {
        return snapshot.npcUuid()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.ownerId())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.ownerName())
                + FIELD_SEPARATOR + encodeStringArray(snapshot.toolIds())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.roleId())
                + FIELD_SEPARATOR + snapshot.tamed()
                + FIELD_SEPARATOR + encodeNullableString(snapshot.customName())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.displayName())
                + FIELD_SEPARATOR + encodeVector(snapshot.lastKnownPosition())
                + FIELD_SEPARATOR + encodeVector(snapshot.homePosition())
                + FIELD_SEPARATOR + snapshot.diedAtMs()
                + FIELD_SEPARATOR + snapshot.respawnAvailableAtMs()
                + FIELD_SEPARATOR + encodeNullableString(snapshot.breedingConfigId())
                + FIELD_SEPARATOR + encodeNullableDouble(snapshot.breedingHappiness())
                + FIELD_SEPARATOR + snapshot.breedingCooldownUntilMs()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.breedingLastPartnerUuid())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.traitsConfigId())
                + FIELD_SEPARATOR + snapshot.traitsRollSeed()
                + FIELD_SEPARATOR + encodeNullableString(snapshot.traitsValues())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.happinessConfigId())
                + FIELD_SEPARATOR + encodeNullableDouble(snapshot.happinessValue())
                + FIELD_SEPARATOR + snapshot.happinessLastUpdateMs()
                + FIELD_SEPARATOR + encodeNullableString(snapshot.lifeStage())
                + FIELD_SEPARATOR + snapshot.lifeStageBornAtMs()
                + FIELD_SEPARATOR + snapshot.lifeStageAdolescentAtMs()
                + FIELD_SEPARATOR + snapshot.lifeStageAdultAtMs()
                + FIELD_SEPARATOR + snapshot.lifeStageFullyGrownAtMs()
                + FIELD_SEPARATOR + snapshot.lifeStageBabyScale()
                + FIELD_SEPARATOR + snapshot.lifeStageAdolescentScale()
                + FIELD_SEPARATOR + snapshot.lifeStageAdolescentSwitchScale()
                + FIELD_SEPARATOR + snapshot.lifeStageAdultStartScale()
                + FIELD_SEPARATOR + snapshot.lifeStageAdultSwitchScale()
                + FIELD_SEPARATOR + snapshot.lifeStageAdultScale()
                + FIELD_SEPARATOR + snapshot.lifeStageGrowthScalingEnabled()
                + FIELD_SEPARATOR + encodeNullableString(snapshot.attachmentsConfigId())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.attachmentsValues());
    }

    @Nullable
    private DeadLinkedNpcSnapshot parseSnapshot(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length < 12) {
            return null;
        }
        UUID npcUuid = decodeNullableUuid(parts[0]);
        if (npcUuid == null) {
            return null;
        }
        UUID ownerId = decodeNullableUuid(parts[1]);
        String ownerName = decodeNullableString(parts[2]);
        String[] toolIds = sanitizeToolIds(decodeStringArray(parts[3]));
        String roleId = decodeNullableString(parts[4]);
        boolean tamed = Boolean.parseBoolean(parts[5]);
        String customName = decodeNullableString(parts[6]);
        String displayName = decodeNullableString(parts[7]);
        Vector3d lastKnownPosition = decodeVector(parts[8]);
        Vector3d homePosition = decodeVector(parts[9]);
        long diedAtMs = parseLong(parts[10], System.currentTimeMillis());
        long respawnAvailableAtMs = parseLong(parts[11], diedAtMs);
        String breedingConfigId = parts.length > 12 ? decodeNullableString(parts[12]) : null;
        Double breedingHappiness = parts.length > 13 ? parseNullableDouble(parts[13]) : null;
        long breedingCooldownUntilMs = parts.length > 14 ? parseLong(parts[14], 0L) : 0L;
        UUID breedingLastPartnerUuid = parts.length > 15 ? decodeNullableUuid(parts[15]) : null;
        String traitsConfigId = parts.length > 16 ? decodeNullableString(parts[16]) : null;
        long traitsRollSeed = parts.length > 17 ? parseLong(parts[17], 0L) : 0L;
        String traitsValues = parts.length > 18 ? decodeNullableString(parts[18]) : null;
        String happinessConfigId = parts.length > 19 ? decodeNullableString(parts[19]) : null;
        Double happinessValue = parts.length > 20 ? parseNullableDouble(parts[20]) : null;
        long happinessLastUpdateMs = parts.length > 21 ? parseLong(parts[21], 0L) : 0L;
        String lifeStage = parts.length > 22 ? decodeNullableString(parts[22]) : null;
        long lifeStageBornAtMs = parts.length > 23 ? parseLong(parts[23], 0L) : 0L;
        long lifeStageAdolescentAtMs = parts.length > 24 ? parseLong(parts[24], 0L) : 0L;
        long lifeStageAdultAtMs = parts.length > 25 ? parseLong(parts[25], 0L) : 0L;
        boolean legacyLifeStageLayout = parts.length <= 30;
        long lifeStageFullyGrownAtMs = legacyLifeStageLayout
                ? lifeStageAdultAtMs
                : parts.length > 26 ? parseLong(parts[26], 0L) : 0L;
        double lifeStageBabyScale = legacyLifeStageLayout
                ? (parts.length > 26 ? parseDouble(parts[26], 0.55) : 0.55)
                : (parts.length > 27 ? parseDouble(parts[27], 0.55) : 0.55);
        double lifeStageAdolescentScale = legacyLifeStageLayout
                ? (parts.length > 27 ? parseDouble(parts[27], 0.80) : 0.80)
                : (parts.length > 28 ? parseDouble(parts[28], 0.80) : 0.80);
        double lifeStageAdolescentSwitchScale = legacyLifeStageLayout
                ? lifeStageAdolescentScale
                : (parts.length > 29 ? parseDouble(parts[29], 0.80) : 0.80);
        double lifeStageAdultStartScale = legacyLifeStageLayout
                ? lifeStageAdolescentScale
                : (parts.length > 30 ? parseDouble(parts[30], 0.80) : 0.80);
        double lifeStageAdultSwitchScale = legacyLifeStageLayout
                ? (parts.length > 28 ? parseDouble(parts[28], 1.00) : 1.00)
                : (parts.length > 31 ? parseDouble(parts[31], 1.00) : 1.00);
        double lifeStageAdultScale = legacyLifeStageLayout
                ? (parts.length > 28 ? parseDouble(parts[28], 1.00) : 1.00)
                : (parts.length > 32 ? parseDouble(parts[32], 1.00) : 1.00);
        boolean lifeStageGrowthScalingEnabled = legacyLifeStageLayout
                ? (parts.length > 29 && Boolean.parseBoolean(parts[29]))
                : (parts.length > 33 && Boolean.parseBoolean(parts[33]));
        String attachmentsConfigId = parts.length > 34 ? decodeNullableString(parts[34]) : null;
        String attachmentsValues = parts.length > 35 ? decodeNullableString(parts[35]) : null;
        return new DeadLinkedNpcSnapshot(
                npcUuid,
                ownerId,
                ownerName,
                toolIds,
                roleId,
                tamed,
                customName,
                displayName,
                lastKnownPosition,
                homePosition,
                diedAtMs,
                respawnAvailableAtMs,
                breedingConfigId,
                breedingHappiness,
                breedingCooldownUntilMs,
                breedingLastPartnerUuid,
                traitsConfigId,
                traitsRollSeed,
                traitsValues,
                happinessConfigId,
                happinessValue,
                happinessLastUpdateMs,
                lifeStage,
                lifeStageBornAtMs,
                lifeStageAdolescentAtMs,
                lifeStageAdultAtMs,
                lifeStageFullyGrownAtMs,
                lifeStageBabyScale,
                lifeStageAdolescentScale,
                lifeStageAdolescentSwitchScale,
                lifeStageAdultStartScale,
                lifeStageAdultSwitchScale,
                lifeStageAdultScale,
                lifeStageGrowthScalingEnabled,
                attachmentsConfigId,
                attachmentsValues
        );
    }

    private String encodeNullableUuid(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    @Nullable
    private UUID decodeNullableUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeNullableDouble(@Nullable Double value) {
        if (value == null) {
            return "";
        }
        return Double.toString(value);
    }

    @Nullable
    private String decodeNullableString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String out = new String(decoded, StandardCharsets.UTF_8);
            return out.isBlank() ? null : out;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeStringArray(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(ARRAY_SEPARATOR);
            }
            builder.append(encodeNullableString(value));
        }
        return builder.toString();
    }

    private String[] decodeStringArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(ARRAY_SEPARATOR);
        ArrayList<String> out = new ArrayList<>(parts.length);
        for (String value : parts) {
            String decoded = decodeNullableString(value);
            if (decoded == null || decoded.isBlank()) {
                continue;
            }
            out.add(decoded);
        }
        return out.toArray(new String[0]);
    }

    @Nullable
    static String encodeAttachmentSelections(@Nullable Map<String, String> selections) {
        if (selections == null || selections.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : selections.entrySet()) {
            if (entry == null) {
                continue;
            }
            String setId = entry.getKey();
            String optionId = entry.getValue();
            if (setId == null || setId.isBlank() || optionId == null || optionId.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(ARRAY_SEPARATOR);
            }
            builder.append(encodeAttachmentToken(setId))
                    .append(ATTACHMENT_KV_SEPARATOR)
                    .append(encodeAttachmentToken(optionId));
        }
        return builder.length() > 0 ? builder.toString() : null;
    }

    @Nonnull
    static Map<String, String> decodeAttachmentSelections(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> decoded = new HashMap<>();
        String[] parts = raw.split(ARRAY_SEPARATOR);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split(ATTACHMENT_KV_SEPARATOR, 2);
            if (pair.length < 2) {
                continue;
            }
            String setId = decodeAttachmentToken(pair[0]);
            String optionId = decodeAttachmentToken(pair[1]);
            if (setId == null || setId.isBlank() || optionId == null || optionId.isBlank()) {
                continue;
            }
            decoded.put(setId, optionId);
        }
        return CompanionModelAttachmentService.sanitizeAttachmentSelections(decoded);
    }

    @Nullable
    private static String decodeAttachmentToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            String out = new String(decoded, StandardCharsets.UTF_8);
            return out.isBlank() ? null : out;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String encodeAttachmentToken(@Nonnull String value) {
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "";
        }
        return vector.x + VECTOR_SEPARATOR + vector.y + VECTOR_SEPARATOR + vector.z;
    }

    @Nullable
    private Vector3d decodeVector(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] values = raw.split(VECTOR_SEPARATOR, -1);
        if (values.length < 3) {
            return null;
        }
        try {
            return new Vector3d(
                    Double.parseDouble(values[0]),
                    Double.parseDouble(values[1]),
                    Double.parseDouble(values[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nullable
    private Double parseNullableDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean wasDeathRemoval(Ref<EntityStore> reference, RemoveReason reason, Store<EntityStore> store) {
        if (reference != null && reference.isValid() && store != null) {
            try {
                if (store.getArchetype(reference).contains(DeathComponent.getComponentType())) {
                    return true;
                }
            } catch (Exception ignored) {
                // Fall through to reason heuristics.
            }
        }
        if (reason == null) {
            return false;
        }
        String reasonText = reason.toString();
        if (reasonText == null || reasonText.isBlank()) {
            return false;
        }
        String normalized = reasonText.toLowerCase(Locale.ROOT);
        return normalized.contains("death") || normalized.contains("killed");
    }

    private long resolveRespawnCooldownMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config != null ? config.getCommandDeadRespawnCooldownMs() : 0L;
        return Math.max(0L, configured);
    }

    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0 && NPCPlugin.get() != null) {
            String name = NPCPlugin.get().getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String resolveCustomName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType == null) {
            return null;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, nameType);
        if (component == null || component.getName() == null || component.getName().isBlank()) {
            return null;
        }
        return component.getName();
    }

    private String resolveDisplayName(Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      NPCEntity npc,
                                      String roleId,
                                      String customName) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        if (npcRef != null && npcRef.isValid() && store != null) {
            DisplayNameComponent displayName = store.getComponent(npcRef, DisplayNameComponent.getComponentType());
            if (displayName != null && displayName.getDisplayName() != null) {
                String ansi = displayName.getDisplayName().getAnsiMessage();
                if (ansi != null && !ansi.isBlank()) {
                    return ansi;
                }
            }
        }
        if (npc != null) {
            String legacy = npc.getLegacyDisplayName();
            if (legacy != null && !legacy.isBlank()) {
                return legacy;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "Dead companion";
    }

    private String[] sanitizeToolIds(String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * Snapshot of a linked companion that died while linked to one or more command tools.
     */
    public record DeadLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable UUID ownerId,
                                        @Nullable String ownerName,
                                        String[] toolIds,
                                        @Nullable String roleId,
                                        boolean tamed,
                                        @Nullable String customName,
                                        @Nullable String displayName,
                                        @Nullable Vector3d lastKnownPosition,
                                        @Nullable Vector3d homePosition,
                                        long diedAtMs,
                                        long respawnAvailableAtMs,
                                        @Nullable String breedingConfigId,
                                        @Nullable Double breedingHappiness,
                                        long breedingCooldownUntilMs,
                                        @Nullable UUID breedingLastPartnerUuid,
                                        @Nullable String traitsConfigId,
                                        long traitsRollSeed,
                                         @Nullable String traitsValues,
                                         @Nullable String happinessConfigId,
                                         @Nullable Double happinessValue,
                                         long happinessLastUpdateMs,
                                         @Nullable String lifeStage,
                                         long lifeStageBornAtMs,
                                         long lifeStageAdolescentAtMs,
                                         long lifeStageAdultAtMs,
                                         long lifeStageFullyGrownAtMs,
                                         double lifeStageBabyScale,
                                         double lifeStageAdolescentScale,
                                         double lifeStageAdolescentSwitchScale,
                                         double lifeStageAdultStartScale,
                                         double lifeStageAdultSwitchScale,
                                         double lifeStageAdultScale,
                                         boolean lifeStageGrowthScalingEnabled,
                                         @Nullable String attachmentsConfigId,
                                         @Nullable String attachmentsValues) {
        public boolean containsToolId(String toolId) {
            if (toolId == null || toolIds == null || toolIds.length == 0) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isRespawnReady() {
            return System.currentTimeMillis() >= respawnAvailableAtMs;
        }
    }
}
