package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** Splits a trusted bundled SQLite script without breaking quoted semicolons or comments. */
final class SqlScriptParser {
    private SqlScriptParser() {
    }

    @Nonnull
    static List<String> statements(@Nonnull String script) {
        if (script == null) {
            throw new IllegalArgumentException("SQL script is required");
        }
        ArrayList<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        State state = State.SQL;
        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            switch (state) {
                case SQL -> {
                    if (value == '\'') {
                        current.append(value);
                        state = State.SINGLE_QUOTE;
                    } else if (value == '"') {
                        current.append(value);
                        state = State.DOUBLE_QUOTE;
                    } else if (value == '-' && next == '-') {
                        current.append(value).append(next);
                        index++;
                        state = State.LINE_COMMENT;
                    } else if (value == '/' && next == '*') {
                        current.append(value).append(next);
                        index++;
                        state = State.BLOCK_COMMENT;
                    } else if (value == ';') {
                        addStatement(statements, current);
                    } else {
                        current.append(value);
                    }
                }
                case SINGLE_QUOTE -> {
                    current.append(value);
                    if (value == '\'' && next == '\'') {
                        current.append(next);
                        index++;
                    } else if (value == '\'') {
                        state = State.SQL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    current.append(value);
                    if (value == '"' && next == '"') {
                        current.append(next);
                        index++;
                    } else if (value == '"') {
                        state = State.SQL;
                    }
                }
                case LINE_COMMENT -> {
                    current.append(value);
                    if (value == '\n') {
                        state = State.SQL;
                    }
                }
                case BLOCK_COMMENT -> {
                    current.append(value);
                    if (value == '*' && next == '/') {
                        current.append(next);
                        index++;
                        state = State.SQL;
                    }
                }
            }
        }
        if (state == State.SINGLE_QUOTE || state == State.DOUBLE_QUOTE || state == State.BLOCK_COMMENT) {
            throw new IllegalArgumentException("Unterminated SQL script token: " + state);
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private enum State {
        SQL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
