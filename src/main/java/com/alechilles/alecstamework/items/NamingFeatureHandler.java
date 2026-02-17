package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.math.vector.Vector3d;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles naming item interactions and chat-based naming flow.
 */
public final class NamingFeatureHandler {
    private static final long REQUEST_TIMEOUT_MS = 30000L;
    private static final String CANCEL_TOKEN = "cancel";

    private final NameItemRegistry registry;
    private final TranslationRegistry translationRegistry;
    private final ConcurrentHashMap<UUID, PendingNameRequest> pendingByPlayer = new ConcurrentHashMap<>();

    public NamingFeatureHandler(NameItemRegistry registry, TranslationRegistry translationRegistry) {
        this.registry = registry;
        this.translationRegistry = translationRegistry;
    }

    // Begins a naming request for the player. Runs on the store thread.
    public boolean beginNaming(Player player,
                               ItemStack itemStack,
                               Ref<EntityStore> targetRef,
                               String configIdOverride,
                               NamingOverrides overrides) {
        if (player == null || itemStack == null || itemStack.isEmpty() || targetRef == null) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null || !targetRef.isValid()) {
            return false;
        }
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }

        String itemId = itemStack.getItemId();
        TwNameItemConfig config = resolveConfig(itemId, configIdOverride);
        NamingRules rules = resolveRules(config, overrides);

        String roleId = resolveRoleIdFromNpc(npc);
        if (!isRoleAllowed(roleId, config)) {
            sendMessage(player, "That NPC cannot be named with this item.");
            return false;
        }

        if (rules.isRequireTamed() && !isTamed(targetRef, store)) {
            String npcName = resolveNpcDisplayName(npc);
            sendMessage(player, "You must tame that " + npcName + " before naming it.");
            return false;
        }

        UUID ownerUuid = resolveOwnerUuid(targetRef, store);
        if (rules.isRequireOwner()) {
            if (ownerUuid == null) {
                sendMessage(player, "That NPC does not have an owner.");
                return false;
            }
            UUID playerUuid = player.getUuid();
            if (playerUuid == null || !ownerUuid.equals(playerUuid)) {
                String npcName = resolveNpcDisplayName(npc);
                String ownerName = resolveOwnerName(targetRef, store);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "name");
                return false;
            }
        }

        if (isCooldownActive(itemStack, TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, rules.getCooldownMs())) {
            sendMessage(player, "That naming item is on cooldown.");
            return false;
        }

        boolean hasTameworkName = hasTameworkName(targetRef, store);
        boolean hasAnyName = hasAnyName(targetRef, store, npc);
        if (!rules.isAllowRename() && hasTameworkName) {
            sendMessage(player, "This NPC has already been named.");
            return false;
        }
        if (!rules.isReplaceExisting() && hasAnyName) {
            sendMessage(player, "This NPC already has a name.");
            return false;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }
        PendingNameRequest request = new PendingNameRequest(
                playerUuid,
                targetRef,
                itemId,
                configIdOverride,
                overrides,
                System.currentTimeMillis()
        );
        pendingByPlayer.put(playerUuid, request);
        sendMessage(player, "Type a name in chat to name this NPC. Type 'cancel' to cancel.");
        return true;
    }

    // Handles async chat events to capture naming input.
    public void onPlayerChat(PlayerChatEvent event) {
        if (event == null) {
            return;
        }
        PlayerRef sender = event.getSender();
        if (sender == null) {
            return;
        }
        UUID playerUuid = sender.getUuid();
        if (playerUuid == null) {
            return;
        }
        PendingNameRequest request = pendingByPlayer.remove(playerUuid);
        if (request == null) {
            return;
        }
        event.setCancelled(true);

        String content = event.getContent();
        if (content == null) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        if (isCancelMessage(content)) {
            pendingByPlayer.remove(playerUuid);
            sender.sendMessage(Message.raw("Naming cancelled."));
            return;
        }

        Ref<EntityStore> playerRef = sender.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        world.execute(() -> applyNameFromChat(sender, playerRef, request, content));
    }

    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        UUID playerUuid = event.getPlayerRef().getUuid();
        if (playerUuid != null) {
            pendingByPlayer.remove(playerUuid);
        }
    }

    private void applyNameFromChat(PlayerRef playerRef,
                                   Ref<EntityStore> playerEntityRef,
                                   PendingNameRequest request,
                                   String rawName) {
        if (playerRef == null || playerEntityRef == null || request == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null || !playerUuid.equals(request.playerUuid)) {
            return;
        }
        if (isRequestExpired(request)) {
            pendingByPlayer.remove(playerUuid);
            playerRef.sendMessage(Message.raw("Naming request expired. Use the item again."));
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        if (store == null) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        if (!playerEntityRef.isValid()) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) {
            pendingByPlayer.remove(playerUuid);
            return;
        }
        if (request.npcRef == null || !request.npcRef.isValid()) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "That NPC is no longer available.");
            return;
        }
        NPCEntity npc = store.getComponent(request.npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "That NPC is no longer available.");
            return;
        }

        ItemStack activeItem = getActiveItem(player);
        if (activeItem == null || activeItem.isEmpty()) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "Hold the naming item to finish naming.");
            return;
        }
        String activeItemId = activeItem.getItemId();
        if (activeItemId == null || !activeItemId.equals(request.itemId)) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "Hold the naming item to finish naming.");
            return;
        }

        TwNameItemConfig config = resolveConfig(activeItemId, request.configIdOverride);
        NamingRules rules = resolveRules(config, request.overrides);

        if (isCooldownActive(activeItem, TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, rules.getCooldownMs())) {
            sendMessage(player, "That naming item is on cooldown.");
            return;
        }

        String roleId = resolveRoleIdFromNpc(npc);
        if (!isRoleAllowed(roleId, config)) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "That NPC cannot be named with this item.");
            return;
        }

        if (rules.isRequireTamed() && !isTamed(request.npcRef, store)) {
            pendingByPlayer.remove(playerUuid);
            String npcName = resolveNpcDisplayName(npc);
            sendMessage(player, "You must tame that " + npcName + " before naming it.");
            return;
        }

        UUID ownerUuid = resolveOwnerUuid(request.npcRef, store);
        if (rules.isRequireOwner()) {
            if (ownerUuid == null) {
                pendingByPlayer.remove(playerUuid);
                sendMessage(player, "That NPC does not have an owner.");
                return;
            }
            if (!ownerUuid.equals(playerUuid)) {
                pendingByPlayer.remove(playerUuid);
                String npcName = resolveNpcDisplayName(npc);
                String ownerName = resolveOwnerName(request.npcRef, store);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "name");
                return;
            }
        }

        boolean hasTameworkName = hasTameworkName(request.npcRef, store);
        boolean hasAnyName = hasAnyName(request.npcRef, store, npc);
        NameValidation.NameValidationResult validation = NameValidation.validate(
                rawName,
                rules,
                hasTameworkName,
                hasAnyName
        );
        if (!validation.isOk()) {
            pendingByPlayer.remove(playerUuid);
            String message = validation.getErrorMessage();
            sendMessage(player, message != null ? message : "That name is not allowed.");
            return;
        }

        String finalName = validation.getNormalizedName();
        if (finalName == null || finalName.isBlank()) {
            pendingByPlayer.remove(playerUuid);
            sendMessage(player, "That name is not allowed.");
            return;
        }

        EntitySupport.setDisplayName(request.npcRef, finalName, store);
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type != null) {
            store.putComponent(
                    request.npcRef,
                    type,
                    new TameworkNpcNameComponent(
                            finalName,
                            ownerUuid != null ? ownerUuid : playerUuid,
                            System.currentTimeMillis(),
                            TameworkNpcNameComponent.NameSource.Player
                    )
            );
        }

        applyItemChanges(player, rules);
        spawnSuccessEffects(player.getWorld(), request.npcRef, rules);
        sendMessage(player, "NPC named " + finalName + ".");
        pendingByPlayer.remove(playerUuid);
    }

    private boolean isRequestExpired(PendingNameRequest request) {
        return System.currentTimeMillis() - request.createdMs > REQUEST_TIMEOUT_MS;
    }

    private boolean isCancelMessage(String content) {
        return content.trim().equalsIgnoreCase(CANCEL_TOKEN);
    }

    private NamingRules resolveRules(TwNameItemConfig config, NamingOverrides overrides) {
        NamingRules.Builder builder = NamingRules.builder();
        if (config != null && config.getNaming() != null) {
            TwNameItemConfig.NamingSettings naming = config.getNaming();
            builder.requireTamed(naming.isRequireTamed())
                .requireOwner(naming.isRequireOwner())
                .allowRename(naming.isAllowRename())
                .replaceExisting(naming.isReplaceExisting())
                .consumeItem(naming.isConsumeItem())
                .trimWhitespace(naming.isTrimWhitespace())
                .minLength(naming.getMinLength())
                .maxLength(naming.getMaxLength())
                .allowedChars(naming.getAllowedChars())
                .cooldownMs(naming.getCooldownMs())
                .soundEvent(naming.getSoundEvent())
                .particleSystem(naming.getParticleSystem());
        }
        if (overrides != null) {
            if (overrides.getRequireTamed() != null) {
                builder.requireTamed(overrides.getRequireTamed());
            }
            if (overrides.getRequireOwner() != null) {
                builder.requireOwner(overrides.getRequireOwner());
            }
            if (overrides.getAllowRename() != null) {
                builder.allowRename(overrides.getAllowRename());
            }
            if (overrides.getReplaceExisting() != null) {
                builder.replaceExisting(overrides.getReplaceExisting());
            }
            if (overrides.getConsumeItem() != null) {
                builder.consumeItem(overrides.getConsumeItem());
            }
            if (overrides.getTrimWhitespace() != null) {
                builder.trimWhitespace(overrides.getTrimWhitespace());
            }
            if (overrides.getMinLength() != null) {
                builder.minLength(overrides.getMinLength());
            }
            if (overrides.getMaxLength() != null) {
                builder.maxLength(overrides.getMaxLength());
            }
            if (overrides.getAllowedChars() != null) {
                builder.allowedChars(overrides.getAllowedChars());
            }
            if (overrides.getCooldownMs() != null) {
                builder.cooldownMs(overrides.getCooldownMs());
            }
        }
        return builder.build();
    }

    private TwNameItemConfig resolveConfig(String itemId, String configIdOverride) {
        if (configIdOverride != null && !configIdOverride.isBlank()) {
            if (TwNameItemConfig.getAssetMap() != null) {
                TwNameItemConfig override = TwNameItemConfig.getAssetMap().getAsset(configIdOverride);
                if (override != null) {
                    return override;
                }
            }
        }
        if (registry == null || itemId == null || itemId.isBlank()) {
            return null;
        }
        return registry.get(itemId);
    }

    private boolean isRoleAllowed(String roleId, TwNameItemConfig config) {
        if (config == null) {
            return true;
        }
        TwNameItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == null) {
            return true;
        }
        switch (allowed.getMode()) {
            case Allowlist:
                if (roleId == null || roleId.isBlank()) {
                    return false;
                }
                String[] allow = allowed.getAllowlist();
                if (allow == null || allow.length == 0) {
                    return false;
                }
                for (String value : allow) {
                    if (roleId.equals(value)) {
                        return true;
                    }
                }
                return false;
            case Denylist:
                String[] deny = allowed.getDenylist();
                if (deny == null || deny.length == 0) {
                    return true;
                }
                if (roleId == null || roleId.isBlank()) {
                    return true;
                }
                for (String value : deny) {
                    if (roleId.equals(value)) {
                        return false;
                    }
                }
                return true;
            case AllowAll:
            default:
                return true;
        }
    }

    private String resolveRoleIdFromNpc(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                return nameKey;
            }
        }
        return null;
    }

    private boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkTamedComponent component = store.getComponent(npcRef, type);
        return component != null && component.isTamed();
    }

    private UUID resolveOwnerUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return component != null ? component.getOwnerId() : null;
    }

    private String resolveOwnerName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return component != null ? component.getOwnerName() : null;
    }

    private boolean hasTameworkName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, type);
        return component != null && component.getName() != null && !component.getName().isBlank();
    }

    private boolean hasAnyName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (hasTameworkName(npcRef, store)) {
            return true;
        }
        DisplayNameComponent displayName = store.getComponent(npcRef, DisplayNameComponent.getComponentType());
        if (displayName != null && displayName.getDisplayName() != null) {
            if (!displayName.getDisplayName().getAnsiMessage().isEmpty()) {
                return true;
            }
        }
        String legacy = npc != null ? npc.getLegacyDisplayName() : null;
        return legacy != null && !legacy.isBlank();
    }

    private ItemStack getActiveItem(Player player) {
        if (player == null) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        ItemStack stack = inventory.getActiveHotbarItem();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack;
    }

    private void applyItemChanges(Player player, NamingRules rules) {
        if (player == null || rules == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        byte slot = inventory.getActiveHotbarSlot();
        if (slot == Inventory.INACTIVE_SLOT_INDEX) {
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return;
        }
        ItemStack current = hotbar.getItemStack(slot);
        if (current == null || current.isEmpty()) {
            return;
        }

        if (rules.isConsumeItem()) {
            hotbar.removeItemStackFromSlot((short) slot, 1);
        }

        ItemStack after = hotbar.getItemStack(slot);
        if (after == null || after.isEmpty()) {
            return;
        }
        if (rules.getCooldownMs() > 0) {
            ItemStack updated = applyCooldown(after, TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, rules.getCooldownMs());
            hotbar.setItemStackForSlot((short) slot, updated);
        }
    }

    private void spawnSuccessEffects(World world, Ref<EntityStore> targetRef, NamingRules rules) {
        if (world == null || targetRef == null || !targetRef.isValid() || rules == null) {
            return;
        }
        String particleSystem = rules.getParticleSystem();
        String soundEvent = rules.getSoundEvent();
        if ((particleSystem == null || particleSystem.isBlank())
                && (soundEvent == null || soundEvent.isBlank())) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (particleSystem != null && !particleSystem.isBlank()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        }
        if (soundEvent != null && !soundEvent.isBlank()) {
            int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
            if (soundEventIndex > 0) {
                SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, position, store);
            }
        }
    }

    private boolean isCooldownActive(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return false;
        }
        Long until = itemStack.getFromMetadataOrNull(key, Codec.LONG);
        if (until == null) {
            return false;
        }
        return until > System.currentTimeMillis();
    }

    private ItemStack applyCooldown(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return itemStack;
        }
        long until = System.currentTimeMillis() + cooldownMs;
        return itemStack.withMetadata(key, Codec.LONG, until);
    }

    private String resolveNpcDisplayName(NPCEntity npc) {
        if (npc == null) {
            return "pet";
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin != null) {
            int roleIndex = npc.getRoleIndex();
            if (roleIndex >= 0) {
                String nameKey = npcPlugin.getName(roleIndex);
                if (nameKey != null && translationRegistry != null) {
                    String translated = translationRegistry.get(nameKey);
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                    if (!nameKey.contains(".")) {
                        String derivedKey = "npcRoles." + nameKey + ".name";
                        translated = translationRegistry.get(derivedKey);
                        if (translated != null && !translated.isBlank()) {
                            return translated;
                        }
                    }
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            if (translationRegistry != null) {
                String derivedKey = "npcRoles." + roleName + ".name";
                String translated = translationRegistry.get(derivedKey);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            return roleName;
        }
        return "pet";
    }

    private void sendMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        player.sendMessage(Message.raw(message));
    }

    private static final class PendingNameRequest {
        private final UUID playerUuid;
        private final Ref<EntityStore> npcRef;
        private final String itemId;
        private final String configIdOverride;
        private final NamingOverrides overrides;
        private final long createdMs;

        private PendingNameRequest(UUID playerUuid,
                                   Ref<EntityStore> npcRef,
                                   String itemId,
                                   String configIdOverride,
                                   NamingOverrides overrides,
                                   long createdMs) {
            this.playerUuid = playerUuid;
            this.npcRef = npcRef;
            this.itemId = itemId;
            this.configIdOverride = configIdOverride;
            this.overrides = overrides;
            this.createdMs = createdMs;
        }
    }
}
