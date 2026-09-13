package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Read-only companion-guide overlay for the command panel.
 *
 * <p>The guide deliberately has no companion, profile, or command authority.
 * It only changes its own visible page and sends no game-play actions.</p>
 */
final class TameworkCompanionGuide {
    static final String UI_PATH = "TameworkCompanionGuide.ui";
    static final String ROOT = "#TameworkCompanionGuideRoot";
    static final String ACTION_OPEN = "guide:open";
    static final String ACTION_CLOSE = "guide:close";

    private static final String ACTION_PREFIX = "guide:";
    private static final String ACTION_PREVIOUS = "guide:previous";
    private static final String ACTION_NEXT = "guide:next";
    private static final String ACTION_TOPIC_PREFIX = "guide:topic:";
    private static final String ACTION_EXAMPLE_PREVIOUS = "guide:example:previous";
    private static final String ACTION_EXAMPLE_NEXT = "guide:example:next";
    private static final String EVENT_COMMAND_ID = "CommandId";

    private final Supplier<String> languageSupplier;
    private int topicIndex;
    private int exampleIndex;
    private boolean visible;

    TameworkCompanionGuide(@Nonnull Supplier<String> languageSupplier) {
        this.languageSupplier = Objects.requireNonNull(languageSupplier, "languageSupplier");
    }

    /** Appends the hidden overlay and its guide-only event bindings to a command panel. */
    void build(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.append("#TameworkCommandMenuWheel", UI_PATH);
        applyPage(commandBuilder);
        commandBuilder.set(ROOT + ".Visible", false);
        bind(eventBuilder, "#TameworkCompanionGuideCloseButton", ACTION_CLOSE);
        bind(eventBuilder, "#TameworkCompanionGuideBackButton", ACTION_CLOSE);
        bind(eventBuilder, "#TameworkCompanionGuidePreviousButton", ACTION_PREVIOUS);
        bind(eventBuilder, "#TameworkCompanionGuideNextButton", ACTION_NEXT);
        bind(eventBuilder, "#TameworkCompanionGuideExamplePreviousButton", ACTION_EXAMPLE_PREVIOUS);
        bind(eventBuilder, "#TameworkCompanionGuideExampleNextButton", ACTION_EXAMPLE_NEXT);
        for (int index = 0; index < TameworkCompanionGuideContent.TOPICS.length; index++) {
            bind(eventBuilder, "#TameworkCompanionGuideTopic" + index, ACTION_TOPIC_PREFIX + index);
        }
    }

    /**
     * Consumes guide-prefixed command-panel events and updates only the overlay.
     * Callers should suppress unrelated panel actions while {@link #isVisible()} is true.
     */
    boolean handle(String action, @Nonnull UICommandBuilder commandBuilder) {
        if (action == null || !action.startsWith(ACTION_PREFIX)) {
            return false;
        }
        if (ACTION_OPEN.equals(action)) {
            visible = true;
            commandBuilder.set(ROOT + ".Visible", true);
            applyPage(commandBuilder);
            return true;
        }
        if (ACTION_CLOSE.equals(action)) {
            visible = false;
            commandBuilder.set(ROOT + ".Visible", false);
            return true;
        }
        if (!visible) {
            return true;
        }
        if (ACTION_PREVIOUS.equals(action)) {
            topicIndex = Math.max(0, topicIndex - 1);
            exampleIndex = 0;
        } else if (ACTION_NEXT.equals(action)) {
            topicIndex = Math.min(TameworkCompanionGuideContent.TOPICS.length - 1, topicIndex + 1);
            exampleIndex = 0;
        } else if (ACTION_EXAMPLE_PREVIOUS.equals(action)) {
            if (TameworkCompanionGuideCardRenderer.hasExampleScenarios(topicIndex)) {
                exampleIndex = Math.max(0, exampleIndex - 1);
            }
        } else if (ACTION_EXAMPLE_NEXT.equals(action)) {
            if (TameworkCompanionGuideCardRenderer.hasExampleScenarios(topicIndex)) {
                exampleIndex = Math.min(TameworkCompanionGuideCardRenderer.EXAMPLE_COUNT - 1,
                        exampleIndex + 1);
            }
        } else if (action.startsWith(ACTION_TOPIC_PREFIX)) {
            try {
                int requested = Integer.parseInt(action.substring(ACTION_TOPIC_PREFIX.length()));
                if (requested >= 0 && requested < TameworkCompanionGuideContent.TOPICS.length) {
                    topicIndex = requested;
                    exampleIndex = 0;
                }
            } catch (NumberFormatException ignored) {
                // A forged guide action must not escape into command execution.
            }
        } else {
            return true;
        }
        applyPage(commandBuilder, !ACTION_EXAMPLE_PREVIOUS.equals(action)
                && !ACTION_EXAMPLE_NEXT.equals(action));
        return true;
    }

    boolean isVisible() {
        return visible;
    }

