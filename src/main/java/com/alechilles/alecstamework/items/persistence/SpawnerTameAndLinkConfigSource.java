package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.companion.capture
        .CaptureCommandAccessEvidence;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeaturePolicySource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Copies the current spawner, command-access, role, and population policies
 * used by tame/link evidence.
 */
final class SpawnerTameAndLinkConfigSource
        implements TameworkSpawnerTameAndLinkEvidenceSource.ConfigSource {
    private final PopulationGroupConfigRegistry groups;
    private final ItemFeatureRegistry items;
    private final CommandItemRegistry commands;
    private final ReplacementFeaturePolicySource policies;

    SpawnerTameAndLinkConfigSource(
            PopulationGroupConfigRegistry groups,
            ItemFeatureRegistry items,
            CommandItemRegistry commands,
            ReplacementFeaturePolicySource policies
    ) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.items = Objects.requireNonNull(items, "items");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    @Override
    @Nullable
    public TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot freeze(
            SpawnerTameAndLinkIntentFactory.Input input
    ) {
        if (input == null) {
            return null;
        }
        ItemSnapshot item = item(input);
        if (item == null) {
            return null;
        }
        String targetRole = targetRole(input.roleId(), item.feature());
        CommandSnapshot command = command(item.mechanics(), targetRole);
        ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy =
                policies.resolve(targetRole);
        if (command == null || !validRolePolicy(
                targetRole, rolePolicy
        )) {
            return null;
        }
        PopulationGroupConfigIndex groupPolicy = groups.snapshot();
        return new TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot(
                targetRole,
                rolePolicy.globalOwnerLimit(),
                rolePolicy.perWorldOwnerLimit(),
                groupPolicy.revision(),
                groupPolicy.resolvePoliciesForRole(targetRole),
                new CommandFamilyKey(
                        new OwnerId(input.actorUuid()),
                        command.familyId()
                ),
                new CaptureCommandAccessEvidence(
                        command.configId(),
                        command.revision(),
                        command.familyId(),
                        command.accessItemIds()
                ),
                rolePolicy.timedSummonPolicy()
        );
    }

    @Nullable
    private ItemSnapshot item(
            SpawnerTameAndLinkIntentFactory.Input input
    ) {
        String itemId = input.sourceStack().getItemId();
        long before = items.revision();
        SpawnerCaptureMechanicsView view = items
                .resolveCaptureForItemId(itemId)
                .orElse(null);
        ItemFeatureConfig feature = items.get(itemId);
        long after = items.revision();
        if (view == null || feature == null || before != after
                || view.configRevision() != before
                || !view.configId().equals(
                input.resolution().formula().itemConfigId()
        )
                || view.configRevision() != input.resolution()
                .formula().itemConfigRevision()
                || view.successDisposition()
                != CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                || feature.getCaptureMechanics().successDisposition()
                != CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                || !feature.isCaptureTamesTarget()) {
            return null;
        }
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                feature.getCaptureMechanics();
        if (!Objects.equals(
                view.commandFamilyId(), mechanics.commandFamilyId()
        )
                || !Objects.equals(
                view.requiredCommandConfigId(),
                mechanics.requiredCommandConfigId()
        )
                || !view.requireCommandAccessItem()
                || !mechanics.requireCommandAccessItem()) {
            return null;
        }
        return new ItemSnapshot(feature, mechanics);
    }

    private String targetRole(
            String sourceRole,
            ItemFeatureConfig feature
    ) {
        String mapped = feature.resolveCaptureTamedRole(sourceRole);
        return mapped == null ? sourceRole.trim() : mapped.trim();
    }

    @Nullable
    private CommandSnapshot command(
            ItemFeatureConfig.CaptureItemMechanics mechanics,
            String targetRole
    ) {
        String configId = mechanics.requiredCommandConfigId();
        String familyId = mechanics.commandFamilyId();
        long before = commands.revision();
        TwCommandItemConfig config = commands.getByConfigId(configId);
        Map<String, TwCommandItemConfig> byItem = commands.snapshot();
        long after = commands.revision();
        if (before != after || !validCommand(
                config, configId, familyId, targetRole
        )) {
            return null;
        }
        List<String> accessItems = byItem.entrySet().stream()
                .filter(entry -> entry.getValue() == config)
                .map(Map.Entry::getKey)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        if (accessItems.isEmpty()
                || !accessItems.containsAll(configuredItems(config))) {
            return null;
        }
        return new CommandSnapshot(
                configId, before, familyId, accessItems
        );
    }

    private boolean validCommand(
            @Nullable TwCommandItemConfig config,
            String configId,
            String familyId,
            String roleId
    ) {
        return config != null
                && config.isEnabled()
                && config.isLinkEnabled()
                && config.usesOwnerCommandFamilyRoster()
                && config.isRequireOwner()
                && Objects.equals(config.getId(), configId)
                && Objects.equals(config.getCommandFamilyId(), familyId)
                && roleAllowed(config.getAllowedRoles(), roleId);
    }

    private boolean roleAllowed(
            @Nullable TwCommandItemConfig.AllowedRoles allowed,
            String roleId
    ) {
        if (allowed == null || allowed.getMode()
                == TwCommandItemConfig.RoleFilterMode.AllowAll) {
            return true;
        }
        boolean listed = Arrays.stream(
                        allowed.getMode()
                                == TwCommandItemConfig.RoleFilterMode.Allowlist
                                ? allowed.getAllowlist()
                                : allowed.getDenylist()
                )
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(roleId::equals);
        return allowed.getMode()
                == TwCommandItemConfig.RoleFilterMode.Allowlist
                ? listed
                : !listed;
    }

    private List<String> configuredItems(TwCommandItemConfig config) {
        return Arrays.stream(config.getItemIds())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private boolean validRolePolicy(
            String targetRole,
            @Nullable ReplacementFeaturePolicySource.RolePolicySnapshot policy
    ) {
        return policy != null
                && policy.roleId().equals(targetRole)
                && policy.timedSummoningEnabled()
                && policy.timedSummonPolicy() != null;
    }

    private record ItemSnapshot(
            ItemFeatureConfig feature,
            ItemFeatureConfig.CaptureItemMechanics mechanics
    ) {
    }

    private record CommandSnapshot(
            String configId,
            long revision,
            String familyId,
            List<String> accessItemIds
    ) {
    }
}
