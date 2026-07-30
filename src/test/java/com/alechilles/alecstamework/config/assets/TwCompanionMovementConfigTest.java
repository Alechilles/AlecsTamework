package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCompanionMovementConfigTest {
    @Test
    void resolvesHighestPriorityEnabledConfigForNormalizedRole() throws Exception {
        TwCompanionMovementConfig disabled = config(
                "Mod:Disabled", false, 100, new String[] { "  Tamed_Moose_Bull  " }, 1.75, null, null, null
        );
        TwCompanionMovementConfig lowerPriority = config(
                "Mod:Lower", true, 5, new String[] { "tamed_moose_bull" }, 1.05, null, null,
                new TwCompanionMovementConfig.AttachmentModifier[] {
                        modifier("Saddle", new String[] { "Leather" }, 1.4)
                }
        );
        TwCompanionMovementConfig firstById = config(
                "mod:alpha", true, 10, new String[] { "TAMED_MOOSE_BULL" }, 1.15, null, null, null
        );
        TwCompanionMovementConfig laterById = config(
                "Mod:Zeta", true, 10, new String[] { "tamed_moose_bull" }, 1.45, null, null, null
        );

        TwCompanionMovementConfig.ResolvedMovement resolved =
                TwCompanionMovementConfig.resolveForRoleForTest(
                        List.of(disabled, lowerPriority, laterById, firstById), "  tamed_moose_bull "
                );

        assertEquals(1.15, resolved.baseMoveSpeedMultiplier());
        assertTrue(resolved.attachmentModifiers().isEmpty());
    }

    @Test
    void childInheritsOmittedScalarsFromParent() throws Exception {
        TwCompanionMovementConfig parent = config(
                "Parent", true, 3, new String[] { "Cat_Pet" }, 1.15, 0.65, 2.25, null
        );
        TwCompanionMovementConfig child = config(
                "Child", true, 12, new String[0], null, null, null, null
        );

        child.inheritMissingTopLevelFrom(parent, Set.of("Priority"));

        assertEquals(12, child.getPriority());
        assertEquals(1.15, child.getBaseMoveSpeedMultiplier());
        assertEquals(0.65, child.getMinMoveSpeedMultiplier());
        assertEquals(2.25, child.getMaxMoveSpeedMultiplier());
        assertSame(parent.getRoleIds(), child.getRoleIds());
    }

    @Test
    void explicitAttachmentModifiersReplaceParentArray() throws Exception {
        TwCompanionMovementConfig.AttachmentModifier parentModifier = modifier("Saddle", new String[] { "Leather" }, 1.1);
        TwCompanionMovementConfig.AttachmentModifier childModifier = modifier("Harness", new String[] { "Gold" }, 1.2);
        TwCompanionMovementConfig parent = config(
                "Parent", true, 0, new String[] { "Cat_Pet" }, null, null, null,
                new TwCompanionMovementConfig.AttachmentModifier[] { parentModifier }
        );
        TwCompanionMovementConfig child = config(
                "Child", true, 0, new String[0], null, null, null,
                new TwCompanionMovementConfig.AttachmentModifier[] { childModifier }
        );

        child.inheritMissingTopLevelFrom(parent, Set.of("AttachmentModifiers"));

        assertEquals(1, child.getAttachmentModifiers().length);
        assertSame(childModifier, child.getAttachmentModifiers()[0]);
    }

    @Test
    void noRoleMatchResolvesNeutralDefaults() {
        TwCompanionMovementConfig.ResolvedMovement unresolved =
                TwCompanionMovementConfig.resolveForRoleForTest(List.of(), "No_Match");

        assertEquals(1.0, unresolved.baseMoveSpeedMultiplier());
        assertEquals(0.50, unresolved.minMoveSpeedMultiplier());
        assertEquals(2.0, unresolved.maxMoveSpeedMultiplier());
        assertTrue(unresolved.attachmentModifiers().isEmpty());
    }

    @Test
    void resolvedMovementIsImmutable() throws Exception {
        TwCompanionMovementConfig.AttachmentModifier modifier = modifier("Saddle", new String[] { "Leather" }, 1.15);
        TwCompanionMovementConfig.ResolvedMovement resolved =
                TwCompanionMovementConfig.resolveForRoleForTest(
                        List.of(config(
                                "Configured", true, 0, new String[] { "Cat_Pet" }, 1.15, null, null,
                                new TwCompanionMovementConfig.AttachmentModifier[] { modifier }
                        )),
                        "Cat_Pet"
                );

        assertEquals(1, resolved.attachmentModifiers().size());
        assertFalse(resolved.attachmentModifiers().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> resolved.attachmentModifiers().add(modifier("Harness", new String[] { "Gold" }, 1.2))
        );
    }

    private static TwCompanionMovementConfig config(String id,
                                                     boolean enabled,
                                                     int priority,
                                                     String[] roleIds,
                                                     Double baseMoveSpeedMultiplier,
                                                     Double minMoveSpeedMultiplier,
                                                     Double maxMoveSpeedMultiplier,
                                                     TwCompanionMovementConfig.AttachmentModifier[] attachmentModifiers)
            throws Exception {
        TwCompanionMovementConfig config = new TwCompanionMovementConfig();
        setField(config, "id", id);
        setField(config, "enabled", enabled);
        setField(config, "priority", priority);
        setField(config, "roleIds", roleIds);
        setField(config, "baseMoveSpeedMultiplier", baseMoveSpeedMultiplier);
        setField(config, "minMoveSpeedMultiplier", minMoveSpeedMultiplier);
        setField(config, "maxMoveSpeedMultiplier", maxMoveSpeedMultiplier);
        setField(config, "attachmentModifiers", attachmentModifiers);
        return config;
    }

    private static TwCompanionMovementConfig.AttachmentModifier modifier(String slot,
                                                                           String[] values,
                                                                           double multiplier) {
        return new TwCompanionMovementConfig.AttachmentModifier(slot, List.of(values), multiplier);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
