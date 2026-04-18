package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Builds command-panel entries using linked and nearby-owned companions.
 */
final class CommandPanelEntrySourceService {
    private final CommandLinkedPanelEntryService linkedPanelEntryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver) {
        this.linkedPanelEntryService = linkedPanelEntryService;
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
    }

    List<LinkedNpcEntry> buildEntries(Player player,
                                      Store<EntityStore> store,
                                      ItemStack stack,
                                      TwCommandItemConfig config,
                                      String toolId) {
        List<LinkedNpcEntry> linkedEntries = linkedPanelEntryService.buildEntries(player, store, stack, toolId);
        CommandPanelPreferenceService.PanelMode panelMode =
                panelPreferenceService.resolveEffectivePanelMode(stack, config);
        if (panelMode != CommandPanelPreferenceService.PanelMode.NearbyMode) {
            return applyFiltersAndSort(linkedEntries, stack);
        }
        if (player == null || store == null) {
            return applyFiltersAndSort(linkedEntries, stack);
        }
        UUID playerUuid = player.getUuid();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerUuid == null || playerRef == null || !playerRef.isValid()) {
            return applyFiltersAndSort(linkedEntries, stack);
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return applyFiltersAndSort(linkedEntries, stack);
        }
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return applyFiltersAndSort(linkedEntries, stack);
        }
        double radiusSq = radius * radius;

        ArrayList<LinkedNpcEntry> out = new ArrayList<>(linkedEntries.size() + 16);
        Set<UUID> seen = new HashSet<>();
        for (LinkedNpcEntry entry : linkedEntries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            out.add(entry);
            seen.add(entry.npcUuid());
        }

        Vector3d playerPos = new Vector3d(playerTransform.getPosition());
        boolean requireOwner = resolveLinkingRequireOwner();
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || npc.getUuid() == null || seen.contains(npc.getUuid())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                if (!linkPolicyService.passesOwnerAndTamed(
                        requireOwner,
                        config != null && config.isRequireTamed(),
                        npcRef,
                        playerUuid,
                        store
                )) {
                    continue;
                }
                if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), config)) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (npcTransform == null) {
                    continue;
                }
                Vector3d npcPos = npcTransform.getPosition();
                double dx = npcPos.x - playerPos.x;
                double dy = npcPos.y - playerPos.y;
                double dz = npcPos.z - playerPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
                String roleId = normalize(linkPolicyService.resolveRoleId(npc));
                HealthSnapshot healthSnapshot = readHealthSnapshot(npcRef, store);
                TameworkCommandLinksComponent links =
                        store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                boolean hasHome = links != null && links.hasHome();
                LinkedNpcTraitIndicator[] traitIndicators =
                        linkedPanelEntryService.readLoadedTraitIndicators(npcRef, store);
                out.add(new LinkedNpcEntry(
                        npc.getUuid(),
                        npcNameResolver.resolveNpcDisplayName(npcRef, store, npc),
                        healthSnapshot.current,
                        healthSnapshot.max,
                        0,
                        0,
                        0,
                        null,
                        0,
                        0,
                        0,
                        0,
                        true,
                        hasHome,
                        false,
                        false,
                        false,
                        false,
                        0L,
                        null,
                        null,
                        null,
                        traitIndicators,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        roleId,
                        roleId,
                        null,
                        null,
                        null,
                        false,
                        false,
                        0L,
                        0.0,
                        false
                ));
                seen.add(npc.getUuid());
            }
        });
        return applyFiltersAndSort(out, stack);
    }

    private boolean resolveLinkingRequireOwner() {
        return resolveLinkingRequireOwner(TwGlobalConfig.resolveActive());
    }

    static boolean resolveLinkingRequireOwner(TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return resolved.isOwnershipLinkingRequiresOwner();
    }

    private List<LinkedNpcEntry> applyFiltersAndSort(List<LinkedNpcEntry> input, ItemStack stack) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        String nameFilter = normalize(panelPreferenceService.resolveNameFilter(stack));
        String speciesFilter = normalize(panelPreferenceService.resolveSpeciesFilter(stack));
        String groupFilter = normalize(panelPreferenceService.resolveGroupFilter(stack));
        ArrayList<LinkedNpcEntry> filtered = new ArrayList<>(input.size());
        for (LinkedNpcEntry entry : input) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (!matchesContains(entry.displayName(), nameFilter)) {
                continue;
            }
            if (!matchesContains(firstNonBlank(entry.speciesLabel(), entry.speciesId()), speciesFilter)) {
                continue;
            }
            if (!matchesContains(firstNonBlank(entry.groupName(), entry.groupId()), groupFilter)) {
                continue;
            }
            filtered.add(entry);
        }
        CommandPanelPreferenceService.PanelSort sort = panelPreferenceService.resolveSort(stack);
        if (sort == CommandPanelPreferenceService.PanelSort.Default) {
            return partitionByActive(filtered);
        }
        Comparator<LinkedNpcEntry> comparator = buildComparator(sort);
        filtered.sort(comparator);
        return filtered;
    }

    private Comparator<LinkedNpcEntry> buildComparator(CommandPanelPreferenceService.PanelSort sort) {
        Comparator<LinkedNpcEntry> base =
                Comparator.comparing((LinkedNpcEntry value) -> value.active() ? 0 : 1);
        Comparator<LinkedNpcEntry> byName = Comparator
                .comparing((LinkedNpcEntry value) -> safe(value.displayName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.npcUuid().toString());
        if (sort == null) {
            return base.thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Name) {
            return base.thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Species) {
            return base
                    .thenComparing(
                            (LinkedNpcEntry value) -> safe(firstNonBlank(value.speciesLabel(), value.speciesId())),
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(byName);
        }
        return base
                .thenComparing(
                        (LinkedNpcEntry value) -> safe(firstNonBlank(value.groupName(), value.groupId())),
                        String.CASE_INSENSITIVE_ORDER
                )
                .thenComparing(byName);
    }

    private List<LinkedNpcEntry> partitionByActive(List<LinkedNpcEntry> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcEntry> active = new ArrayList<>(input.size());
        ArrayList<LinkedNpcEntry> inactive = new ArrayList<>(input.size());
        for (LinkedNpcEntry entry : input) {
            if (entry == null) {
                continue;
            }
            if (entry.active()) {
                active.add(entry);
            } else {
                inactive.add(entry);
            }
        }
        if (inactive.isEmpty()) {
            return active;
        }
        if (active.isEmpty()) {
            return inactive;
        }
        active.addAll(inactive);
        return active;
    }

    private HealthSnapshot readHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return HealthSnapshot.ZERO;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return HealthSnapshot.ZERO;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return HealthSnapshot.ZERO;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return HealthSnapshot.ZERO;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return HealthSnapshot.ZERO;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesContains(String candidate, String filterNormalized) {
        if (filterNormalized == null || filterNormalized.isBlank()) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(filterNormalized);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record HealthSnapshot(int current, int max) {
        private static final HealthSnapshot ZERO = new HealthSnapshot(0, 0);
    }
}
