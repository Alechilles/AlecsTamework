package com.alechilles.alecstamework.selftest;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

public final class ApiSelfTestReportFormatter {
    private static final int MAX_DETAIL_CHARACTERS = 240;

    private ApiSelfTestReportFormatter() {
    }

    @Nonnull
    public static List<String> format(@Nonnull ApiSelfTestRunReport report, boolean verbose) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("=== Tamework API Self-Test ===");
        for (ApiSelfTestSuiteResult suite : report.suites()) {
            lines.add(formatSuiteSummary(suite));
            if (!verbose) {
                continue;
            }
            for (ApiSelfTestAssertion assertion : suite.assertions()) {
                StringBuilder builder = new StringBuilder();
                builder.append(assertion.passed() ? "  PASS " : "  FAIL ");
                builder.append(assertion.name());
                if (assertion.detail() != null && !assertion.detail().isBlank()) {
                    builder.append(": ").append(boundedDetail(assertion.detail()));
                }
                lines.add(builder.toString());
            }
        }
        lines.add(
                "Totals: "
                        + report.totalPassed()
                        + "/"
                        + report.totalAssertions()
                        + " passed"
                        + (report.passed() ? "" : ", " + report.totalFailed() + " failed")
        );
        return lines;
    }

    @Nonnull
    public static String formatFixtureSet(@Nonnull ApiSelfTestFixtureSet fixtureSet) {
        return "Fixture set="
                + fixtureSet.fixtureSetId()
                + ", world="
                + fixtureSet.worldName()
                + ", toolId="
                + fixtureSet.toolId()
                + ", fixtures="
                + fixtureSet.fixtures().keySet();
    }

    @Nonnull
    private static String formatSuiteSummary(@Nonnull ApiSelfTestSuiteResult suite) {
        String summary = (suite.passed() ? "PASS " : "FAIL ")
                + suite.suiteName()
                + " ("
                + suite.passedCount()
                + "/"
                + suite.assertions().size()
                + ")";
        if (suite.passed()) {
            return summary;
        }
        for (ApiSelfTestAssertion assertion : suite.assertions()) {
            if (assertion.passed()) {
                continue;
            }
            if (assertion.detail() == null || assertion.detail().isBlank()) {
                return summary + ": " + assertion.name();
            }
            return summary + ": " + assertion.name() + " - "
                    + boundedDetail(assertion.detail());
        }
        return summary;
    }

    /** Keeps operator output single-line and bounded even when an API returns hostile detail text. */
    @Nonnull
    private static String boundedDetail(@Nonnull String detail) {
        String singleLine = detail.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\\p{Cntrl}&&[^ ]]", "?")
                .trim();
        if (singleLine.length() <= MAX_DETAIL_CHARACTERS) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_DETAIL_CHARACTERS - 3) + "...";
    }
}
