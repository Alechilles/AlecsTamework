package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.companioncard.CardRenameAvailability;
import com.alechilles.alecstamework.companioncard.CompanionCardRenameMode;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ui.TameworkNameInputPage;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.DisplayNameSupport;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles naming item interactions and name submission flow (UI first, chat fallback), plus renaming from the
 * companion card, which needs no item in hand.
 */
public final class NamingFeatureHandler {
    private static final long REQUEST_TIMEOUT_MS = 300000L;
    private static final String CANCEL_TOKEN = "cancel";
    private static final int DEFAULT_UI_NAME_MAX_LENGTH = 32;
    private static final int RANDOM_NAME_ATTEMPTS = 8;
    private static final String[] EMPTY_RANDOM_NAME_POOL = new String[0];
    /** The {@code TwNameItemConfig} Naming defaults: card Free mode names with these, and nothing is consumed. */
    private static final NamingRules DEFAULT_RULES = NamingRules.builder().build();
    private static final Runnable NO_OP = () -> {
    };

    private final NameItemRegistry registry;
    private final NamingEffectService effectService;
    private final NamingNpcInfoService npcInfoService;
    private final ConcurrentHashMap<UUID, PendingNameRequest> pendingByPlayer = new ConcurrentHashMap<>();
    @Nullable
    private volatile BiConsumer<Ref<EntityStore>, Store<EntityStore>> nameClearedListener;

    public NamingFeatureHandler(NameItemRegistry registry, TranslationRegistry translationRegistry) {
        this.registry = registry;
        this.effectService = new NamingEffectService();
        this.npcInfoService = new NamingNpcInfoService(translationRegistry);
    }

    /**
     * Sets the hook told when a player clears a name, so the companion record drops it too.
     * Removing the name component alone does not: a body can lose it without being renamed.
     */
    public void setNameClearedListener(@Nullable BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener) {
        this.nameClearedListener = listener;
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

        TargetFacts facts = targetFacts(targetRef, store, npc);
        UUID playerUuid = player.getUuid();
        Denial denial = identityDenial(config, rules, facts, playerUuid);
        if (denial != null) {
            sendDenial(player, denial, targetRef, store, npc, facts.ownerUuid());
            return false;
        }

        if (NamingItemSlots.isCooldownActive(itemStack, rules.getCooldownMs())) {
            sendMessageKey(player, "tamework.ui.notifications.name.itemCooldown");
            return false;
        }

        denial = existingNameDenial(rules, facts);
        if (denial != null) {
            sendDenial(player, denial, targetRef, store, npc, facts.ownerUuid());
            return false;
        }

        if (playerUuid == null) {
            return false;
        }
        String gender = CompanionGenderService.resolveGender(targetRef, store, facts.roleId(), null);
        String[] randomNamePool = resolveRandomNamePool(config, gender);
        PendingNameRequest uiRequest = new PendingNameRequest(
                playerUuid,
                targetRef,
                itemId,
                configIdOverride,
                overrides,
                randomNamePool,
                System.currentTimeMillis(),
                InputMode.Ui,
                UUID.randomUUID(),
                RequestSource.HELD
        );
        pendingByPlayer.put(playerUuid, uiRequest);
        if (openNamingInputPage(player, store, npc, rules, uiRequest, NO_OP)) {
            return true;
        }

        pendingByPlayer.remove(playerUuid, uiRequest);
        PendingNameRequest chatFallbackRequest = uiRequest.withInputMode(InputMode.ChatFallback);
        pendingByPlayer.put(playerUuid, chatFallbackRequest);
        sendMessageKey(player, "tamework.ui.notifications.name.uiUnavailableChatFallback", CANCEL_TOKEN);
        return true;
    }

