package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prevents owner-component writes from bypassing the population admission journal.
 */
class OwnerComponentWriteSafetyGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final String OWNER_COMPONENT_PATH =
            "com/alechilles/alecstamework/npc/components/TameworkOwnerComponent.java";
    private static final String MUTATION_SERVICE_PATH =
            "com/alechilles/alecstamework/ownership/OwnerComponentMutationService.java";
    private static final Pattern COMPONENT_TYPE_VARIABLE = Pattern.compile(
            "ComponentType\\s*<\\s*EntityStore\\s*,\\s*([A-Za-z_$.][A-Za-z0-9_$.]*)\\s*>\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern OWNER_VALUE_VARIABLE = Pattern.compile(
            "\\bTameworkOwnerComponent\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b"
    );
    private static final Pattern MUTATION_INVOCATION = Pattern.compile(
            "(?:(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)?)"
                    + "(?:putComponent|addComponent|removeComponent|tryRemoveComponent|"
                    + "safePutComponent|putIfPresent)\\s*\\("
    );

    @Test
    void ownerComponentWritesUseAdmissionAwareMutationFacade() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : listJavaFiles()) {
            String relativePath = toUnixRelativePath(sourceFile);
            if (OWNER_COMPONENT_PATH.equals(relativePath) || MUTATION_SERVICE_PATH.equals(relativePath)) {
                continue;
            }

            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            List<ComponentTypeBinding> componentTypeBindings = captureComponentTypeBindings(source);
            Set<String> ownerValueVariables = captureVariables(source, OWNER_VALUE_VARIABLE);
            findMutationViolations(relativePath, source, componentTypeBindings, violations);
            findSetterViolations(relativePath, source, ownerValueVariables, violations);
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Raw TameworkOwnerComponent writes are forbidden outside "
                        + "OwnerComponentMutationService. Route ownership changes through prepared admission so "
                        + "the durable population index and ECS state cannot diverge. The component codec is the "
                        + "only exception.\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    private static void findMutationViolations(String relativePath,
                                               String source,
                                               List<ComponentTypeBinding> componentTypeBindings,
                                               List<String> violations) {
        Matcher matcher = MUTATION_INVOCATION.matcher(source);
        while (matcher.find()) {
            int openParenthesis = matcher.end() - 1;
            int closeParenthesis = findClosingParenthesis(source, openParenthesis);
            if (closeParenthesis < 0) {
                continue;
            }
            String invocation = source.substring(matcher.start(), closeParenthesis + 1);
            if (!referencesOwnerComponent(invocation, matcher.start(), componentTypeBindings)) {
                continue;
            }
            violations.add(formatViolation(relativePath, source, matcher.start(), invocation));
        }
    }

    private static void findSetterViolations(String relativePath,
                                             String source,
                                             Set<String> ownerValueVariables,
                                             List<String> violations) {
        for (String variable : ownerValueVariables) {
            Pattern setterCall = Pattern.compile(
                    "\\b" + Pattern.quote(variable) + "\\s*\\.\\s*setOwner(?:Id|Name)\\s*\\("
            );
            Matcher matcher = setterCall.matcher(source);
            while (matcher.find()) {
                int openParenthesis = matcher.end() - 1;
                int closeParenthesis = findClosingParenthesis(source, openParenthesis);
                String invocation = closeParenthesis < 0
                        ? matcher.group()
                        : source.substring(matcher.start(), closeParenthesis + 1);
                violations.add(formatViolation(relativePath, source, matcher.start(), invocation));
            }
        }
    }

    private static boolean referencesOwnerComponent(String invocation,
                                                    int invocationOffset,
                                                    List<ComponentTypeBinding> componentTypeBindings) {
        if (invocation.contains("TameworkOwnerComponent")) {
            return true;
        }
        for (ComponentTypeBinding binding : componentTypeBindings) {
            if (binding.ownerComponent()
                    && containsWord(invocation, binding.variable())
                    && isActiveOwnerBinding(binding.variable(), invocationOffset, componentTypeBindings)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveOwnerBinding(String variable,
                                                int invocationOffset,
                                                List<ComponentTypeBinding> bindings) {
        ComponentTypeBinding nearest = null;
        for (ComponentTypeBinding binding : bindings) {
            if (!binding.variable().equals(variable) || binding.offset() >= invocationOffset) {
                continue;
            }
            if (nearest == null || binding.offset() > nearest.offset()) {
                nearest = binding;
            }
        }
        return nearest != null && nearest.ownerComponent();
    }

    private static Set<String> captureVariables(String source, Pattern pattern) {
        Set<String> variables = new HashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static List<ComponentTypeBinding> captureComponentTypeBindings(String source) {
        List<ComponentTypeBinding> bindings = new ArrayList<>();
        Matcher matcher = COMPONENT_TYPE_VARIABLE.matcher(source);
        while (matcher.find()) {
            bindings.add(new ComponentTypeBinding(
                    matcher.group(2),
                    matcher.group(1).endsWith("TameworkOwnerComponent"),
                    matcher.start()
            ));
        }
        return bindings;
    }

    private static boolean containsWord(String value, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(value).find();
    }

    private static int findClosingParenthesis(String source, int openParenthesis) {
        int depth = 0;
        boolean inString = false;
        boolean inCharacter = false;
        boolean escaped = false;
        for (int i = openParenthesis; i < source.length(); i++) {
            char current = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inCharacter) && current == '\\') {
                escaped = true;
                continue;
            }
            if (!inCharacter && current == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && current == '\'') {
                inCharacter = !inCharacter;
                continue;
            }
            if (inString || inCharacter) {
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String formatViolation(String relativePath, String source, int offset, String invocation) {
        long lineNumber = source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
        String compactInvocation = invocation.replaceAll("\\s+", " ").trim();
        return relativePath + ":" + lineNumber + " -> " + compactInvocation;
    }

    private static List<Path> listJavaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String toUnixRelativePath(Path path) {
        return MAIN_JAVA.relativize(path).toString().replace('\\', '/');
    }

    private record ComponentTypeBinding(String variable, boolean ownerComponent, int offset) {
    }
}
