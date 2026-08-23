package com.alechilles.alecstamework.commands;

import java.util.Arrays;
import javax.annotation.Nonnull;

/** Parses extra command arguments after a known command token. */
final class TameworkCommandInput {
    private TameworkCommandInput() {
    }

    static String firstArgument(String input, String commandToken) {
        String[] arguments = argumentsAfter(input, commandToken);
        return arguments.length == 0 ? null : arguments[0];
    }

    @Nonnull
    static String[] argumentsAfter(String input, String commandToken) {
        if (input == null || input.isBlank() || commandToken == null || commandToken.isBlank()) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        for (int index = tokens.length - 1; index >= 0; index--) {
            if (commandToken.equalsIgnoreCase(tokens[index])) {
                return Arrays.copyOfRange(tokens, index + 1, tokens.length);
            }
        }
        return new String[0];
    }
}