    /**
     * Whether the companion card's rename pencil can rename {@code npcRef} for this player. Read-only and cheap
     * (one pass over the carried inventory in Item mode); call on the world thread that owns {@code store}.
     * Free mode and the not-allowed check use the default naming rules; Item mode needs a carried stack whose
     * naming config allows this target and whose cooldown has ended.
     */
    @Nonnull
    public CardRenameAvailability cardRenameAvailability(@Nonnull Player player,
                                                         @Nonnull Ref<EntityStore> playerRef,
                                                         @Nonnull Ref<EntityStore> npcRef,
                                                         @Nonnull Store<EntityStore> store,
                                                         @Nonnull CompanionCardRenameMode mode) {
        if (mode == CompanionCardRenameMode.OFF) {
            return CardRenameAvailability.OFF;
        }
        UUID playerUuid = player.getUuid();
        NPCEntity npc = loadedNpc(npcRef, store);
        if (playerUuid == null || npc == null) {
            return CardRenameAvailability.NOT_ALLOWED;
        }
        TargetFacts facts = targetFacts(npcRef, store, npc);
        if (mode == CompanionCardRenameMode.ITEM && findCardItem(playerRef, store, facts, playerUuid) != null) {
            return CardRenameAvailability.AVAILABLE;
        }
        if (cardDenial(null, DEFAULT_RULES, facts, playerUuid) != null) {
            return CardRenameAvailability.NOT_ALLOWED;
        }
        return mode == CompanionCardRenameMode.ITEM
                ? CardRenameAvailability.NEEDS_ITEM
                : CardRenameAvailability.AVAILABLE;
    }

    /**
     * Opens the name input page for {@code npcRef} from the companion card. Item mode uses the first carried
     * naming item that can rename this target (hotbar first in slot order, then storage, then backpack) and that
     * item's rules; at submit it re-finds a usable stack of the same item, then consumes and starts its cooldown.
     * Free mode uses the default rules and changes no item. A refusal is told to the player.
     *
     * <p>{@code afterClose} runs exactly once, as a later task on the world thread, after the page closes for any
     * reason (submit, clear, cancel, dismiss, or another page replacing it), or when no page opens (refused or
     * failed to open). A dismiss can come from another page replacing this one, or from the player leaving, so the
     * callback should check that the player is still valid and has no other page open before reopening the card.
     * Call on the world thread that owns {@code store}.</p>
     */
    public void beginCardNaming(@Nonnull Player player,
                                @Nonnull Ref<EntityStore> playerRef,
                                @Nonnull Ref<EntityStore> npcRef,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull CompanionCardRenameMode mode,
                                @Nullable Runnable afterClose) {
        Runnable closed = runOnceLater(store, afterClose);
        if (!openCardNaming(player, playerRef, npcRef, store, mode, closed)) {
            closed.run();
        }
    }

    /** True when the name page opened; {@code closed} then runs when it closes. */
    private boolean openCardNaming(Player player,
                                   Ref<EntityStore> playerRef,
                                   Ref<EntityStore> npcRef,
                                   Store<EntityStore> store,
                                   CompanionCardRenameMode mode,
                                   Runnable closed) {
        if (mode == CompanionCardRenameMode.OFF) {
            return false;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }
        NPCEntity npc = loadedNpc(npcRef, store);
        if (npc == null) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetUnavailable");
            return false;
        }
        TargetFacts facts = targetFacts(npcRef, store, npc);

        TwNameItemConfig config = null;
        NamingRules rules = DEFAULT_RULES;
        String itemId = null;
        RequestSource source = RequestSource.CARD_FREE;
        if (mode == CompanionCardRenameMode.ITEM) {
            CardItem item = findCardItem(playerRef, store, facts, playerUuid);
            if (item == null) {
                Denial denial = cardDenial(null, DEFAULT_RULES, facts, playerUuid);
                if (denial != null) {
                    sendDenial(player, denial, npcRef, store, npc, facts.ownerUuid());
                } else {
                    sendMissingItemMessage(player, playerRef, store,
                            stack -> cardItemFor(stack, facts, playerUuid) != null);
                }
                return false;
            }
            config = item.config();
            rules = item.rules();
            itemId = item.slot().stack().getItemId();
            source = RequestSource.CARD_ITEM;
        }

