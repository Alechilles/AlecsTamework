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
    private static final String EVENT_COMMAND_ID = "CommandId";

    private final Supplier<String> languageSupplier;
    private int topicIndex;
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
        } else if (ACTION_NEXT.equals(action)) {
            topicIndex = Math.min(TameworkCompanionGuideContent.TOPICS.length - 1, topicIndex + 1);
        } else if (action.startsWith(ACTION_TOPIC_PREFIX)) {
            try {
                int requested = Integer.parseInt(action.substring(ACTION_TOPIC_PREFIX.length()));
                if (requested >= 0 && requested < TameworkCompanionGuideContent.TOPICS.length) {
                    topicIndex = requested;
                }
            } catch (NumberFormatException ignored) {
                // A forged guide action must not escape into command execution.
            }
        } else {
            return true;
        }
        applyPage(commandBuilder);
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
        TameworkCompanionGuideContent.Topic topic = TameworkCompanionGuideContent.TOPICS[topicIndex];
        String language = languageSupplier.get();
        commands.set("#TameworkCompanionGuideTitle.Text", text(language, "title"));
        commands.set("#TameworkCompanionGuideSubtitle.Text", text(language, "subtitle"));
        commands.set("#TameworkCompanionGuideTopicsLabel.Text", text(language, "topics"));
        commands.set("#TameworkCompanionGuideCloseButton.TooltipText", text(language, "close"));
        commands.set("#TameworkCompanionGuideExampleLabel.Text", text(language, "example"));
        commands.set("#TameworkCompanionGuideSampleName.Text", text(language, "sample.name"));
        commands.set("#TameworkCompanionGuideSampleSubtitle.Text", text(language, "sample.subtitle"));
        commands.set("#TameworkCompanionGuideSampleProgress.Text", text(language, "sample.progress"));
        commands.set("#TameworkCompanionGuideSampleHealth.Text", text(language, "sample.health"));
        commands.set("#TameworkCompanionGuideSampleHappiness.Text", text(language, "sample.happiness"));
        commands.set("#TameworkCompanionGuidePreviousButton.Text", text(language, "previous"));
        commands.set("#TameworkCompanionGuideNextButton.Text", text(language, "next"));
        commands.set("#TameworkCompanionGuideBackButton.Text", text(language, "back"));
        commands.set("#TameworkCompanionGuideContentTitle.Text", text(language, "topic." + topic.key() + ".title"));
        // Recreate only the reading area so a new topic starts at the top.
        commands.clear("#TameworkCompanionGuideBodyViewport");
        commands.append("#TameworkCompanionGuideBodyViewport", "TameworkCompanionGuideBody.ui");
        commands.set("#TameworkCompanionGuideContentBody.Text", text(language, "topic." + topic.key() + ".body"));
        commands.set("#TameworkCompanionGuideExampleTitle.Text", text(language, "topic." + topic.key() + ".visual"));
        commands.set("#TameworkCompanionGuideExampleNote.Text", text(language, "topic." + topic.key() + ".note"));
        commands.set("#TameworkCompanionGuidePage.Text", LocalizedText.format(language, "tamework.ui.guide.page", topicIndex + 1, TameworkCompanionGuideContent.TOPICS.length));
        commands.set("#TameworkCompanionGuideSampleState.Text", text(language, "sample." + topic.key() + ".state"));
        commands.set("#TameworkCompanionGuideSampleCallout0.Text", text(language, "sample." + topic.key() + ".callout0"));
        commands.set("#TameworkCompanionGuideSampleCallout1.Text", text(language, "sample." + topic.key() + ".callout1"));
        commands.set("#TameworkCompanionGuideSampleCallout2.Text", text(language, "sample." + topic.key() + ".callout2"));
        commands.set("#TameworkCompanionGuideSampleCallout3.Text", text(language, "sample." + topic.key() + ".callout3"));
        commands.set("#TameworkCompanionGuideSampleCare.Visible", topicIndex != 8);
        commands.set("#TameworkCompanionGuideHappiness.Visible", topicIndex != 8);
        commands.set("#TameworkCompanionGuideBondedBadge.Text", text(language, "sample.bonded"));
        commands.set("#TameworkCompanionGuideBondedBadge.Visible", topicIndex == 8);
        boolean scenarios = topicIndex != 1 && topicIndex != 5 && topicIndex != 9;
        commands.set("#TameworkCompanionGuideScenarios.Visible", scenarios);
        commands.set("#TameworkCompanionGuideSampleCard.Visible", !scenarios);
        if (scenarios) {
            for (int index = 0; index < 3; index++) {
                String selector = "#TameworkCompanionGuideScenario" + index;
                String key = "sample." + topic.key() + ".scenario" + index;
                commands.set(selector + " #Title.Text", text(language, key + ".title"));
                commands.set(selector + " #Body.Text", text(language, key + ".body"));
                commands.set(selector + " #Portrait.Visible", topicIndex != 10);
            }
        }
        commands.set("#TameworkCompanionGuideSampleCommand1.Text", text(language, "sample." + topic.key() + ".action0"));
        commands.set("#TameworkCompanionGuideSampleCommand2.Text", text(language, "sample." + topic.key() + ".action1"));
        commands.set("#TameworkCompanionGuideSampleCommand3.Text", text(language, "sample." + topic.key() + ".action2"));
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
