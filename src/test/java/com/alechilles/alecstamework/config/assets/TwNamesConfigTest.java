package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TwNamesConfigTest {

    @Test
    void omittedArrayInheritsFromParent() throws Exception {
        TwNamesConfig parent = new TwNamesConfig();
        TwNamesConfig child = new TwNamesConfig();

        setField(parent, "northAmericaMale", new String[] { "Liam", "Noah" });
        setField(child, "northAmericaMale", new String[0]);

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertArrayEquals(new String[] { "Liam", "Noah" }, child.getNorthAmericaMale());
    }

    @Test
    void explicitArrayReplacesParentArray() throws Exception {
        TwNamesConfig parent = new TwNamesConfig();
        TwNamesConfig child = new TwNamesConfig();

        String[] parentGerman = new String[] { "Emil", "Matteo" };
        String[] childGerman = new String[] { "Felix" };
        setField(parent, "germanMale", parentGerman);
        setField(child, "germanMale", childGerman);

        child.inheritMissingTopLevelFrom(parent, Set.of("GermanMale"), new HashMap<>());

        assertArrayEquals(childGerman, child.getGermanMale());
    }

    @Test
    void mergedPoolUsesSectionOrderAndCaseInsensitiveDedupe() throws Exception {
        TwNamesConfig config = new TwNamesConfig();
        setField(config, "northAmericaMale", new String[] { "Noah", "Liam" });
        setField(config, "northAmericaFemale", new String[] { "Emma", "Noah" });
        setField(config, "germanMale", new String[] { "Noah", "Felix" });
        setField(config, "spanishFemale", new String[] { "Lucía", "EMMA" });
        setField(config, "brazilianPortugueseMale", new String[] { "João" });
        setField(config, "brazilianPortugueseFemale", new String[] { "Helena", "joão" });

        assertArrayEquals(
                new String[] { "Noah", "Liam", "Emma", "Felix", "Lucía", "João", "Helena" },
                config.getMergedPool()
        );
    }

    @Test
    void genderPoolUsesMatchingNameSectionsOnly() throws Exception {
        TwNamesConfig config = new TwNamesConfig();
        setField(config, "northAmericaMale", new String[] { "Noah" });
        setField(config, "northAmericaFemale", new String[] { "Emma" });
        setField(config, "germanMale", new String[] { "Felix" });
        setField(config, "germanFemale", new String[] { "Mia" });

        assertArrayEquals(new String[] { "Noah", "Felix" }, config.getMergedPool("Male"));
        assertArrayEquals(new String[] { "Emma", "Mia" }, config.getMergedPool("Female"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
