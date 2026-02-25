package com.alechilles.alecstamework.commands;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Parses `/tw` trait command arguments into validated trait/value requests.
 */
final class TameworkTraitCommandInputParser {
    private TameworkTraitCommandInputParser() {
    }

    static ParseResult parseSetTraits(@Nullable String input) {
        String[] tokens = tokenize(input);
        if (tokens.length < 4) {
            return ParseResult.error("Usage: /tw settraits <TraitId> <Value> [TraitId Value ...]");
        }
        int argumentCount = tokens.length - 2;
        if ((argumentCount % 2) != 0) {
            return ParseResult.error("Usage: /tw settraits <TraitId> <Value> [TraitId Value ...]");
        }
        return parsePairs(tokens, "settraits");
    }

    static ParseResult parseAddTrait(@Nullable String input) {
        String[] tokens = tokenize(input);
        if (tokens.length != 4) {
            return ParseResult.error("Usage: /tw addtrait <TraitId> <Value>");
        }
        ParseResult parsed = parsePairs(tokens, "addtrait");
        if (!parsed.isSuccess()) {
            return parsed;
        }
        if (parsed.requests().size() != 1) {
            return ParseResult.error("Usage: /tw addtrait <TraitId> <Value>");
        }
        return parsed;
    }

    private static ParseResult parsePairs(String[] tokens, String commandName) {
        ArrayList<TraitRequest> requests = new ArrayList<>();
        for (int i = 2; i < tokens.length; i += 2) {
            String traitId = tokens[i];
            if (traitId == null || traitId.isBlank()) {
                return ParseResult.error("Trait id at argument " + (i + 1) + " is empty.");
            }
            String rawValue = i + 1 < tokens.length ? tokens[i + 1] : null;
            Double value = parseFiniteDouble(rawValue);
            if (value == null) {
                return ParseResult.error("Value for trait '" + traitId + "' is invalid. "
                        + "Usage: /tw " + commandName + " <TraitId> <Value>"
                        + ("settraits".equalsIgnoreCase(commandName) ? " [TraitId Value ...]" : ""));
            }
            requests.add(new TraitRequest(traitId, value));
        }
        if (requests.isEmpty()) {
            return ParseResult.error("No trait values were provided.");
        }
        return ParseResult.success(requests);
    }

    @Nullable
    private static Double parseFiniteDouble(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(input.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String[] tokenize(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        return input.trim().split("\\s+");
    }

    static final class ParseResult {
        private final List<TraitRequest> requests;
        private final String errorMessage;

        private ParseResult(@Nonnull List<TraitRequest> requests, @Nullable String errorMessage) {
            this.requests = requests;
            this.errorMessage = errorMessage;
        }

        static ParseResult success(@Nonnull List<TraitRequest> requests) {
            return new ParseResult(List.copyOf(requests), null);
        }

        static ParseResult error(@Nonnull String message) {
            return new ParseResult(List.of(), message);
        }

        boolean isSuccess() {
            return errorMessage == null;
        }

        @Nonnull
        List<TraitRequest> requests() {
            return requests;
        }

        @Nullable
        String errorMessage() {
            return errorMessage;
        }
    }

    static final class TraitRequest {
        private final String traitId;
        private final double value;

        TraitRequest(@Nonnull String traitId, double value) {
            this.traitId = traitId;
            this.value = value;
        }

        @Nonnull
        String traitId() {
            return traitId;
        }

        double value() {
            return value;
        }
    }
}

