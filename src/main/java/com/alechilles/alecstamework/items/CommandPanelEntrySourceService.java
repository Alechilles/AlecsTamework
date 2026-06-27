package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;

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
        this.loadedSnapshotService = new CommandLoadedNpcStatusSnapshotService(
                this.npcNameResolver,
                this.linkPolicyService,
                new CommandLinkedPanelProgressionPresentationService(),
                new CommandLinkedPanelCooldownSnapshotService()
        );
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
                LinkedNpcEntry loadedEntry = loadedSnapshotService.buildLoadedEntry(
                        player,
                        npcRef,
                        store,
                        new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                                npc.getUuid(),
                                npcNameResolver.resolveNpcDisplayName(npcRef, store, npc),
                                false,
                                true,
                                false,
                                false,
                                null,
                                null,
                                null,
                                roleId,
                                null
                        )
                );
                if (loadedEntry != null) {
                    out.add(loadedEntry);
                    seen.add(npc.getUuid());
                }
            }
        });
        return applyFiltersAndSort(out, stack);
    }

    private boolean resolveLinkingRequireOwner() {
        return resolveLinkingRequireOwner(TwGlobalConfig.resolveActive());
    }

    static boolean resolveLinkingRequireOwner(TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
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

}
