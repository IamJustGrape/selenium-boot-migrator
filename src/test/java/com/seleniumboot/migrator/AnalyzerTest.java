package com.seleniumboot.migrator;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(AnalyzerTest.class.getResource("/" + name).toURI());
    }

    private static List<String> rules(String src) {
        return new Analyzer().analyzeSource(src).findings().stream().map(Finding::ruleId).toList();
    }

    @Test
    void detectsThreadLocalDriverAndWebDriverManager() {
        var r = rules("""
            public class DriverFactory {
                private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
                public static void create() { WebDriverManager.chromedriver().setup(); }
            }""");
        assertTrue(r.contains("MIG-001"));
        assertTrue(r.contains("MIG-002"));
        assertTrue(r.contains("MIG-015"));
    }

    @Test
    void detectsTestNgDriverLifecycleMethods() throws Exception {
        var findings = new Analyzer().analyze(fixture("lifecycle-testng")).findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-017")).toList();
        assertEquals(3, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains(
                "Delete the lifecycle glue; extend BaseTest. Driver creation, per-thread isolation, and teardown are handled for you.")));
    }

    @Test
    void detectsJunit4DriverLifecycleMethods() throws Exception {
        var findings = new Analyzer().analyze(fixture("lifecycle-junit4")).findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-017")).toList();
        assertEquals(2, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains("extend BaseTest")));
    }

    @Test
    void detectsJunit5DriverLifecycleMethods() throws Exception {
        var findings = new Analyzer().analyze(fixture("lifecycle-junit5")).findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-017")).toList();
        assertEquals(2, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains("extend BaseTest")));
    }

    @Test
    void ignoresLifecycleMethodsWithoutDriverSetupOrTeardown() {
        var report = new Analyzer().analyzeSource("class BaseTest { @BeforeEach void setUp() { prepareData(); } }");
        assertFalse(report.findings().stream().anyMatch(finding -> finding.ruleId().equals("MIG-017")));
    }

    @Test
    void detectsWaitsSleepsAndImplicitWait() {
        var r = rules("""
            class P { void m() throws Exception {
                new WebDriverWait(driver, Duration.ofSeconds(10)).until(ExpectedConditions.elementToBeClickable(By.id("x")));
                Thread.sleep(500);
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            } }""");
        assertTrue(r.containsAll(List.of("MIG-003", "MIG-014", "MIG-016")));
    }

    @Test
    void detectsRetryAndScreenshotListener() {
        var r = rules("""
            class R implements IRetryAnalyzer { public boolean retry(ITestResult t) { return true; } }
            class S implements ITestListener { public void onTestFailure(ITestResult t) {
                ((TakesScreenshot) d).getScreenshotAs(OutputType.FILE); } }""");
        assertTrue(r.contains("MIG-004"));
        assertTrue(r.contains("MIG-005"));
    }

    @Test
    void plainClassHasNoFindingsAndFullConfidence() {
        var report = new Analyzer().analyzeSource("class Plain { int x; }");
        assertTrue(report.findings().isEmpty());
        assertEquals(100, report.estimatedConfidence());
    }

    @Test
    void unparsableSourceIsReportedNotThrown() {
        var report = new Analyzer().analyzeSource("class {{{");
        assertEquals(1, report.unparsable().size());
    }
}
