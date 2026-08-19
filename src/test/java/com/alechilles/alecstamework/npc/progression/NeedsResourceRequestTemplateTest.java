package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Protects immutable needs-resource request identity from repeated sensor
 * request construction.
 */
class NeedsResourceRequestTemplateTest {
    @Test
    void canonicalizesUnorderedDuplicateMixedCaseFoodIds() {
        NeedsResourceRequestTemplate template = NeedsResourceRequestTemplate.from(
                " Food_Container ",
                64.0,
                99,
                64.0,
                new String[]{" Food_Wheat ", "FOOD_BEEF", "food_wheat", " "}
        );

        assertEquals("food_container", template.resourceKind());
        assertEquals(32.0, template.radius());
        assertEquals(8, template.verticalRadius());
        assertEquals(32.0, template.consumeRadius());
        assertEquals(List.of("food_beef", "food_wheat"), template.itemIds());
    }

    @Test
    void repeatedResolutionSharesOneRequestWithinNormalizedWorldAndCell() {
        NeedsResourceRequestTemplate template = NeedsResourceRequestTemplate.from(
                "food_container", 12.0, 2, 1.5, new String[]{"food_beef"}
        );
        NeedsResourceRequestTemplate.AreaRequestMemo memo =
                new NeedsResourceRequestTemplate.AreaRequestMemo();

        NeedsResourceSearchCoordinator.Request first = memo.resolve(
                template, "  WORLD ", 0.1, 64.1, 0.1
        );
        NeedsResourceSearchCoordinator.Request second = memo.resolve(
                template, "world", 3.9, 67.9, 3.9
        );

        assertSame(first, second);
        assertEquals("world", first.areaKey().worldName());
        assertEquals(List.of("food_beef"), first.itemIds());
    }

    @Test
    void memoRejectsInvalidOriginEvenWhenItMapsToTheCachedCell() {
        NeedsResourceRequestTemplate template = NeedsResourceRequestTemplate.from(
                "water", 12.0, 2, 1.5, new String[0]
        );
        NeedsResourceRequestTemplate.AreaRequestMemo memo =
                new NeedsResourceRequestTemplate.AreaRequestMemo();
        memo.resolve(template, "world", 0.1, 64.1, 0.1);

        assertThrows(IllegalArgumentException.class, () -> memo.resolve(
                template, "world", Double.NaN, 64.1, 0.1
        ));
    }

    @Test
    void crossingAnyFourBlockCellCoordinateCreatesNewRequest() {
        NeedsResourceRequestTemplate template = NeedsResourceRequestTemplate.from(
                "water", 12.0, 2, 1.5, new String[]{"ignored"}
        );
        NeedsResourceRequestTemplate.AreaRequestMemo memo =
                new NeedsResourceRequestTemplate.AreaRequestMemo();
        NeedsResourceSearchCoordinator.Request base = memo.resolve(
                template, "world", 0.1, 64.1, 0.1
        );

        NeedsResourceSearchCoordinator.Request xChanged = memo.resolve(
                template, "world", 4.0, 64.1, 0.1
        );
        NeedsResourceSearchCoordinator.Request yChanged = memo.resolve(
                template, "world", 0.1, 68.0, 0.1
        );
        NeedsResourceSearchCoordinator.Request zChanged = memo.resolve(
                template, "world", 0.1, 64.1, 4.0
        );

        assertNotSame(base, xChanged);
        assertNotSame(xChanged, yChanged);
        assertNotSame(yChanged, zChanged);
        assertTrue(base.itemIds().isEmpty());
    }

    @Test
    void worldScalarAndEffectiveFoodIdChangesCreateRequestsWithNewValues() {
        NeedsResourceRequestTemplate.AreaRequestMemo memo =
                new NeedsResourceRequestTemplate.AreaRequestMemo();
        NeedsResourceRequestTemplate baseTemplate = NeedsResourceRequestTemplate.from(
                "food_container", 12.0, 2, 1.5, new String[]{"food_beef"}
        );
        NeedsResourceSearchCoordinator.Request base = memo.resolve(
                baseTemplate, "world", 0.1, 64.1, 0.1
        );

        NeedsResourceSearchCoordinator.Request worldChanged = memo.resolve(
                NeedsResourceRequestTemplate.from(
                        "food_container", 12.0, 2, 1.5, new String[]{"food_beef"}
                ),
                "other-world", 0.1, 64.1, 0.1
        );
        NeedsResourceSearchCoordinator.Request scalarChanged = memo.resolve(
                NeedsResourceRequestTemplate.from(
                        "food_container", 18.0, 4, 3.0, new String[]{"food_beef"}
                ),
                "other-world", 0.1, 64.1, 0.1
        );
        NeedsResourceSearchCoordinator.Request idsChanged = memo.resolve(
                NeedsResourceRequestTemplate.from(
                        "food_container", 18.0, 4, 3.0, new String[]{"food_wheat"}
                ),
                "other-world", 0.1, 64.1, 0.1
        );
        NeedsResourceSearchCoordinator.Request resourceChanged = memo.resolve(
                NeedsResourceRequestTemplate.from(
                        "water", 18.0, 4, 3.0, new String[0]
                ),
                "other-world", 0.1, 64.1, 0.1
        );

        assertNotSame(base, worldChanged);
        assertNotSame(worldChanged, scalarChanged);
        assertNotSame(scalarChanged, idsChanged);
        assertNotSame(idsChanged, resourceChanged);
        assertEquals(18.0, scalarChanged.radius());
        assertEquals(4, scalarChanged.verticalRadius());
        assertEquals(3.0, scalarChanged.consumeRadius());
        assertEquals(List.of("food_wheat"), idsChanged.itemIds());
        assertEquals("water", resourceChanged.resourceKind());
    }

    @Test
    void inputArrayAndReturnedListCannotMutateAnExistingRequest() {
        String[] input = {" FOOD_BEEF "};
        NeedsResourceRequestTemplate template = NeedsResourceRequestTemplate.from(
                "food_container", 12.0, 2, 1.5, input
        );
        input[0] = "food_wheat";

        NeedsResourceSearchCoordinator.Request request =
                new NeedsResourceRequestTemplate.AreaRequestMemo().resolve(
                        template, "world", 0.1, 64.1, 0.1
                );

        assertEquals(List.of("food_beef"), request.itemIds());
        assertThrows(UnsupportedOperationException.class,
                () -> request.itemIds().add("food_wheat"));
        assertEquals(List.of("food_beef"), request.itemIds());
    }

    @Test
    void waterRequestsAlwaysUseAnEmptyCanonicalItemList() {
        NeedsResourceSearchCoordinator.Request request = NeedsResourceSearchCoordinator.Request.forArea(
                "water", "world", 0.1, 64.1, 0.1, 12.0, 2, 1.5,
                List.of("food_beef")
        );

        assertTrue(request.itemIds().isEmpty());
    }

    @Test
    void publicRequestFactoryKeepsHashCollisionsDistinctByCanonicalList() {
        NeedsResourceSearchCoordinator.Request first = NeedsResourceSearchCoordinator.Request.forArea(
                "food_container", "world", 0.1, 64.1, 0.1, 12.0, 2, 1.5,
                List.of("b=0")
        );
        NeedsResourceSearchCoordinator.Request second = NeedsResourceSearchCoordinator.Request.forArea(
                "food_container", "world", 0.1, 64.1, 0.1, 12.0, 2, 1.5,
                List.of("a\\0")
        );

        assertEquals(first.itemIds().hashCode(), second.itemIds().hashCode());
        assertNotSame(first, second);
        assertNotEquals(first, second);
    }
}
