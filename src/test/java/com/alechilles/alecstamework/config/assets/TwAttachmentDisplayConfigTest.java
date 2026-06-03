package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TwAttachmentDisplayConfigTest {

    @Test
    void singleConfigCanResolveEntriesForManyRoles() throws Exception {
        TwAttachmentDisplayConfig config = config(
                "Display_All",
                0,
                true,
                entry("cattle", appliesTo(new String[] { "Cow" }, null, null, null),
                        set("BaseColor", "Coat", Map.of("Black", "Black Coat"))),
                entry("birds", appliesTo(new String[] { "Chicken" }, null, null, null),
                        set("FeatherColor", "Feathers", Map.of("White", "White Feathers")))
        );
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(config));

        ResolvedAttachmentDisplay cow = resolver.resolve("Cow", null, "BaseColor", "Black");
        ResolvedAttachmentDisplay chicken = resolver.resolve("Chicken", null, "FeatherColor", "White");

        assertEquals("Coat", cow.setLabel());
        assertEquals("Black Coat", cow.valueLabel());
        assertEquals("Feathers", chicken.setLabel());
        assertEquals("White Feathers", chicken.valueLabel());
    }

    @Test
    void exactModelMatchBeatsExactRoleMatch() throws Exception {
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(
                config("Display_Role", 0, true,
                        entry("role", appliesTo(new String[] { "Mob_Cow" }, null, null, null),
                                set("BaseColor", "Role Coat", Map.of("Black", "Role Black")))),
                config("Display_Model", 0, true,
                        entry("model", appliesTo(null, new String[] { "Model_Cow" }, null, null),
                                set("BaseColor", "Model Coat", Map.of("Black", "Model Black"))))
        ));

        ResolvedAttachmentDisplay resolved = resolver.resolve("Mob_Cow", "Model_Cow", "BaseColor", "Black");

        assertEquals("Model Coat", resolved.setLabel());
        assertEquals("Model Black", resolved.valueLabel());
    }

    @Test
    void exactRoleMatchBeatsNamespaceMatch() throws Exception {
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(
                config("Display_Namespace", 0, true,
                        entry("namespace", appliesTo(null, null, new String[] { "Aures" }, null),
                                set("BaseColor", "Namespace Coat", Map.of("Black", "Namespace Black")))),
                config("Display_Role", 0, true,
                        entry("role", appliesTo(new String[] { "Aures:Mob_Cow" }, null, null, null),
                                set("BaseColor", "Role Coat", Map.of("Black", "Role Black"))))
        ));

        ResolvedAttachmentDisplay resolved = resolver.resolve("Aures:Mob_Cow", null, "BaseColor", "Black");

        assertEquals("Role Coat", resolved.setLabel());
        assertEquals("Role Black", resolved.valueLabel());
    }

    @Test
    void namespaceMatchBeatsGlobalFallback() throws Exception {
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(
                config("Display_Global", 0, true,
                        entry("global", new TwAttachmentDisplayConfig.AppliesTo(),
                                set("BaseColor", "Global Coat", Map.of("Black", "Global Black")))),
                config("Display_ModelNamespace", 0, true,
                        entry("namespace", appliesTo(null, null, null, new String[] { "Celly" }),
                                set("BaseColor", "Celly Coat", Map.of("Black", "Celly Black"))))
        ));

        ResolvedAttachmentDisplay resolved = resolver.resolve("Mob_Cow", "Celly:Model_Cow", "BaseColor", "Black");

        assertEquals("Celly Coat", resolved.setLabel());
        assertEquals("Celly Black", resolved.valueLabel());
    }

    @Test
    void priorityAndConfigIdBreakTiesDeterministically() throws Exception {
        TwAttachmentDisplayConfig low = config("Display_Low", 1, true,
                entry("same", appliesTo(new String[] { "Mob_Cow" }, null, null, null),
                        set("BaseColor", "Low Coat", Map.of("Black", "Low Black"))));
        TwAttachmentDisplayConfig high = config("Display_High", 5, true,
                entry("same", appliesTo(new String[] { "Mob_Cow" }, null, null, null),
                        set("BaseColor", "High Coat", Map.of("Black", "High Black"))));
        TwAttachmentDisplayConfig alpha = config("Display_Alpha", 5, true,
                entry("same", appliesTo(new String[] { "Mob_Yak" }, null, null, null),
                        set("BaseColor", "Alpha Coat", Map.of("Black", "Alpha Black"))));
        TwAttachmentDisplayConfig zeta = config("Display_Zeta", 5, true,
                entry("same", appliesTo(new String[] { "Mob_Yak" }, null, null, null),
                        set("BaseColor", "Zeta Coat", Map.of("Black", "Zeta Black"))));
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(low, high, zeta, alpha));

        ResolvedAttachmentDisplay priority = resolver.resolve("Mob_Cow", null, "BaseColor", "Black");
        ResolvedAttachmentDisplay idTie = resolver.resolve("Mob_Yak", null, "BaseColor", "Black");

        assertEquals("High Black", priority.valueLabel());
        assertEquals("Alpha Black", idTie.valueLabel());
    }

    @Test
    void disabledConfigsAreIgnoredAndUnknownMappingsUseRawIds() throws Exception {
        AttachmentDisplayResolver resolver = new AttachmentDisplayResolver(List.of(
                config("Display_Disabled", 100, false,
                        entry("disabled", appliesTo(new String[] { "Mob_Cow" }, null, null, null),
                                set("BaseColor", "Disabled Coat", Map.of("Black", "Disabled Black"))))
        ));

        ResolvedAttachmentDisplay resolved = resolver.resolve("Mob_Cow", null, "BaseColor", "Black");

        assertEquals("BaseColor", resolved.setLabel());
        assertEquals("Black", resolved.valueLabel());
    }

    @Test
    void parentFallbackInheritsOmittedEntriesAndExplicitEntriesReplaceParent() throws Exception {
        TwAttachmentDisplayConfig.Entry parentEntry = entry(
                "parent",
                new TwAttachmentDisplayConfig.AppliesTo(),
                set("BaseColor", "Coat", Map.of("Black", "Black Coat"))
        );
        TwAttachmentDisplayConfig parent = config("Display_Parent", 9, true, parentEntry);
        TwAttachmentDisplayConfig childOmitted = config("Display_ChildOmitted", 0, true);
        TwAttachmentDisplayConfig.Entry childEntry = entry(
                "child",
                new TwAttachmentDisplayConfig.AppliesTo(),
                set("Horns", "Horns", Map.of("Small", "Small Horns"))
        );
        TwAttachmentDisplayConfig childExplicit = config("Display_ChildExplicit", 0, true, childEntry);

        childOmitted.inheritMissingTopLevelFrom(parent, Set.of("Enabled"));
        childExplicit.inheritMissingTopLevelFrom(parent, Set.of("Entries"));

        assertEquals(9, childOmitted.getPriority());
        assertSame(parentEntry, childOmitted.getEntries()[0]);
        assertEquals(1, childExplicit.getEntries().length);
        assertSame(childEntry, childExplicit.getEntries()[0]);
    }

    private static TwAttachmentDisplayConfig config(String id,
                                                    int priority,
                                                    boolean enabled,
                                                    TwAttachmentDisplayConfig.Entry... entries) throws Exception {
        TwAttachmentDisplayConfig config = new TwAttachmentDisplayConfig();
        setField(config, "id", id);
        setField(config, "priority", priority);
        setField(config, "enabled", enabled);
        setField(config, "entries", entries);
        return config;
    }

    private static TwAttachmentDisplayConfig.Entry entry(String id,
                                                        TwAttachmentDisplayConfig.AppliesTo appliesTo,
                                                        Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> sets)
            throws Exception {
        TwAttachmentDisplayConfig.Entry entry = new TwAttachmentDisplayConfig.Entry();
        setField(entry, "id", id);
        setField(entry, "appliesTo", appliesTo);
        setField(entry, "sets", sets);
        return entry;
    }

    private static TwAttachmentDisplayConfig.AppliesTo appliesTo(String[] roleIds,
                                                                 String[] modelIds,
                                                                 String[] roleNamespaces,
                                                                 String[] modelNamespaces) throws Exception {
        TwAttachmentDisplayConfig.AppliesTo appliesTo = new TwAttachmentDisplayConfig.AppliesTo();
        if (roleIds != null) setField(appliesTo, "roleIds", roleIds);
        if (modelIds != null) setField(appliesTo, "modelIds", modelIds);
        if (roleNamespaces != null) setField(appliesTo, "roleNamespaces", roleNamespaces);
        if (modelNamespaces != null) setField(appliesTo, "modelNamespaces", modelNamespaces);
        return appliesTo;
    }

    private static Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> set(String setId,
                                                                                  String label,
                                                                                  Map<String, String> values)
            throws Exception {
        TwAttachmentDisplayConfig.AttachmentSetDisplay set = new TwAttachmentDisplayConfig.AttachmentSetDisplay();
        setField(set, "label", label);
        setField(set, "values", values);
        assertEquals(values, set.getValues());
        return Map.of(setId, set);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
