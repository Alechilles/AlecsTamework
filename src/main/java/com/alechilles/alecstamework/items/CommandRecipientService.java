package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Resolves loaded and unloaded command recipients from command context.
 */
final class CommandRecipientService {
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandPanelPreferenceService panelPreferenceService;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;

    CommandRecipientService(CommandLinkPolicyService linkPolicyService,
                            CommandLinkedNpcRecordStore linkedNpcRecordStore,
                            CommandPanelPreferenceService panelPreferenceService) {
        this(linkPolicyService, linkedNpcRecordStore, panelPreferenceService, null);
    }

    CommandRecipientService(CommandLinkPolicyService linkPolicyService,
                            CommandLinkedNpcRecordStore linkedNpcRecordStore,
                            CommandPanelPreferenceService panelPreferenceService,
                            @Nullable CommandNpcProfileActionResolver profileActionResolver) {
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.linkedNpcRecordStore = linkedNpcRecordStore != null ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.profileActionResolver = profileActionResolver;
    }

    List<Candidate> queryRecipients(Context context) {
        ArrayList<Candidate> out = new ArrayList<>();
        TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
        Vector3d playerPos = playerTransform != null ? new Vector3d(playerTransform.getPosition()) : null;
        CommandPanelPreferenceService.PanelMode panelModeOverride =
                panelPreferenceService.readPanelModeOverride(context.workingItem);
        MembershipMode recipientMembershipMode = panelPreferenceService.resolveRecipientMembershipMode(
                context.workingItem,
                context.config
        );
        double effectiveRadius = context.config.getRadius();
        if (panelModeOverride == CommandPanelPreferenceService.PanelMode.NearbyMode) {
            effectiveRadius = panelPreferenceService.resolveNearbyRadius(context.workingItem, context.config);
        }
        double radiusSq = effectiveRadius >= 0 ? effectiveRadius * effectiveRadius : -1;
        int maxTargets = Math.max(1, context.config.getMaxTargets());
        int maxActive = Math.max(0, context.config.getMaxActive());
        UUID playerUuid = context.player.getUuid();
        boolean requireOwner = resolveLinkingRequireOwner();
        List<LinkedNpcRecord> linkedRecords = linkedNpcRecordStore.read(context.workingItem);
        Map<UUID, LinkedNpcRecord> linkedRecordByUuid = mapLinkedRecordsByUuid(linkedRecords);
        Set<UUID> cappedActiveLinkedNpcUuids = resolveCappedActiveLinkedNpcUuids(linkedRecords, maxActive);

        context.store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                if (!linkPolicyService.matchesMembership(
                        recipientMembershipMode,
                        requireOwner,
                        npcRef,
                        npc,
                        context.playerRef,
                        playerUuid,
                        context.toolId,
                        context.store
                )) {
                    continue;
                }
                if (!linkPolicyService.passesOwnerAndTamed(
                        requireOwner,
                        context.config.isRequireTamed(),
                        npcRef,
                        playerUuid,
                        context.store
                )) {
                    continue;
                }
                if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), context.config)) {
                    continue;
                }
                UUID npcUuid = npc.getUuid();
                if (isInactiveLinkedRecord(linkedRecordByUuid, npcUuid)) {
                    continue;
                }
                if (isCappedOutLinkedRecord(linkedRecordByUuid, cappedActiveLinkedNpcUuids, maxActive, npcUuid)) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                double distSq = 0;
                if (playerPos != null && npcTransform != null) {
                    Vector3d p = npcTransform.getPosition();
                    double dx = p.x - playerPos.x;
                    double dy = p.y - playerPos.y;
                    double dz = p.z - playerPos.z;
                    distSq = dx * dx + dy * dy + dz * dz;
                    if (radiusSq >= 0 && distSq > radiusSq) {
                        continue;
                    }
                } else if (radiusSq >= 0) {
                    continue;
                }
                LinkedNpcRecord linkedRecord = linkedRecordByUuid.get(npcUuid);
                out.add(new Candidate(
                        npcRef, npc, distSq, linkedRecord != null ? linkedRecord.profileId : null));
            }
        });
        out.sort(Comparator.comparingDouble(value -> value.distSq));
        if (out.size() > maxTargets) {
            return new ArrayList<>(out.subList(0, maxTargets));
        }
        return out;
    }

    List<LinkedNpcRecord> queryUnloadedLinkedRecords(Context context, List<Candidate> loadedRecipients) {
        CommandPanelPreferenceService.PanelMode panelModeOverride =
                panelPreferenceService.readPanelModeOverride(context.workingItem);
        MembershipMode mode = panelPreferenceService.resolveRecipientMembershipMode(context.workingItem, context.config);
        if (panelModeOverride == null
                && mode != MembershipMode.LinkedOnly
                && mode != MembershipMode.LinkedOrMasterTarget) {
            return List.of();
        }
        List<LinkedNpcRecord> linkedRecords = linkedNpcRecordStore.read(context.workingItem);
        if (linkedRecords.isEmpty()) {
            return List.of();
        }
        linkedRecords = canonicalizeActiveStack(context, linkedRecords);
        if (linkedRecords == null) {
            return List.of();
        }
        Set<UUID> loadedUuids = new HashSet<>();
        if (loadedRecipients != null) {
            for (Candidate recipient : loadedRecipients) {
                if (recipient == null || recipient.npc == null || recipient.npc.getUuid() == null) {
                    continue;
                }
                loadedUuids.add(recipient.npc.getUuid());
            }
        }
        int remaining = Math.max(0, Math.max(1, context.config.getMaxTargets()) - loadedUuids.size());
        if (remaining <= 0) {
            return List.of();
        }
        int maxActive = Math.max(0, context.config.getMaxActive());
        int remainingActiveSlots = Integer.MAX_VALUE;
        if (maxActive > 0) {
            Map<UUID, LinkedNpcRecord> linkedRecordByUuid = mapLinkedRecordsByUuid(linkedRecords);
            int loadedActiveCount = countLoadedActiveLinkedRecipients(loadedRecipients, linkedRecordByUuid);
            remainingActiveSlots = maxActive - loadedActiveCount;
            if (remainingActiveSlots <= 0) {
                return List.of();
            }
        }
        ArrayList<LinkedNpcRecord> unloaded = new ArrayList<>();
        World world = context.player != null ? context.player.getWorld() : null;
        if (world == null) {
            return List.of();
        }
        for (LinkedNpcRecord cachedRecord : linkedRecords) {
            LinkedNpcRecord record = resolveRelocationRecord(cachedRecord);
            if (record == null || record.npcUuid == null || loadedUuids.contains(record.npcUuid)) {
                continue;
            }
            if (!record.active) {
                continue;
            }
            Ref<EntityStore> ref = world.getEntityRef(record.npcUuid);
            if (ref != null && ref.isValid()) {
                NPCEntity npc = context.store.getComponent(ref, NPCEntity.getComponentType());
                if (npc != null) {
                    continue;
                }
            }
            unloaded.add(record);
            if (maxActive > 0) {
                remainingActiveSlots--;
                if (remainingActiveSlots <= 0) {
                    break;
                }
            }
            if (unloaded.size() >= remaining) {
                break;
            }
        }
        return unloaded;
    }

    @Nullable
    private List<LinkedNpcRecord> canonicalizeActiveStack(
            Context context,
            List<LinkedNpcRecord> records) {
        if (profileActionResolver == null) {
            return records;
        }
        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                profileActionResolver.canonicalizeRecords(records);
        if (!canonical.safeToPersist()) {
            return null;
        }
        if (canonical.identityChanged()) {
            context.workingItem = linkedNpcRecordStore.write(context.workingItem, canonical.records());
            context.itemChanged = true;
        }
        return canonical.records();
    }

    @Nullable
    private LinkedNpcRecord resolveRelocationRecord(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null || profileActionResolver == null) {
            return record;
        }
        CommandNpcProfileActionResolver.ActionTarget target =
                profileActionResolver.resolveRelocation(record);
        return target.isActionable() ? target.resolvedRecord() : null;
    }

    private Map<UUID, LinkedNpcRecord> mapLinkedRecordsByUuid(List<LinkedNpcRecord> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        HashMap<UUID, LinkedNpcRecord> byUuid = new HashMap<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            byUuid.put(record.npcUuid, record);
        }
        return byUuid;
    }

    private boolean isInactiveLinkedRecord(Map<UUID, LinkedNpcRecord> linkedRecordByUuid, UUID npcUuid) {
        if (linkedRecordByUuid == null || linkedRecordByUuid.isEmpty() || npcUuid == null) {
            return false;
        }
        LinkedNpcRecord record = linkedRecordByUuid.get(npcUuid);
        return record != null && !record.active;
    }

    private boolean isCappedOutLinkedRecord(Map<UUID, LinkedNpcRecord> linkedRecordByUuid,
                                            Set<UUID> cappedActiveLinkedNpcUuids,
                                            int maxActive,
                                            UUID npcUuid) {
        if (maxActive <= 0 || linkedRecordByUuid.isEmpty() || npcUuid == null) {
            return false;
        }
        LinkedNpcRecord record = linkedRecordByUuid.get(npcUuid);
        if (record == null || !record.active) {
            return false;
        }
        return !cappedActiveLinkedNpcUuids.contains(npcUuid);
    }

    private Set<UUID> resolveCappedActiveLinkedNpcUuids(List<LinkedNpcRecord> records, int maxActive) {
        if (maxActive <= 0 || records.isEmpty()) {
            return Set.of();
        }
        HashSet<UUID> allowed = new HashSet<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null || !record.active) {
                continue;
            }
            allowed.add(record.npcUuid);
            if (allowed.size() >= maxActive) {
                break;
            }
        }
        return allowed;
    }

    private int countLoadedActiveLinkedRecipients(List<Candidate> loadedRecipients,
                                                  Map<UUID, LinkedNpcRecord> linkedRecordByUuid) {
        if (loadedRecipients == null || loadedRecipients.isEmpty() || linkedRecordByUuid.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Candidate recipient : loadedRecipients) {
            if (recipient == null || recipient.npc == null || recipient.npc.getUuid() == null) {
                continue;
            }
            LinkedNpcRecord record = linkedRecordByUuid.get(recipient.npc.getUuid());
            if (record != null && record.active) {
                count++;
            }
        }
        return count;
    }

    private boolean resolveLinkingRequireOwner() {
        return resolveLinkingRequireOwner(TwGlobalConfig.resolveActive());
    }

    static boolean resolveLinkingRequireOwner(TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }
}
