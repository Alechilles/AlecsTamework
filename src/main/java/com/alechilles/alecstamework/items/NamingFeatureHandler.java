package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ui.TameworkNameInputPage;
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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles naming item interactions and name submission flow (UI first, chat fallback).
 */
public final class NamingFeatureHandler {
    private static final long REQUEST_TIMEOUT_MS = 300000L;
    private static final String CANCEL_TOKEN = "cancel";
    private static final int DEFAULT_UI_NAME_MAX_LENGTH = 32;
    private static final String[] EMPTY_RANDOM_NAME_POOL = new String[0];

    private final NameItemRegistry registry;
    private final NamingEffectService effectService;
    private final NamingNpcInfoService npcInfoService;
    private final ConcurrentHashMap<UUID, PendingNameRequest> pendingByPlayer = new ConcurrentHashMap<>();

    public NamingFeatureHandler(NameItemRegistry registry, TranslationRegistry translationRegistry) {
        this.registry = registry;
        this.effectService = new NamingEffectService();
        this.npcInfoService = new NamingNpcInfoService(translationRegistry);
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

        String roleId = npcInfoService.resolveRoleId(npc);
        if (!isRoleAllowed(roleId, config)) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetNotAllowed");
            return false;
        }

        if (rules.isRequireTamed() && !npcInfoService.isTamed(targetRef, store)) {
            String npcName = npcInfoService.resolveDisplayName(npc);
            sendMessageKey(player, "tamework.ui.notifications.name.requireTamed", npcName);
            return false;
        }

