package com.seleniumboot.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
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
        assertEquals(7, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains(
                "Delete the lifecycle glue; extend BaseTest. Driver creation, per-thread isolation, and teardown are handled for you.")));
    }

    @Test
    void detectsJunit4DriverLifecycleMethods() throws Exception {
        var findings = new Analyzer().analyze(fixture("lifecycle-junit4")).findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-017")).toList();
        assertEquals(3, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains("extend BaseTest")));
    }

    @Test
    void detectsJunit5DriverLifecycleMethods() throws Exception {
        var findings = new Analyzer().analyze(fixture("lifecycle-junit5")).findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-017")).toList();
        assertEquals(4, findings.size());
        assertTrue(findings.stream().allMatch(finding -> finding.advice().contains("extend BaseTest")));
    }

    @Test
    void ignoresLifecycleMethodsWithoutDriverSetupOrTeardown() {
        var report = new Analyzer().analyzeSource("class BaseTest { @BeforeEach void setUp() { prepareData(); } }");
        assertFalse(report.findings().stream().anyMatch(finding -> finding.ruleId().equals("MIG-017")));
    }

    @Test
    void mixedLifecycleMethodRequiresManualReview() {
        var finding = new Analyzer().analyzeSource("""
                class BaseTest {
                    @AfterEach void tearDown() {
                        driver.quit();
                        logout();
                    }
                }
                """).findings().stream().filter(item -> item.ruleId().equals("MIG-017")).findFirst().orElseThrow();

        assertEquals(Finding.Status.MANUAL, finding.status());
        assertEquals("Remove the driver setup; keep the rest.", finding.advice());
    }

    @Test
    void ignoresQuitOnNonDriverReceiver() {
        var report = new Analyzer().analyzeSource("class BaseTest { @AfterEach void tearDown() { browser.quit(); } }");
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
    void detectsPageObjectsFindByFieldsAndPageFactoryCalls() {
        var report = new Analyzer().analyzeSource("""
            import org.openqa.selenium.support.FindBy;
            class LoginPage {
                @FindBy(id = "username") private WebElement username;
                @org.openqa.selenium.support.FindBy(css = ".submit") private WebElement submit;
                LoginPage(org.openqa.selenium.WebDriver driver) {
                    org.openqa.selenium.support.PageFactory.initElements(driver, this);
                }
            }
            class NotAPage { NotAPage(String name) {} }
            """);

        assertEquals(1, report.findings().stream().filter(f -> f.ruleId().equals("MIG-010")).count());
        assertEquals(2, report.findings().stream().filter(f -> f.ruleId().equals("MIG-011")).count());
        assertEquals(1, report.findings().stream().filter(f -> f.ruleId().equals("MIG-012")).count());
        assertTrue(report.render().contains("MIG-010 (Page objects):"));
        assertTrue(report.render().contains("MIG-011 (@FindBy fields):"));
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

    @Test
    void detectsGroovyGradleBuildAndDependencies() throws Exception {
        String output = new Analyzer().analyze(fixture("gradle-groovy")).render();
        assertTrue(output.contains("Detected technologies"));
        assertTrue(output.contains("Build system: Gradle (Groovy DSL)"));
        assertTrue(output.contains("Dependency: org.seleniumhq.selenium:selenium-java:4.21.0"));
        assertTrue(output.contains("Dependency: org.testng:testng:7.10.2"));
    }

    @Test
    void detectsKotlinGradleBuildAndDependencies() throws Exception {
        String output = new Analyzer().analyze(fixture("gradle-kotlin")).render();
        assertTrue(output.contains("Detected technologies"));
        assertTrue(output.contains("Build system: Gradle (Kotlin DSL)"));
        assertTrue(output.contains("Dependency: org.seleniumhq.selenium:selenium-java:4.21.0"));
        assertTrue(output.contains("Dependency: org.junit.jupiter:junit-jupiter:5.10.2"));
    }

    @Test
    void reportsMavenDependenciesInTheSameSection() throws Exception {
        String output = new Analyzer().analyze(fixture("maven")).render();
        assertTrue(output.contains("Detected technologies"));
        assertTrue(output.contains("Build system: Maven"));
        assertTrue(output.contains("Dependency: org.seleniumhq.selenium:selenium-java:4.21.0"));
    }

    @Test
    void reportsMalformedMavenBuildAndContinuesAnalysis(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project>");
        Files.writeString(root.resolve("build.gradle"), "implementation 'org.example:sample:1.0'");

        String output = new Analyzer().analyze(root).render();

        assertTrue(output.contains("Build system: Maven (could not parse pom.xml)"));
        assertTrue(output.contains("Build system: Gradle (Groovy DSL)"));
        assertTrue(output.contains("Dependency: org.example:sample:1.0"));
    }

    @Test
    void ignoresBuildFilesInGeneratedAndVendorDirectories(@TempDir Path root) throws Exception {
        for (String directory : List.of("target", "build", ".gradle", "node_modules")) {
            Path ignored = Files.createDirectories(root.resolve(directory));
            Files.writeString(ignored.resolve("build.gradle"), "implementation 'org.example:sample:1.0'");
        }

        String output = new Analyzer().analyze(root).render();

        assertFalse(output.contains("Build system: Gradle"));
        assertFalse(output.contains("Dependency: org.example:sample:1.0"));
        assertTrue(output.contains("No supported build descriptor found"));
    }

    @Test
    void allAutoFindingsHaveFullConfidence() {
        var report = new Analyzer().analyzeSource("""
                class AutoOnly {
                    ThreadLocal<WebDriver> driver = new ThreadLocal<>();
                }
                """);

        assertEquals(100, report.estimatedConfidence());
    }

    @Test
    void allManualFindingsDoNotDropConfidenceToZero() {
        var report = new Analyzer().analyzeSource("""
                class Page {
                    void waitForElement() {
                        new WebDriverWait(driver, Duration.ofSeconds(5))
                                .until(ExpectedConditions.visibilityOfElementLocated(By.id("x")));
                    }
                }
                """);

        assertEquals(50, report.estimatedConfidence());
    }

    @Test
    void mixedAutoAndManualFilesProduceWeightedConfidence() {
        var report = new Report(
                2,
                2,
                List.of(),
                List.of(
                        new Finding("AUTO", Finding.Status.AUTO, "Auto.java", 1, "", ""),
                        new Finding("MANUAL", Finding.Status.MANUAL, "Manual.java", 1, "", "")
                )
        );

        assertEquals(75, report.estimatedConfidence());
    }

    @Test
    void unparsableFilesLowerConfidence() {
        var report = new Report(
                2,
                1,
                List.of("Broken.java"),
                List.of(
                        new Finding("AUTO", Finding.Status.AUTO, "Auto.java", 1, "", "")
                )
        );

        assertEquals(50, report.estimatedConfidence());
    }

    @Test
    void mig003EmitsOneFindingPerWaitStatement() {
        var report = new Analyzer().analyzeSource("""
                class Page {
                    void waitForElement() {
                        new WebDriverWait(driver, Duration.ofSeconds(5))
                                .until(ExpectedConditions.visibilityOfElementLocated(By.id("x")));
                    }
                }
                """);

        assertEquals(1, report.findings().stream()
                .filter(finding -> finding.ruleId().equals("MIG-003"))
                .count());
    }


}