    private void bind(UIEventBuilder eventBuilder, String selector, String action) {
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of(EVENT_COMMAND_ID, action), false);
    }

    private void applyPage(UICommandBuilder commands) {
        applyPage(commands, true);
    }

    private void applyPage(UICommandBuilder commands, boolean resetReadingArea) {
        TameworkCompanionGuideContent.Topic topic = TameworkCompanionGuideContent.TOPICS[topicIndex];
        String language = languageSupplier.get();
        commands.set("#TameworkCompanionGuideTitle.Text", text(language, "title"));
        commands.set("#TameworkCompanionGuideSubtitle.Text", text(language, "subtitle"));
        commands.set("#TameworkCompanionGuideTopicsLabel.Text", text(language, "topics"));
        commands.set("#TameworkCompanionGuideCloseButton.TooltipText", text(language, "close"));
        commands.set("#TameworkCompanionGuideExampleLabel.Text", text(language, "example"));
        commands.set("#TameworkCompanionGuidePreviousButton.Text", text(language, "previous"));
        commands.set("#TameworkCompanionGuideNextButton.Text", text(language, "next"));
        commands.set("#TameworkCompanionGuideBackButton.Text", text(language, "back"));
        commands.set("#TameworkCompanionGuideContentTitle.Text", text(language, "topic." + topic.key() + ".title"));
        // A new topic starts at the top; changing examples keeps the reader's place.
        if (resetReadingArea) {
            commands.clear("#TameworkCompanionGuideBodyViewport");
            commands.append("#TameworkCompanionGuideBodyViewport", "TameworkCompanionGuideBody.ui");
            // Locale bodies separate sections with a blank line, then heading from prose
            // with one newline. Keep that translated content intact while styling each part.
            String[] sections = text(language, "topic." + topic.key() + ".body").split("\n\n");
            for (int index = 0; index < sections.length; index++) {
                commands.append("#TameworkCompanionGuideSections", "TameworkCompanionGuideSection.ui");
                String selector = "#TameworkCompanionGuideSections[" + index + "]";
                int headingEnd = sections[index].indexOf('\n');
                commands.set(selector + " #Heading.Visible", headingEnd >= 0);
                commands.set(selector + " #Heading.Text", headingEnd < 0 ? "" : sections[index].substring(0, headingEnd));
                commands.set(selector + " #Body.Text", headingEnd < 0 ? sections[index] : sections[index].substring(headingEnd + 1));
            }
        }
        commands.set("#TameworkCompanionGuideExampleTitle.Text", text(language, "topic." + topic.key() + ".visual"));
        commands.set("#TameworkCompanionGuideExampleNote.Text", text(language, "topic." + topic.key() + ".note"));
        boolean scenarios = TameworkCompanionGuideCardRenderer.hasExampleScenarios(topicIndex);
        String scenarioKey = "sample." + topic.key();
        commands.set("#TameworkCompanionGuideScenarioTitle.Text", scenarios
                ? text(language, scenarioKey + ".scenario" + exampleIndex + ".title") : "");
        commands.set("#TameworkCompanionGuideScenarioTitle.Visible", scenarios);
        commands.set("#TameworkCompanionGuideExamplePreviousButton.Text", text(language, "previous"));
        commands.set("#TameworkCompanionGuideExampleNextButton.Text", text(language, "next"));
        commands.set("#TameworkCompanionGuideExamplePreviousButton.Visible", scenarios && exampleIndex > 0);
        commands.set("#TameworkCompanionGuideExampleNextButton.Visible",
                scenarios && exampleIndex < TameworkCompanionGuideCardRenderer.EXAMPLE_COUNT - 1);
        commands.set("#TameworkCompanionGuidePage.Text", LocalizedText.format(language, "tamework.ui.guide.page", topicIndex + 1, TameworkCompanionGuideContent.TOPICS.length));
        TameworkCompanionGuideCardRenderer.render(
                commands, topic, topicIndex, exampleIndex, language
        );
        commands.set("#TameworkCompanionGuidePreviousButton.Visible", topicIndex > 0);
        commands.set("#TameworkCompanionGuideNextButton.Visible", topicIndex < TameworkCompanionGuideContent.TOPICS.length - 1);
        for (int index = 0; index < TameworkCompanionGuideContent.TOPICS.length; index++) {
            TameworkCompanionGuideContent.Topic listed = TameworkCompanionGuideContent.TOPICS[index];
            commands.set("#TameworkCompanionGuideTopic" + index + ".Text",
                    text(language, "topic." + listed.key() + ".title"));
            commands.set("#TameworkCompanionGuideTopic" + index + ".OutlineColor",
                    index == topicIndex ? "#d5b15c" : "#414845");
        }
    }

    private static String text(String language, String key) {
        return LocalizedText.resolve(language, "tamework.ui.guide." + key).replace("\\n", "\n");
    }
}
