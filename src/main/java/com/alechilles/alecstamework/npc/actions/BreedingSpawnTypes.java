package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import javax.annotation.Nullable;

/** Immutable offspring role selected immediately before a live spawn. */
record BreedingResolvedSpawnRole(String roleId,
                                 int roleIndex,
                                 String adultRoleId,
                                 @Nullable TwBreedingConfig.Gender gender,
                                 @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
}
