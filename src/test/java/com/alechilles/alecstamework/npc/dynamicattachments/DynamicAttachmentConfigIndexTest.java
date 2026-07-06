package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentConfigIndexTest {
    @Test
    void testIndexLooksUpRolesCaseInsensitively() throws Exception {
        TwDynamicAttachmentsConfig.RoleRuleEntry entry = entry(
                config("mod:cat", 0),
                rule("hat", 0),
                0
        );
        DynamicAttachmentConfigIndex index = DynamicAttachmentConfigIndex.forTest(Map.of(
                "Cat_Pet",
                List.of(entry)
        ));

        assertTrue(index.hasRulesForRole("cat_pet"));
        assertTrue(index.hasRulesForRole(" CAT_PET "));
        assertEquals(1, index.rulesForRole("cat_pet").size());
        assertSame(entry, index.rulesForRole("CAT_PET").get(0));
    }

    @Test
    void testEmptyIndexHasNoRulesAndReturnsImmutableEmptyLists() {
        DynamicAttachmentConfigIndex index = DynamicAttachmentConfigIndex.emptyForTest();

        assertFalse(index.hasRulesForRole("cat_pet"));
        assertFalse(index.hasRulesForRole(null));
        assertTrue(index.rulesForRole("cat_pet").isEmpty());
        assertTrue(index.rulesForRole("   ").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> index.rulesForRole("cat_pet").add(null));
    }

    @Test
    void testForTestDefensivelyCopiesLists() throws Exception {
        TwDynamicAttachmentsConfig.RoleRuleEntry first = entry(config("mod:first", 0), rule("first", 0), 0);
        TwDynamicAttachmentsConfig.RoleRuleEntry second = entry(config("mod:second", 0), rule("second", 0), 1);
        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = new ArrayList<>();
        entries.add(first);

        DynamicAttachmentConfigIndex index = DynamicAttachmentConfigIndex.forTest(Map.of("cat_pet", entries));
        entries.add(second);

        assertEquals(1, index.rulesForRole("cat_pet").size());
        assertSame(first, index.rulesForRole("cat_pet").get(0));
        assertThrows(UnsupportedOperationException.class, () -> index.rulesForRole("cat_pet").add(second));
    }

    private static TwDynamicAttachmentsConfig config(String id, int priority) throws Exception {
        Constructor<TwDynamicAttachmentsConfig> constructor = TwDynamicAttachmentsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwDynamicAttachmentsConfig config = constructor.newInstance();
        setField(config, "id", id);
        setField(config, "priority", priority);
        return config;
    }

    private static TwDynamicAttachmentsConfig.Rule rule(String id, int priority) throws Exception {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();
        setField(rule, "id", id);
        setField(rule, "priority", priority);
        return rule;
    }

    private static TwDynamicAttachmentsConfig.RoleRuleEntry entry(TwDynamicAttachmentsConfig config,
                                                                  TwDynamicAttachmentsConfig.Rule rule,
                                                                  int declarationOrder) throws Exception {
        Constructor<TwDynamicAttachmentsConfig.RoleRuleEntry> constructor =
                TwDynamicAttachmentsConfig.RoleRuleEntry.class.getDeclaredConstructor(
                        TwDynamicAttachmentsConfig.class,
                        TwDynamicAttachmentsConfig.Rule.class,
                        int.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(config, rule, declarationOrder);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
