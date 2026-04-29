package com.alechilles.alecstamework.api;

import java.util.Optional;

public interface TameworkConfigReadApi {
    GlobalConfigView getGlobalConfig();

    Optional<InteractionConfigView> getInteractionConfigById(String id);

    Optional<InteractionConfigView> resolveInteractionConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getCompanionConfigById(String id);

    Optional<RoleScopedConfigView> resolveCompanionConfigForRole(String roleId);

    Optional<SpawnerConfigView> getSpawnerConfigById(String id);

    Optional<SpawnerConfigView> resolveSpawnerConfigForItemId(String itemId);

    Optional<NameItemConfigView> getNameItemConfigById(String id);

    Optional<NameItemConfigView> resolveNameItemConfigForItemId(String itemId);

    Optional<CommandItemConfigView> getCommandItemConfigById(String id);

    Optional<CommandItemConfigView> resolveCommandItemConfigForItemId(String itemId);

    Optional<RoleScopedConfigView> getHappinessConfigById(String id);

    Optional<RoleScopedConfigView> resolveHappinessConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getNeedsConfigById(String id);

    Optional<RoleScopedConfigView> resolveNeedsConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getBreedingConfigById(String id);

    Optional<RoleScopedConfigView> resolveBreedingConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getLevelingConfigById(String id);

    Optional<RoleScopedConfigView> resolveLevelingConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getTraitConfigById(String id);

    Optional<RoleScopedConfigView> resolveTraitConfigForRole(String roleId);

    Optional<RoleScopedConfigView> getTalentConfigById(String id);

    Optional<RoleScopedConfigView> resolveTalentConfigForRole(String roleId);
}