        UUID ownerUuid = npcInfoService.resolveOwnerUuid(targetRef, store);
        UUID playerUuid = player.getUuid();
        if (!NamingOwnershipPolicy.canName(playerUuid, ownerUuid, rules)) {
            if (ownerUuid == null) {
                sendMessageKey(player, "tamework.ui.notifications.name.noOwner");
                return false;
            }
            if (rules.isRequireOwner()) {
                String npcName = npcInfoService.resolveDisplayName(npc);
                String ownerName = npcInfoService.resolveOwnerName(targetRef, store);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "name");
                return false;
            }
        }

        if (isCooldownActive(itemStack, TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, rules.getCooldownMs())) {
            sendMessageKey(player, "tamework.ui.notifications.name.itemCooldown");
            return false;
        }

        boolean hasTameworkName = npcInfoService.hasTameworkName(targetRef, store);
        boolean hasAnyName = npcInfoService.hasAnyName(targetRef, store, npc);
        if (!rules.isAllowRename() && hasTameworkName) {
            sendMessageKey(player, "tamework.ui.notifications.name.validation.alreadyNamed");
            return false;
        }
        if (!rules.isReplaceExisting() && hasAnyName) {
            sendMessageKey(player, "tamework.ui.notifications.name.validation.alreadyHasName");
            return false;
        }

        if (playerUuid == null) {
            return false;
        }
        String[] randomNamePool = resolveRandomNamePool(config);
        PendingNameRequest uiRequest = new PendingNameRequest(
                playerUuid,
                targetRef,
                itemId,
                configIdOverride,
                overrides,
                randomNamePool,
                System.currentTimeMillis(),
                InputMode.Ui,
                UUID.randomUUID()
        );
        pendingByPlayer.put(playerUuid, uiRequest);
        if (openNamingInputPage(player, store, npc, rules, uiRequest)) {
            return true;
        }

        pendingByPlayer.remove(playerUuid, uiRequest);
        PendingNameRequest chatFallbackRequest = uiRequest.withInputMode(InputMode.ChatFallback);
        pendingByPlayer.put(playerUuid, chatFallbackRequest);
        sendMessageKey(player, "tamework.ui.notifications.name.uiUnavailableChatFallback", CANCEL_TOKEN);
        return true;
    }

    // Handles async chat events for the fallback chat naming path.
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
        PendingNameRequest request = pendingByPlayer.get(playerUuid);
        if (request == null || request.inputMode != InputMode.ChatFallback) {
            return;
        }
        if (!pendingByPlayer.remove(playerUuid, request)) {
            return;
        }
        event.setCancelled(true);

        String content = event.getContent();
        if (content == null) {
            return;
        }
        if (isCancelMessage(content)) {
            sendMessageKey(sender, "tamework.ui.notifications.name.cancelled");
            return;
        }

        Ref<EntityStore> playerRef = sender.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Store<EntityStore> store = playerRef.getStore();
        if (store == null || store.getExternalData() == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            Player player = store.getComponent(playerRef, Player.getComponentType());
            if (player == null) {
                return;
            }
            applyNameFromInput(player, request, content);
        });
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

    private boolean openNamingInputPage(Player player,
                                        Store<EntityStore> store,
                                        NPCEntity npc,
                                        NamingRules rules,
                                        PendingNameRequest request) {
        if (player == null || store == null || npc == null || rules == null || request == null) {
            return false;
        }
        if (player.getPageManager() == null) {
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return false;
        }
        String npcName = npcInfoService.resolveDisplayName(npc);
        if (npcName == null || npcName.isBlank()) {
            npcName = LocalizedText.resolve(player, "tamework.ui.nameInput.defaultNpcName");
        }
        String title = LocalizedText.format(player, "tamework.ui.nameInput.titleForNpc", npcName);
        String subtitle = LocalizedText.resolve(player, "tamework.ui.nameInput.subtitle");
        String existingName = npcInfoService.resolveAssignedName(request.npcRef, store, npc);
        int maxLength = rules.getMaxLength() > 0 ? rules.getMaxLength() : DEFAULT_UI_NAME_MAX_LENGTH;
        TameworkNameInputPage page = new TameworkNameInputPage(
                uiPlayerRef,
                title,
                subtitle,
                LocalizedText.resolve(player, "tamework.ui.nameInput.placeholder"),
                existingName,
                maxLength,
                () -> handleUiNameCancelled(player, request.playerUuid, request.requestId),
                input -> handleUiNameSubmitted(player, request.playerUuid, request.requestId, input),
                request.randomNamePool != null && request.randomNamePool.length > 0
                        ? () -> handleUiRandomRequested(player, request.playerUuid, request.requestId)
                        : null
        );
        player.getPageManager().openCustomPage(playerRef, store, page);
        return true;
    }

    private void handleUiNameCancelled(Player player, UUID playerUuid, UUID requestId) {
        if (player == null || playerUuid == null || requestId == null) {
            return;
        }
        PendingNameRequest request = pendingByPlayer.get(playerUuid);
        if (request == null || request.inputMode != InputMode.Ui || !requestId.equals(request.requestId)) {
            return;
        }
        if (!pendingByPlayer.remove(playerUuid, request)) {
            return;
        }
        sendMessageKey(player, "tamework.ui.notifications.name.cancelled");
    }

    private void handleUiNameSubmitted(Player player,
                                       UUID playerUuid,
                                       UUID requestId,
                                       String rawName) {
        if (player == null || playerUuid == null || requestId == null) {
            return;
        }
        PendingNameRequest request = pendingByPlayer.get(playerUuid);
        if (request == null || request.inputMode != InputMode.Ui || !requestId.equals(request.requestId)) {
            return;
        }
        if (!pendingByPlayer.remove(playerUuid, request)) {
            return;
        }
        applyNameFromInput(player, request, rawName);
    }

    @Nullable
    private String handleUiRandomRequested(Player player, UUID playerUuid, UUID requestId) {
        if (player == null || playerUuid == null || requestId == null) {
            return null;
        }
        PendingNameRequest request = pendingByPlayer.get(playerUuid);
        if (request == null || request.inputMode != InputMode.Ui || !requestId.equals(request.requestId)) {
            return null;
        }
        if (isRequestExpired(request)) {
            pendingByPlayer.remove(playerUuid, request);
            sendMessageKey(player, "tamework.ui.notifications.name.requestExpired");
            return null;
        }
        if (request.randomNamePool == null || request.randomNamePool.length == 0) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(request.randomNamePool.length);
        return request.randomNamePool[index];
    }

    private void applyNameFromInput(Player player,
                                    PendingNameRequest request,
                                    String rawName) {
        if (player == null || request == null) {
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null || !playerUuid.equals(request.playerUuid)) {
            return;
        }
        if (isRequestExpired(request)) {
            sendMessageKey(player, "tamework.ui.notifications.name.requestExpired");
            return;
        }
        Ref<EntityStore> playerEntityRef = player.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        if (store == null) {
            return;
        }
        if (request.npcRef == null || !request.npcRef.isValid()) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetUnavailable");
            return;
        }
        NPCEntity npc = store.getComponent(request.npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetUnavailable");
            return;
        }

        ItemStack activeItem = getActiveItem(player);
        if (activeItem == null || activeItem.isEmpty()) {
            sendMessageKey(player, "tamework.ui.notifications.name.holdItemToFinish");
            return;
        }
        String activeItemId = activeItem.getItemId();
        if (activeItemId == null || !activeItemId.equals(request.itemId)) {
            sendMessageKey(player, "tamework.ui.notifications.name.holdItemToFinish");
            return;
        }

        TwNameItemConfig config = resolveConfig(activeItemId, request.configIdOverride);
        NamingRules rules = resolveRules(config, request.overrides);

        if (isCooldownActive(activeItem, TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, rules.getCooldownMs())) {
            sendMessageKey(player, "tamework.ui.notifications.name.itemCooldown");
            return;
        }

        String roleId = npcInfoService.resolveRoleId(npc);
        if (!isRoleAllowed(roleId, config)) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetNotAllowed");
            return;
        }

        if (rules.isRequireTamed() && !npcInfoService.isTamed(request.npcRef, store)) {
            String npcName = npcInfoService.resolveDisplayName(npc);
            sendMessageKey(player, "tamework.ui.notifications.name.requireTamed", npcName);
            return;
        }

        UUID ownerUuid = npcInfoService.resolveOwnerUuid(request.npcRef, store);
        if (!NamingOwnershipPolicy.canName(playerUuid, ownerUuid, rules)) {
            if (ownerUuid == null) {
                sendMessageKey(player, "tamework.ui.notifications.name.noOwner");
                return;
            }
            if (rules.isRequireOwner()) {
                String npcName = npcInfoService.resolveDisplayName(npc);
                String ownerName = npcInfoService.resolveOwnerName(request.npcRef, store);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "name");
                return;
            }
        }

        boolean hasTameworkName = npcInfoService.hasTameworkName(request.npcRef, store);
        boolean hasAnyName = npcInfoService.hasAnyName(request.npcRef, store, npc);
        NameValidation.NameValidationResult validation = NameValidation.validate(
                rawName,
                rules,
                hasTameworkName,
                hasAnyName
        );
        if (!validation.isOk()) {
            String errorKey = validation.getErrorKey();
            if (errorKey != null && !errorKey.isBlank()) {
                sendMessageKey(player, errorKey, validation.getErrorArgs());
            } else {
                sendMessageKey(player, "tamework.ui.notifications.name.validation.notAllowed");
            }
            return;
        }

        String finalName = validation.getNormalizedName();
        if (finalName == null || finalName.isBlank()) {
            sendMessageKey(player, "tamework.ui.notifications.name.validation.notAllowed");
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
        effectService.playSuccessEffects(
                player.getWorld(),
                request.npcRef,
                rules.getParticleSystem(),
                rules.getSoundEvent()
        );
        sendMessageKey(player, "tamework.ui.notifications.name.success", finalName);
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
                .allowUnownedWhenRequireOwner(naming.isAllowUnownedWhenRequireOwner())
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
            if (overrides.getAllowUnownedWhenRequireOwner() != null) {
                builder.allowUnownedWhenRequireOwner(overrides.getAllowUnownedWhenRequireOwner());
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

    @Nonnull
    private String[] resolveRandomNamePool(@Nullable TwNameItemConfig config) {
        if (config == null || config.getNaming() == null) {
            return EMPTY_RANDOM_NAME_POOL;
        }
        String randomNamesId = config.getNaming().getRandomNamesId();
        if (randomNamesId == null || randomNamesId.isBlank()) {
            return EMPTY_RANDOM_NAME_POOL;
        }
        String[] resolved = TwNamesConfig.resolveMergedPoolById(randomNamesId);
        if (resolved.length == 0) {
            return EMPTY_RANDOM_NAME_POOL;
        }
        return resolved;
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

    private void sendMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        player.sendMessage(Message.raw(message));
    }

    private void sendMessageKey(Player player, String key, Object... args) {
        if (player == null || key == null || key.isBlank()) {
            return;
        }
        String message = LocalizedText.format(player, key, args);
        sendMessage(player, message);
    }

    private void sendMessageKey(PlayerRef playerRef, String key, Object... args) {
        if (playerRef == null || key == null || key.isBlank()) {
            return;
        }
        String message = LocalizedText.format(playerRef, key, args);
        if (message == null || message.isBlank()) {
            return;
        }
        playerRef.sendMessage(Message.raw(message));
    }

    private enum InputMode {
        Ui,
        ChatFallback
    }

    private static final class PendingNameRequest {
        private final UUID playerUuid;
        private final Ref<EntityStore> npcRef;
        private final String itemId;
        private final String configIdOverride;
        private final NamingOverrides overrides;
        private final String[] randomNamePool;
        private final long createdMs;
        private final InputMode inputMode;
        private final UUID requestId;

        private PendingNameRequest(UUID playerUuid,
                                   Ref<EntityStore> npcRef,
                                   String itemId,
                                   String configIdOverride,
                                   NamingOverrides overrides,
                                   String[] randomNamePool,
                                   long createdMs,
                                   InputMode inputMode,
                                   UUID requestId) {
            this.playerUuid = playerUuid;
            this.npcRef = npcRef;
            this.itemId = itemId;
            this.configIdOverride = configIdOverride;
            this.overrides = overrides;
            this.randomNamePool = randomNamePool == null ? EMPTY_RANDOM_NAME_POOL : randomNamePool;
            this.createdMs = createdMs;
            this.inputMode = inputMode;
            this.requestId = requestId;
        }

        private PendingNameRequest withInputMode(InputMode mode) {
            return new PendingNameRequest(
                    playerUuid,
                    npcRef,
                    itemId,
                    configIdOverride,
                    overrides,
                    randomNamePool,
                    createdMs,
                    mode,
                    requestId
            );
        }
    }
}
