package com.seleniumboot.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MigratorTest {

    @TempDir
    Path temp;

    @Test
    void migratesCopyWithoutChangingOriginalAndCompilesFixture() throws Exception {
        Path project = temp.resolve("project");
        Path output = temp.resolve("migrated");
        write(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>org.seleniumhq.selenium</groupId><artifactId>selenium-java</artifactId><version>4.21.0</version></dependency>
                </dependencies></project>
                """);
        write(project.resolve("src/main/java/fixture/DriverFactory.java"), """
                package fixture;
                import org.openqa.selenium.WebDriver;
                public class DriverFactory {
                    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
                    public void legacyWait() throws InterruptedException { Thread.sleep(1); }
                }
                """);
        write(project.resolve("src/main/java/fixture/Setup.java"), """
                package fixture;
                import io.github.bonigarcia.wdm.WebDriverManager;
                public class Setup {
                    public void configure() {
                        // Keep this note and the surrounding layout.
                        WebDriverManager.chromedriver().setup();
                    }
                }
                """);
        write(project.resolve("src/main/java/fixture/Manual.java"), """
                package fixture;
                public class Manual {
                    public void waitForPage() throws InterruptedException {
                        Thread.sleep(1);
                    }
                }
                """);
            write(project.resolve("src/main/java/fixture/RetryAnalyzer.java"), """
                package fixture;
                import org.testng.IRetryAnalyzer;
                class RetryAnalyzer implements IRetryAnalyzer { }
                """);
            write(project.resolve("src/main/java/fixture/ScreenshotListener.java"), """
                package fixture;
                import org.testng.ITestListener;
                import org.openqa.selenium.TakesScreenshot;
                class ScreenshotListener implements ITestListener {
                    TakesScreenshot screenshot;
                }
                """);
        write(project.resolve("src/main/java/fixture/MixedFactory.java"), """
                package fixture;
                import org.openqa.selenium.WebDriver;
                class MixedDriverFactory {
                    ThreadLocal<WebDriver> driver;
                }
                class Utility { int value; }
                """);
        Map<Path, byte[]> original = snapshot(project);

        Migrator.Result result = new Migrator().migrate(project, output);

        assertEquals(original.keySet(), snapshot(project).keySet());
        original.forEach((path, bytes) -> assertArrayEquals(bytes, read(project.resolve(path))));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/DriverFactory.java")));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/RetryAnalyzer.java")));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/ScreenshotListener.java")));
        String mixed = Files.readString(output.resolve("src/main/java/fixture/MixedFactory.java"));
        assertFalse(mixed.contains("org.openqa.selenium.WebDriver"));
        assertTrue(mixed.contains("class Utility"));
        String setup = Files.readString(output.resolve("src/main/java/fixture/Setup.java"));
        assertFalse(setup.contains("WebDriverManager"));
        assertTrue(setup.contains("// Keep this note and the surrounding layout."));
        String pom = Files.readString(output.resolve("pom.xml"));
        assertTrue(pom.contains("<groupId>io.github.seleniumboot</groupId>"));
        assertTrue(pom.contains("<artifactId>selenium-boot</artifactId>"));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("retained the existing dependency version")));
        assertTrue(result.remaining().findings().stream().anyMatch(f -> f.ruleId().equals("MIG-014")));
        assertTrue(result.remaining().findings().stream().anyMatch(f -> f.ruleId().equals("MIG-014")
            && f.file().endsWith("DriverFactory.java")));
        assertTrue(result.applied().stream().anyMatch(change -> change.contains("DriverFactory")));

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK compiler is required to validate the fixture");
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        try (Stream<Path> sourceFiles = Files.walk(output.resolve("src/main/java"))) {
            var files = sourceFiles.filter(path -> path.toString().endsWith(".java")).toList();
            var compilerArguments = new java.util.ArrayList<String>();
            compilerArguments.add("-d");
            compilerArguments.add(classes.toString());
            files.stream().map(Path::toString).forEach(compilerArguments::add);
            assertEquals(0, compiler.run(null, null, null, compilerArguments.toArray(String[]::new)));
        }
    }

    @Test
    void refusesToWriteIntoSourceOrOverwriteExistingOutput() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        write(project.resolve("source.txt"), "unchanged");

        assertThrows(IllegalArgumentException.class, () -> new Migrator().migrate(project, project));
        assertThrows(IllegalArgumentException.class,
                () -> new Migrator().migrate(project, project.resolve("child")));
        Path existing = temp.resolve("existing");
        Files.createDirectory(existing);
        assertThrows(IllegalArgumentException.class, () -> new Migrator().migrate(project, existing));
        assertEquals("unchanged", Files.readString(project.resolve("source.txt")));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Map<Path, byte[]> snapshot(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).collect(Collectors.toMap(
                    root::relativize, MigratorTest::read));
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}