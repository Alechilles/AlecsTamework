package com.alechilles.alecstamework.persistence.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.refusal;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.requireOptionalUuid;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.requireProfile;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.requireUuid;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.sha256;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.validJson;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.validJsonObject;

/** Plans profile, alias, tool-link, and extension rows independently of lifecycle resolution. */
final class PublicImportIdentityPlanner {
    @Nonnull
    PublicImportPlanningModel.Identity plan(@Nonnull LegacyPublicData source) throws Exception {
        LinkedHashMap<String, PublicImportPlanningModel.ProfileDraft> profiles = profiles(source);
        return new PublicImportPlanningModel.Identity(
                profiles,
                aliases(source, profiles),
                toolLinks(source, profiles),
                extensions(source, profiles)
        );
    }

    private LinkedHashMap<String, PublicImportPlanningModel.ProfileDraft> profiles(
            LegacyPublicData source
    ) throws Exception {
        LinkedHashMap<String, PublicImportPlanningModel.ProfileDraft> result =
                new LinkedHashMap<>();
        for (LegacyPublicData.Profile profile : source.profiles()) {
            requireUuid(profile.profileId(), "INVALID_PROFILE_ID");
            requireOptionalUuid(profile.currentNpcUuid(), "INVALID_CURRENT_NPC_UUID");
            requireOptionalUuid(profile.ownerUuid(), "INVALID_OWNER_UUID");
            if (result.containsKey(profile.profileId())) {
                throw refusal("DUPLICATE_PROFILE_ID", profile.profileId());
            }
            PublicImportPlanningModel.ProfileDraft draft =
                    new PublicImportPlanningModel.ProfileDraft(profile);
            mapMetadata(draft);
            result.put(profile.profileId(), draft);
        }
        return result;
    }

    private void mapMetadata(PublicImportPlanningModel.ProfileDraft profile) throws Exception {
        String stateJson = profile.source().stateJson();
        if (stateJson == null) {
            profile.metadata(null, null);
        } else if (validJsonObject(stateJson)) {
            profile.metadata(stateJson, sha256(stateJson));
        } else {
            profile.conflict("INVALID_PROFILE_METADATA_JSON");
            profile.rawEvidence("rawStateJson", stateJson);
            profile.rawEvidence("legacyStateHash", profile.source().stateHash());
            profile.metadata(null, null);
        }
    }

    private List<PublicImportPlan.Alias> aliases(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) throws Exception {
        HashMap<String, List<LegacyPublicData.Alias>> grouped = groupAliases(source, profiles);
        ArrayList<PublicImportPlan.Alias> result = new ArrayList<>();
        for (PublicImportPlanningModel.ProfileDraft profile : profiles.values()) {
            List<LegacyPublicData.Alias> ordered = grouped.getOrDefault(
                    profile.source().profileId(), List.of()
            ).stream().sorted(Comparator.comparingLong(LegacyPublicData.Alias::mappedAtMs)
                    .thenComparing(LegacyPublicData.Alias::npcUuid)).toList();
            boolean consistent = aliasIdentityConsistent(profile, ordered);
            addAliases(result, ordered, consistent);
        }
        return List.copyOf(result);
    }

    private HashMap<String, List<LegacyPublicData.Alias>> groupAliases(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) throws Exception {
        HashMap<String, List<LegacyPublicData.Alias>> grouped = new HashMap<>();
        for (LegacyPublicData.Alias alias : source.aliases()) {
            requireUuid(alias.npcUuid(), "INVALID_ALIAS_UUID");
            PublicImportPlanningModel.ProfileDraft profile =
                    requireProfile(profiles, alias.profileId(), "ALIAS_PROFILE_MISSING");
            if (alias.current() != 0 && alias.current() != 1) {
                profile.conflict("INVALID_ALIAS_CURRENT_FLAG");
            }
            grouped.computeIfAbsent(alias.profileId(), ignored -> new ArrayList<>()).add(alias);
        }
        return grouped;
    }

    private boolean aliasIdentityConsistent(
            PublicImportPlanningModel.ProfileDraft profile,
            List<LegacyPublicData.Alias> aliases
    ) {
        List<LegacyPublicData.Alias> current =
                aliases.stream().filter(alias -> alias.current() == 1).toList();
        String expected = profile.source().currentNpcUuid();
        boolean consistent = expected == null
                ? current.isEmpty()
                : current.size() == 1 && expected.equals(current.getFirst().npcUuid());
        if (!consistent) {
            profile.conflict("CURRENT_ALIAS_CONFLICT");
            profile.rawEvidence("legacyCurrentNpcUuid", expected);
        }
        return consistent;
    }

    private void addAliases(
            List<PublicImportPlan.Alias> target,
            List<LegacyPublicData.Alias> aliases,
            boolean consistent
    ) {
        for (int generation = 0; generation < aliases.size(); generation++) {
            LegacyPublicData.Alias alias = aliases.get(generation);
            boolean current = consistent && alias.current() == 1;
            target.add(new PublicImportPlan.Alias(
                    alias.npcUuid(),
                    alias.profileId(),
                    generation,
                    current ? "CURRENT" : "RETIRED",
                    alias.mappedAtMs(),
                    current ? null : alias.mappedAtMs()
            ));
        }
    }

    private List<PublicImportPlan.ToolLink> toolLinks(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) throws Exception {
        ArrayList<PublicImportPlan.ToolLink> result = new ArrayList<>();
        for (LegacyPublicData.ToolLink link : source.toolLinks()) {
            requireProfile(profiles, link.profileId(), "TOOL_LINK_PROFILE_MISSING");
            requireUuid(link.toolUuid(), "INVALID_TOOL_UUID");
            if (link.linkType() == null || link.linkType().isBlank()) {
                throw refusal("INVALID_TOOL_LINK_TYPE", link.profileId());
            }
            result.add(new PublicImportPlan.ToolLink(
                    link.profileId(), link.toolUuid(), link.linkType(),
                    link.createdAtMs(), link.updatedAtMs()
            ));
        }
        return List.copyOf(result);
    }

    private List<PublicImportPlan.ExtensionData> extensions(
            LegacyPublicData source,
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles
    ) throws Exception {
        ArrayList<PublicImportPlan.ExtensionData> result = new ArrayList<>();
        for (LegacyPublicData.ExtensionData extension : source.extensionData()) {
            PublicImportPlanningModel.ProfileDraft profile = requireProfile(
                    profiles, extension.profileId(), "EXTENSION_PROFILE_MISSING"
            );
            if (!validJson(extension.jsonPayload())) {
                profile.conflict("INVALID_EXTENSION_JSON");
                profile.rawEvidence(
                        "extension-" + extension.namespace() + "-" + extension.dataKey(),
                        extension.jsonPayload()
                );
                continue;
            }
            result.add(new PublicImportPlan.ExtensionData(
                    extension.profileId(), extension.namespace(), extension.dataKey(),
                    extension.jsonPayload(), extension.createdAtMs(), extension.updatedAtMs()
            ));
        }
        return List.copyOf(result);
    }
}
