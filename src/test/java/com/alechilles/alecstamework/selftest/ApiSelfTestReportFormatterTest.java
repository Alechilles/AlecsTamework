package com.alechilles.alecstamework.selftest;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSelfTestReportFormatterTest {
    @Test
    void formatsSummaryAndVerboseOutput() {
        ApiSelfTestRunReport report = new ApiSelfTestRunReport(List.of(
                new ApiSelfTestSuiteResult("core", List.of(
                        new ApiSelfTestAssertion("api available", true, "version=0.1.0"),
                        new ApiSelfTestAssertion("capabilities", false, "missing PROFILE_DATA")
                ))
        ));

        List<String> summary = ApiSelfTestReportFormatter.format(report, false);
        assertTrue(summary.stream().anyMatch(line -> line.contains("FAIL core (1/2): capabilities - missing PROFILE_DATA")));
        assertTrue(summary.stream().anyMatch(line -> line.contains("Totals: 1/2 passed, 1 failed")));

        List<String> verbose = ApiSelfTestReportFormatter.format(report, true);
        assertTrue(verbose.stream().anyMatch(line -> line.contains("PASS api available")));
        assertTrue(verbose.stream().anyMatch(line -> line.contains("FAIL capabilities")));
    }

    @Test
    void boundsAndFlattensFailureDetailForPrivateOperatorOutput() {
        String detail = "first line\nsecond line\t" + "x".repeat(300) + "secret-tail";
        ApiSelfTestRunReport report = new ApiSelfTestRunReport(List.of(
                new ApiSelfTestSuiteResult("hydragon-integrations", List.of(
                        new ApiSelfTestAssertion("isolated failure", false, detail))
                ))
        );

        List<String> lines = ApiSelfTestReportFormatter.format(report, true);

        assertTrue(lines.stream().noneMatch(line -> line.contains("\n") || line.contains("\t")));
        assertTrue(lines.stream().filter(line -> line.contains("isolated failure"))
                .allMatch(line -> line.length() < 340));
        assertFalse(lines.stream().anyMatch(line -> line.contains("secret-tail")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("FAIL isolated failure")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Totals: 0/1 passed, 1 failed")));
    }
}
