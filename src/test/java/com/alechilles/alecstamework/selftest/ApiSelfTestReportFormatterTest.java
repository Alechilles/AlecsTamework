package com.alechilles.alecstamework.selftest;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