        Denial denial = cardDenial(config, rules, facts, playerUuid);
        if (denial != null) {
            sendDenial(player, denial, npcRef, store, npc, facts.ownerUuid());
            return false;
        }

        String gender = CompanionGenderService.resolveGender(npcRef, store, facts.roleId(), null);
        PendingNameRequest request = new PendingNameRequest(
                playerUuid,
                npcRef,
                itemId,
                null,
                null,
                resolveRandomNamePool(config, gender),
                System.currentTimeMillis(),
                InputMode.Ui,
                UUID.randomUUID(),
                source
        );
        pendingByPlayer.put(playerUuid, request);
        if (openNamingInputPage(player, store, npc, rules, request, closed)) {
            return true;
        }
        // No chat fallback here: the card reopens right away and would take the player's input.
        pendingByPlayer.remove(playerUuid, request);
        sendMessageKey(player, "tamework.ui.notifications.name.uiUnavailable");
        return false;
    }

    /** The first carried naming item that can rename this target now, with its config and rules. */
    @Nullable
    private CardItem findCardItem(Ref<EntityStore> playerRef,
                                  Store<EntityStore> store,
                                  TargetFacts facts,
                                  UUID playerUuid) {
        NamingItemSlots.Slot slot = NamingItemSlots.findCarried(store, playerRef, stack -> {
            CardItem candidate = cardItemFor(stack, facts, playerUuid);
            return candidate != null && !NamingItemSlots.isCooldownActive(stack, candidate.rules().getCooldownMs());
        });
        if (slot == null) {
            return null;
        }
        CardItem item = cardItemFor(slot.stack(), facts, playerUuid);
        return item != null ? new CardItem(slot, item.config(), item.rules()) : null;
    }

    /** The stack's naming config and rules when its config allows renaming this target, ignoring cooldown. */
    @Nullable
    private CardItem cardItemFor(ItemStack stack, TargetFacts facts, UUID playerUuid) {
        TwNameItemConfig config = resolveConfig(stack.getItemId(), null);
        if (config == null) {
            return null;
        }
        NamingRules rules = resolveRules(config, null);
        if (cardDenial(config, rules, facts, playerUuid) != null) {
            return null;
        }
        return new CardItem(null, config, rules);
    }

    /** Tells the player why no stack fits: cooldown when a matching stack is carried, otherwise none carried. */
    private void sendMissingItemMessage(Player player,
                                        Ref<EntityStore> playerRef,
                                        Store<EntityStore> store,
                                        Predicate<ItemStack> matching) {
        boolean carried = NamingItemSlots.findCarried(store, playerRef, matching) != null;
        sendMessageKey(player, carried
                ? "tamework.ui.notifications.name.itemCooldown"
                : "tamework.ui.notifications.name.needsItem");
    }

    @Nullable
    private static NPCEntity loadedNpc(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
            return null;
        }
        return store.getComponent(npcRef, NPCEntity.getComponentType());
    }

    /**
     * Wraps {@code afterClose} so it runs at most once, as a later task on the store's world thread. Deferring
     * lets a page that replaced the name page finish opening first. If the world takes no more tasks (it is
     * stopping), it runs inline instead.
     */
    @Nonnull
    private static Runnable runOnceLater(Store<EntityStore> store, @Nullable Runnable afterClose) {
        if (afterClose == null) {
            return NO_OP;
        }
        AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
            if (world != null) {
                try {
                    world.execute(afterClose);
                    return;
                } catch (RuntimeException ignored) {
                    // The world is stopping; run it here so it still runs once.
                }
            }
            afterClose.run();
        };
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
        try {
            world.execute(() -> {
                // The player may have left or changed world since the chat event.
                if (!playerRef.isValid() || playerRef.getStore() != store) {
                    return;
                }
                Player player = store.getComponent(playerRef, Player.getComponentType());
                if (player == null) {
                    return;
                }
                applyNameFromInput(player, request, content);
            });
        } catch (RuntimeException ignored) {
            // The world is stopping and takes no more tasks; the pending name is dropped.
        }
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
                                        PendingNameRequest request,
                                        Runnable closed) {
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
                () -> runThenClose(() -> handleUiNameCancelled(player, request.playerUuid, request.requestId), closed),
                input -> runThenClose(
                        () -> handleUiNameSubmitted(player, request.playerUuid, request.requestId, input), closed),
                request.randomNamePool != null && request.randomNamePool.length > 0
                        ? () -> handleUiRandomRequested(player, request.playerUuid, request.requestId)
                        : null,
                existingName != null && canClearName(rules)
                        ? () -> runThenClose(
                                () -> handleUiNameCleared(player, request.playerUuid, request.requestId), closed)
                        : null
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkNameInputPage",
                            "name_item",
                            "open",
                            "Failed to open Tamework name input page."
                    ).build()
            );
            return false;
        }
    }

    private static void runThenClose(Runnable action, Runnable closed) {
        try {
            action.run();
        } finally {
            closed.run();
        }
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

    private void handleUiNameCleared(Player player, UUID playerUuid, UUID requestId) {
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
        clearName(player, request);
    }

    /** Clearing replaces an existing name with none, so it needs both rename permissions. */
    private static boolean canClearName(NamingRules rules) {
        return rules != null && rules.isAllowRename() && rules.isReplaceExisting();
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
        // The random button must not suggest a name the server's filter would then reject.
        NameFilter filter = NameFilter.current();
        for (int attempt = 0; attempt < RANDOM_NAME_ATTEMPTS; attempt++) {
            int index = ThreadLocalRandom.current().nextInt(request.randomNamePool.length);
            String candidate = request.randomNamePool[index];
            if (!filter.isBlocked(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void applyNameFromInput(Player player,
                                    PendingNameRequest request,
                                    String rawName) {
        NamingTarget target = resolveNamingTarget(player, request);
        if (target == null) {
            return;
        }
        Store<EntityStore> store = target.store();
        NPCEntity npc = target.npc();
        NamingRules rules = target.rules();
        UUID ownerUuid = target.ownerUuid();
        UUID playerUuid = request.playerUuid;

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

        if (NameFilter.current().isBlocked(finalName)) {
            sendMessageKey(player, "tamework.ui.notifications.name.validation.notAllowed");
            return;
        }

        DisplayNameSupport.setDisplayName(request.npcRef, finalName, store);
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

        applyItemChanges(target, rules.isConsumeItem());
        effectService.playSuccessEffects(
                player.getWorld(),
                request.npcRef,
                rules.getParticleSystem(),
                rules.getSoundEvent()
        );
        sendMessageKey(player, "tamework.ui.notifications.name.success", finalName);
    }

    /** Removes the companion's name. The naming item is not consumed; only its cooldown applies. */
    private void clearName(Player player, PendingNameRequest request) {
        NamingTarget target = resolveNamingTarget(player, request);
        if (target == null) {
            return;
        }
        if (!canClearName(target.rules())) {
            sendMessageKey(player, "tamework.ui.notifications.name.validation.alreadyNamed");
            return;
        }
        Store<EntityStore> store = target.store();
        DisplayNameSupport.setDisplayName(request.npcRef, null, true, store);
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type != null) {
            store.tryRemoveComponent(request.npcRef, type);
        }
        // A ridden companion restores its hidden name on dismount; forget the cleared one.
        ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedType =
                TameworkMountedNameplateComponent.getComponentType();
        TameworkMountedNameplateComponent mounted = mountedType != null
                ? store.getComponent(request.npcRef, mountedType)
                : null;
        if (mounted != null) {
            mounted.setCachedDisplayName(null);
        }
        BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener = nameClearedListener;
        if (listener != null) {
            listener.accept(request.npcRef, store);
        }
        applyItemChanges(target, false);
        sendMessageKey(player, "tamework.ui.notifications.name.cleared");
    }

    /**
     * Re-checks everything a pending request depends on (target, naming item, cooldown, role, tamed
     * state and ownership) at submission time. Sends the player the reason and returns null on failure.
     */
    @Nullable
    private NamingTarget resolveNamingTarget(Player player, PendingNameRequest request) {
        if (player == null || request == null) {
            return null;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null || !playerUuid.equals(request.playerUuid)) {
            return null;
        }
        if (isRequestExpired(request)) {
            sendMessageKey(player, "tamework.ui.notifications.name.requestExpired");
            return null;
        }
        Ref<EntityStore> playerEntityRef = player.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        if (store == null) {
            return null;
        }
        if (request.npcRef == null || !request.npcRef.isValid()) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetUnavailable");
            return null;
        }
        NPCEntity npc = store.getComponent(request.npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            sendMessageKey(player, "tamework.ui.notifications.name.targetUnavailable");
            return null;
        }

        NamingItem item = resolveSubmitItem(player, playerEntityRef, store, request);
        if (item == null) {
            return null;
        }
        NamingRules rules = item.rules();
        TargetFacts facts = targetFacts(request.npcRef, store, npc);
        Denial denial = identityDenial(item.config(), rules, facts, playerUuid);
        if (denial != null) {
            sendDenial(player, denial, request.npcRef, store, npc, facts.ownerUuid());
            return null;
        }
        return new NamingTarget(store, npc, rules, facts.ownerUuid(), item.container(), item.slot());
    }

    /**
     * The naming item a submission spends and the rules it names with. Held: the active hotbar item must still be
     * the one used. Card Item: any carried stack of the same item off cooldown. Card Free: default rules, no item.
     * Sends the player the reason and returns null when the item is gone or cooling down.
     */
    @Nullable
    private NamingItem resolveSubmitItem(Player player,
                                         Ref<EntityStore> playerRef,
                                         Store<EntityStore> store,
                                         PendingNameRequest request) {
        if (request.source == RequestSource.CARD_FREE) {
            return new NamingItem(null, DEFAULT_RULES, null, (short) -1);
        }
        if (request.source == RequestSource.CARD_ITEM) {
            String itemId = request.itemId;
            TwNameItemConfig config = itemId != null ? resolveConfig(itemId, null) : null;
            if (config == null) {
                // The naming config was removed by a reload since the page opened.
                sendMessageKey(player, "tamework.ui.notifications.name.needsItem");
                return null;
            }
            NamingRules rules = resolveRules(config, null);
            NamingItemSlots.Slot slot = NamingItemSlots.findCarried(store, playerRef, stack ->
                    itemId.equals(stack.getItemId()) && !NamingItemSlots.isCooldownActive(stack, rules.getCooldownMs()));
            if (slot == null) {
                sendMissingItemMessage(player, playerRef, store, stack -> itemId.equals(stack.getItemId()));
                return null;
            }
            return new NamingItem(config, rules, slot.container(), slot.index());
        }

        ItemStack activeItem = getActiveItem(player);
        if (activeItem == null || activeItem.isEmpty()) {
            sendMessageKey(player, "tamework.ui.notifications.name.holdItemToFinish");
            return null;
        }
        String activeItemId = activeItem.getItemId();
        if (activeItemId == null || !activeItemId.equals(request.itemId)) {
            sendMessageKey(player, "tamework.ui.notifications.name.holdItemToFinish");
            return null;
        }

        TwNameItemConfig config = resolveConfig(activeItemId, request.configIdOverride);
        NamingRules rules = resolveRules(config, request.overrides);

        if (NamingItemSlots.isCooldownActive(activeItem, rules.getCooldownMs())) {
            sendMessageKey(player, "tamework.ui.notifications.name.itemCooldown");
            return null;
        }
        return new NamingItem(
                config,
                rules,
                PlayerInventoryAccess.getHotbar(player),
                PlayerInventoryAccess.getActiveHotbarSlot(player)
        );
    }

    /** Reads what the naming checks need from the target. Read-only. */
    private TargetFacts targetFacts(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        return new TargetFacts(
                npcInfoService.resolveRoleId(npc),
                npcInfoService.isTamed(npcRef, store),
                npcInfoService.resolveOwnerUuid(npcRef, store),
                npcInfoService.hasTameworkName(npcRef, store),
                npcInfoService.hasAnyName(npcRef, store, npc),
                npcRef,
                store
        );
    }

    /** Role filter, tamed state and ownership, in the order the held item checks them. */
    @Nullable
    private Denial identityDenial(@Nullable TwNameItemConfig config,
                                  NamingRules rules,
                                  TargetFacts facts,
                                  @Nullable UUID playerUuid) {
        if (!isRoleAllowed(facts.roleId(), config)) {
            return Denial.ROLE_NOT_ALLOWED;
        }
        if (rules.isRequireTamed() && !facts.tamed()) {
            return Denial.NOT_TAMED;
        }
        boolean mayRename = CompanionAccess.allows(
                facts.ownerUuid(), playerUuid, facts.npcRef(), facts.store(), AccessAction.RENAME);
        if (!NamingOwnershipPolicy.canName(facts.ownerUuid(), rules, mayRename)) {
            // With an owner, the policy refuses only when RequireOwner is set and the player may not rename it.
            return facts.ownerUuid() == null ? Denial.NO_OWNER : Denial.NOT_OWNER;
        }
        return null;
    }

    /** AllowRename for a Tamework-named target and ReplaceExisting for any named target. */
    @Nullable
    private static Denial existingNameDenial(NamingRules rules, TargetFacts facts) {
        if (!rules.isAllowRename() && facts.hasTameworkName()) {
            return Denial.ALREADY_NAMED;
        }
        if (!rules.isReplaceExisting() && facts.hasAnyName()) {
            return Denial.ALREADY_HAS_NAME;
        }
        return null;
    }

    /** Every check a card rename makes before opening the page. */
    @Nullable
    private Denial cardDenial(@Nullable TwNameItemConfig config,
                              NamingRules rules,
                              TargetFacts facts,
                              UUID playerUuid) {
        Denial denial = identityDenial(config, rules, facts, playerUuid);
        return denial != null ? denial : existingNameDenial(rules, facts);
    }

    private void sendDenial(Player player,
                            Denial denial,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            NPCEntity npc,
                            @Nullable UUID ownerUuid) {
        switch (denial) {
            case ROLE_NOT_ALLOWED -> sendMessageKey(player, "tamework.ui.notifications.name.targetNotAllowed");
            case NOT_TAMED -> sendMessageKey(player, "tamework.ui.notifications.name.requireTamed",
                    npcInfoService.resolveDisplayName(npc));
            case NO_OWNER -> sendMessageKey(player, "tamework.ui.notifications.name.noOwner");
            case NOT_OWNER -> OwnerMessageUtil.sendDenied(
                    player,
                    npcInfoService.resolveDisplayName(npc),
                    npcInfoService.resolveOwnerName(npcRef, store),
                    ownerUuid,
                    "name"
            );
            case ALREADY_NAMED -> sendMessageKey(player, "tamework.ui.notifications.name.validation.alreadyNamed");
            case ALREADY_HAS_NAME -> sendMessageKey(player, "tamework.ui.notifications.name.validation.alreadyHasName");
        }
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
        return resolveRandomNamePool(config, null);
    }

    @Nonnull
    private String[] resolveRandomNamePool(@Nullable TwNameItemConfig config, @Nullable String gender) {
        if (config == null || config.getNaming() == null) {
            return EMPTY_RANDOM_NAME_POOL;
        }
        String randomNamesId = config.getNaming().getRandomNamesId();
        if (randomNamesId == null || randomNamesId.isBlank()) {
            return EMPTY_RANDOM_NAME_POOL;
        }
        String[] resolved = TwNamesConfig.resolveMergedPoolById(randomNamesId, gender);
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
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack;
    }

    /** Consumes from and starts the cooldown on the stack the target resolved, if it uses one. */
    private static void applyItemChanges(NamingTarget target, boolean consumeItem) {
        if (target.itemContainer() == null || target.itemSlot() < 0) {
            return;
        }
        NamingItemSlots.use(target.itemContainer(), target.itemSlot(), consumeItem, target.rules().getCooldownMs());
    }

    private void sendMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        if (player.getPlayerRef() != null) {
            player.getPlayerRef().sendMessage(Message.raw(message));
        }
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

    /** A checked target, plus the stack a successful naming spends ({@code itemContainer} null for none). */
    private record NamingTarget(Store<EntityStore> store,
                                NPCEntity npc,
                                NamingRules rules,
                                @Nullable UUID ownerUuid,
                                @Nullable ItemContainer itemContainer,
                                short itemSlot) {
    }

    /** The rules a submission names with and the stack it spends ({@code container} null for none). */
    private record NamingItem(@Nullable TwNameItemConfig config,
                              NamingRules rules,
                              @Nullable ItemContainer container,
                              short slot) {
    }

    /** A carried naming item usable from the card; {@code slot} is null until a stack is chosen. */
    private record CardItem(@Nullable NamingItemSlots.Slot slot, TwNameItemConfig config, NamingRules rules) {
    }

    /** {@code npcRef} and {@code store} are kept for the shared-access check; the record does not outlive the call. */
    private record TargetFacts(@Nullable String roleId,
                               boolean tamed,
                               @Nullable UUID ownerUuid,
                               boolean hasTameworkName,
                               boolean hasAnyName,
                               Ref<EntityStore> npcRef,
                               Store<EntityStore> store) {
    }

    /** Why a target cannot be named; each maps to the player message the held item has always sent. */
    private enum Denial {
        ROLE_NOT_ALLOWED,
        NOT_TAMED,
        NO_OWNER,
        NOT_OWNER,
        ALREADY_NAMED,
        ALREADY_HAS_NAME
    }

    private enum InputMode {
        Ui,
        ChatFallback
    }

    /** Where a naming request came from, which decides the item it needs at submit. */
    private enum RequestSource {
        /** A naming item held in hand; it must still be in hand at submit. */
        HELD,
        /** The companion card in Free mode: default rules, no item. */
        CARD_FREE,
        /** The companion card in Item mode: any carried stack of {@code itemId}. */
        CARD_ITEM
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
        private final RequestSource source;

        private PendingNameRequest(UUID playerUuid,
                                   Ref<EntityStore> npcRef,
                                   @Nullable String itemId,
                                   String configIdOverride,
                                   NamingOverrides overrides,
                                   String[] randomNamePool,
                                   long createdMs,
                                   InputMode inputMode,
                                   UUID requestId,
                                   RequestSource source) {
            this.playerUuid = playerUuid;
            this.npcRef = npcRef;
            this.itemId = itemId;
            this.configIdOverride = configIdOverride;
            this.overrides = overrides;
            this.randomNamePool = randomNamePool == null ? EMPTY_RANDOM_NAME_POOL : randomNamePool;
            this.createdMs = createdMs;
            this.inputMode = inputMode;
            this.requestId = requestId;
            this.source = source;
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
                    requestId,
                    source
            );
        }
    }
}
