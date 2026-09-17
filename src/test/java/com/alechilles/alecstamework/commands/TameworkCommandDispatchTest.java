package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.Message;
import com.alechilles.alecstamework.items.CommandTargetHudDebugLog;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises full command dispatch, including the reported getter and view routes. */
class TameworkCommandDispatchTest {
    @Test
    void fullGetterCommandsReachPlayerExecutionInsteadOfCollectionHelp() throws Exception {
        for (String leaf : List.of("lifestage", "happiness", "traits", "owner", "tamed", "flock")) {
            RecordingSender sender = dispatch("tw debug get " + leaf, true);
            assertEquals(1, sender.messages.size(), leaf);
            assertEquals("server.commands.errors.playerOrArg", sender.messages.getFirst().getMessageId(), leaf);
        }
    }

    @Test
    void incompleteGetCommandShowsAvailableQueries() throws Exception {
        RecordingSender sender = dispatch("tw debug get", true);
        assertTrue(sender.messages.stream().anyMatch(message -> plainText(message).contains("lifestage")));
    }

    @Test
    void spawnMarkerDeletionIsDispatchedOutsideView() throws Exception {
        RecordingSender sender = dispatch("tw debug delete-spawn-marker 12", true);
        assertEquals("server.commands.errors.playerOrArg", sender.messages.getFirst().getMessageId());
        RecordingSender view = dispatch("tw debug view", true);
        assertTrue(view.messages.stream().noneMatch(message -> plainText(message).contains("delete")));
    }

    @Test
    void getterCannotBypassCommandPermission() throws Exception {
        RecordingSender sender = dispatch("tw debug get lifestage", false);
        assertEquals("server.commands.parsing.error.noPermissionForCommand", sender.messages.getFirst().getMessageId());
    }

    @Test
    void invalidLoggingStateDoesNotToggleAndReportsError() throws Exception {
        boolean previous = CommandTargetHudDebugLog.isEnabled();
        try {
            dispatch("tw debug log target-hud off", true);
            RecordingSender sender = dispatch("tw debug log target-hud offf", true);
            assertFalse(CommandTargetHudDebugLog.isEnabled());
            assertEquals("server.tamework.commands.invalidToggle", sender.messages.getFirst().getMessageId());
        } finally {
            CommandTargetHudDebugLog.setEnabled(previous);
        }
    }

    private static String plainText(Message message) {
        StringBuilder text = new StringBuilder(message.getRawText() == null ? "" : message.getRawText());
        for (Message child : message.getChildren()) {
            text.append(plainText(child));
        }
        return text.toString();
    }

    private static RecordingSender dispatch(String input, boolean allowed) throws Exception {
        TameworkCommandRoot root = new TameworkCommandRoot();
        root.setOwner(() -> "Alechilles:Alec's Tamework!");
        root.completeRegistration();
        RecordingSender sender = new RecordingSender(allowed);
        ParseResult result = new ParseResult();
        var tokens = Tokenizer.parseArguments(input, result);
        var future = root.acceptCall(sender, ParserContext.of(tokens, input, result), result);
        if (future != null) {
            future.join();
        }
        if (allowed) {
            assertFalse(result.failed(), input);
        } else {
            assertTrue(result.failed(), input);
            result.sendMessages(sender);
        }
        return sender;
    }

    private static final class RecordingSender implements CommandSender {
        private final List<Message> messages = new ArrayList<>();
        private final boolean allowed;

        private RecordingSender(boolean allowed) { this.allowed = allowed; }
        @Override public String getUsername() { return "Command test"; }
        @Override public UUID getUuid() { return new UUID(0, 1); }
        @Override public boolean hasPermission(String permission) { return allowed; }
        @Override public boolean hasPermission(String permission, boolean defaultValue) { return allowed; }
        @Override public void sendMessage(Message message) { messages.add(message); }
    }
}
