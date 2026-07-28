package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves NPC-facing names for command item UI and metadata.
 * <p>
 * Resolution order is:
 * 1) explicit display name components (custom/player-facing),
 * 2) translated name key,
 * 3) role id.
 */
final class CommandNpcNameResolver {
    private final TranslationRegistry translationRegistry;

    CommandNpcNameResolver() {
        this(null);
    }

    CommandNpcNameResolver(TranslationRegistry translationRegistry) {
        this.translationRegistry = translationRegistry;
    }

    String resolveNpcDisplayName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (npc == null) {
            return "NPC";
        }
        String customName = resolveNpcNameComponent(npcRef, store);
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        String nameKey = resolveNpcNameKey(npc);
        String roleId = resolveNpcRoleId(npc);
        String componentDisplayName = resolveDisplayNameComponent(npcRef, store);
        if (componentDisplayName != null && !componentDisplayName.isBlank()) {
            String translated = translateSnapshotName(componentDisplayName, nameKey, roleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return componentDisplayName;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            String translated = translateSnapshotName(displayName, nameKey, roleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return displayName;
        }
        if (nameKey != null && !nameKey.isBlank()) {
            String translated = translateNpcNameKey(nameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "NPC";
    }

    String resolveCachedUnloadedDisplayName(LinkedNpcRecord record) {
        if (record == null) {
            return null;
        }
        if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()) {
            String translated = translateSnapshotName(record.cachedDisplayName, record.cachedNameKey, record.cachedRoleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedDisplayName;
        }
        if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
            String translated = translateNpcNameKey(record.cachedNameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedNameKey;
        }
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            String translated = translateNpcNameKey(record.cachedRoleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedRoleId;
        }
        return null;
    }

    String resolveSnapshotDisplayName(String snapshotDisplayName, String roleId) {
        return resolveSnapshotDisplayName(snapshotDisplayName, null, roleId);
    }

    String resolveSnapshotDisplayName(String snapshotDisplayName, String nameKey, String roleId) {
        String translated = translateSnapshotName(snapshotDisplayName, nameKey, roleId);
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        if (snapshotDisplayName != null && !snapshotDisplayName.isBlank()) {
            return snapshotDisplayName;
        }
        if (roleId != null && !roleId.isBlank()) {
            return resolveRoleDisplayName(roleId, nameKey);
        }
        return null;
    }

    String resolveRoleDisplayName(String roleId, String nameKey) {
        String translated = translateNpcNameKey(roleId);
        if (translated == null || translated.isBlank()) {
            translated = translateNpcNameKey(nameKey);
        }
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        String fallbackRole = firstNonBlank(
                roleId,
                extractRoleIdFromNameKey(nameKey)
        );
        if (fallbackRole == null) {
            return null;
        }
        if (fallbackRole.regionMatches(
                true, 0, "Tamed_", 0, "Tamed_".length()
        )) {
            fallbackRole = fallbackRole.substring("Tamed_".length());
        }
        return humanizeRoleId(fallbackRole);
    }

    String resolveNpcDisplayNameFromComponents(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        String customName = resolveNpcNameComponent(npcRef, store);
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        return resolveDisplayNameComponent(npcRef, store);
    }

    private String resolveDisplayNameComponent(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
    }

    private String resolveNpcNameComponent(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            TameworkNpcNameComponent nameComponent = store.getComponent(npcRef, nameType);
            if (nameComponent != null && nameComponent.getName() != null && !nameComponent.getName().isBlank()) {
                return nameComponent.getName();
            }
        }
        return NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
    }

    String resolveNpcNameKey(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleParamNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
        if (roleParamNameKey != null && !roleParamNameKey.isBlank()) {
            return roleParamNameKey;
        }
        String roleId = resolveNpcRoleId(npc);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        if (looksLikeTranslationKey(roleId)) {
            return roleId;
        }
        return RoleNameResolver.defaultRoleNameKey(roleId);
    }

    String resolveNpcRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String registeredRoleId = CommandLinkPolicyService.selectRoleId(
                null,
                npc.getRoleIndex(),
                CommandNpcNameResolver::lookupRegisteredRoleId
        );
        if (registeredRoleId != null && !registeredRoleId.isBlank()) {
            return registeredRoleId;
        }
        String roleId = readStringGetter(npc, "getRoleId");
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        Role role = npc.getRole();
        roleId = readStringGetter(role, "getId", "getRoleId", "id");
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }

    private static String lookupRegisteredRoleId(int roleIndex) {
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null ? plugin.getName(roleIndex) : null;
    }

    private boolean looksLikeTranslationKey(String value) {
        return RoleNameResolver.looksLikeTranslationKey(value);
    }

    private String translateNpcNameKey(String nameKey) {
        if (nameKey == null || nameKey.isBlank()) {
            return null;
        }
        TranslationRegistry registry = translationRegistry;
        if (registry == null) {
            Tamework instance = Tamework.getInstance();
            registry = instance != null ? instance.getTranslationRegistry() : null;
        }
        if (registry == null) {
            return null;
        }
        return RoleNameResolver.translateNameKey(registry::get, nameKey);
    }

    private void addCandidate(List<String> candidates, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!candidates.contains(key)) {
            candidates.add(key);
        }
    }

