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
    void detectsMavenAndTechnologies() throws Exception {
        Path temp = Files.createTempDirectory("selenium-test");

        Files.writeString(temp.resolve("pom.xml"), """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.seleniumhq.selenium</groupId>
                        <artifactId>selenium-java</artifactId>
                        <version>4.20.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.testng</groupId>
                        <artifactId>testng</artifactId>
                        <version>7.10.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                    </dependency>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                    </dependency>
                    <dependency>
                        <groupId>io.github.bonigarcia</groupId>
                        <artifactId>webdrivermanager</artifactId>
                        <version>5.8.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.aventstack</groupId>
                        <artifactId>extentreports</artifactId>
                        <version>5.1.1</version>
                    </dependency>
                    <dependency>
                        <groupId>io.qameta.allure</groupId>
                        <artifactId>allure-testng</artifactId>
                        <version>2.27.0</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        var report = new Analyzer().analyze(temp);

        assertTrue(report.render().contains("Maven"));
        assertTrue(report.render().contains("Selenium 4.20.0"));
        assertTrue(report.render().contains("TestNG"));
        assertTrue(report.render().contains("JUnit 5"));
        assertTrue(report.render().contains("WebDriverManager"));
        assertEquals(List.of("Selenium 4.20.0", "TestNG", "JUnit 4", "JUnit 5",
                "WebDriverManager", "ExtentReports", "Allure"), report.recognizedTechnologies());
    }

    @Test
    void recognizesOnlyDirectDependenciesAndDeduplicatesModules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>org.testng</groupId>
                        <artifactId>testng</artifactId>
                        <version>7.10.0</version>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <dependencies>
                                <dependency>
                                    <groupId>com.aventstack</groupId>
                                    <artifactId>extentreports</artifactId>
                                    <version>5.1.1</version>
                                </dependency>
                            </dependencies>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """);

        Path module = Files.createDirectories(root.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.testng</groupId>
                        <artifactId>testng</artifactId>
                        <version>7.10.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.seleniumhq.selenium</groupId>
                        <artifactId>selenium-java</artifactId>
                        <version>4.20.0</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        var report = new Analyzer().analyze(root);

        assertEquals(List.of("Selenium 4.20.0", "TestNG"), report.recognizedTechnologies());
        assertEquals(1, report.detectedTechnologies().stream()
                .filter(dependency -> dependency.equals("Dependency: org.testng:testng:7.10.0"))
                .count());
    }

    @Test
    void rejectsDoctypeInMavenBuildFile(@TempDir Path root) throws Exception {
        Path secret = Files.writeString(root.resolve("external.txt"), "must-not-be-read");
        Files.writeString(root.resolve("pom.xml"), """
            <!DOCTYPE project [<!ENTITY xxe SYSTEM "%s">]>
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.testng</groupId>
                        <artifactId>testng</artifactId>
                        <version>&xxe;</version>
                    </dependency>
                </dependencies>
            </project>
            """.formatted(secret.toUri()));

        String output = new Analyzer().analyze(root).render();

        assertTrue(output.contains("Build system: Maven (could not parse pom.xml)"));
        assertFalse(output.contains("must-not-be-read"));
    }

    @Test
    void missingPomIsNotDetected() throws Exception {
        Path temp = Files.createTempDirectory("selenium-test");

        var report = new Analyzer().analyze(temp);

        assertTrue(report.render().contains("No supported build descriptor found"));
    }
}
