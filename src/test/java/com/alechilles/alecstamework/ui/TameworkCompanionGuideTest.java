package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class TameworkCompanionGuideTest {
    @Test
    void exampleControlsCannotEmitRealCompanionActions() {
        TameworkCompanionGuide guide = new TameworkCompanionGuide(() -> "en-US");
        UIEventBuilder events = new UIEventBuilder();
        guide.build(new UICommandBuilder(), events);

        // Reusing real card controls must never give a sample animal a game action.
        for (var event : events.getEvents()) {
            BsonDocument payload = BsonDocument.parse(event.data);
            assertTrue(payload.size() == 1
                    && payload.containsKey("CommandId")
                    && payload.getString("CommandId").getValue().startsWith("guide:"),
                    () -> "A guide control can emit a companion action: " + event.selector);
        }
        guide.handle("guide:open", new UICommandBuilder());
        assertTrue(guide.isVisible());
        for (int topic = 0; topic < TameworkCompanionGuideContent.TOPICS.length; topic++) {
            assertTrue(guide.handle("guide:topic:" + topic, new UICommandBuilder()));
            for (int example = 0; example < 3; example++) {
                assertTrue(guide.handle("guide:example:next", new UICommandBuilder()));
            }
        }
        assertFalse(guide.handle("Follow", new UICommandBuilder()));
        guide.handle("guide:close", new UICommandBuilder());
        assertFalse(guide.isVisible());
    }
}