    private String translateSnapshotName(String snapshotDisplayName, String nameKey, String roleId) {
        if (snapshotDisplayName != null && !snapshotDisplayName.isBlank()) {
            if (looksLikeTranslationKey(snapshotDisplayName)) {
                String translated = translateNpcNameKey(snapshotDisplayName);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            String roleIdFromNameKey = extractRoleIdFromNameKey(nameKey);
            String effectiveRoleId = firstNonBlank(roleIdFromNameKey, roleId);
            if (roleId != null
                    && !roleId.isBlank()
                    && snapshotDisplayName.equalsIgnoreCase(roleId)) {
                String translated = translateNpcNameKey(firstNonBlank(nameKey, roleId));
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
                String fallback = RoleNameResolver.resolveDisplayName(roleId, nameKey, null);
                if (fallback != null && !fallback.isBlank() && !fallback.equalsIgnoreCase(roleId)) {
                    return fallback;
                }
            }
            if (nameKey != null && !nameKey.isBlank() && snapshotDisplayName.equalsIgnoreCase(nameKey)) {
                String translated = translateNpcNameKey(nameKey);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            String translatedRole = translateNpcNameKey(firstNonBlank(nameKey, effectiveRoleId));
            if (translatedRole != null
                    && !translatedRole.isBlank()
                    && isGenericRoleDisplayName(snapshotDisplayName, effectiveRoleId, translatedRole)) {
                return translatedRole;
            }
            return null;
        }
        if (nameKey != null && !nameKey.isBlank()) {
            String translated = translateNpcNameKey(nameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    private String extractRoleIdFromNameKey(String nameKey) {
        return RoleNameResolver.extractRoleIdFromNameKey(nameKey);
    }

    private boolean isGenericRoleDisplayName(String displayName, String roleId, String translatedRoleName) {
        if (displayName == null || displayName.isBlank() || roleId == null || roleId.isBlank()) {
            return false;
        }
        String normalizedDisplay = displayName.trim();
        if (translatedRoleName != null && normalizedDisplay.equalsIgnoreCase(translatedRoleName.trim())) {
            return false;
        }
        for (String genericRoleId : buildGenericRoleCandidates(roleId)) {
            String translated = translateNpcNameKey(genericRoleId);
            if (translated != null && normalizedDisplay.equalsIgnoreCase(translated.trim())) {
                return true;
            }
            String humanized = humanizeRoleId(genericRoleId);
            if (humanized != null && normalizedDisplay.equalsIgnoreCase(humanized)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildGenericRoleCandidates(String roleId) {
        ArrayList<String> candidates = new ArrayList<>(4);
        if (roleId == null || roleId.isBlank()) {
            return candidates;
        }
        String trimmed = roleId.trim();
        int separator = trimmed.indexOf('_');
        if (separator <= 0) {
            return candidates;
        }
        String base = trimmed.substring(0, separator);
        addCandidate(candidates, base);
        if (trimmed.endsWith("_Pet")) {
            addCandidate(candidates, base + "_Pet");
        }
        if (trimmed.startsWith("Tamed_")) {
            String withoutTamed = trimmed.substring("Tamed_".length());
            int tamedSeparator = withoutTamed.indexOf('_');
            if (tamedSeparator > 0) {
                addCandidate(candidates, "Tamed_" + withoutTamed.substring(0, tamedSeparator));
            }
        }
        return candidates;
    }

    private String humanizeRoleId(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : roleId.trim().split("_")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String readStringGetter(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            String value = invokeStringGetter(target, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

}
