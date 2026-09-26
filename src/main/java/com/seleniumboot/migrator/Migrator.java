package com.seleniumboot.migrator;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Copies a project and applies only transformations that can be performed mechanically. */
public final class Migrator {

    public record Result(Path output, List<String> applied, List<String> notes, Report remaining) { }

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public Result migrate(Path project, Path output) throws IOException {
        Path source = project.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("not a directory: " + source);
        }
        if (destination.equals(source) || destination.startsWith(source)) {
            throw new IllegalArgumentException("output must not be the source directory or inside it: " + destination);
        }
        if (Files.exists(destination)) {
            throw new IllegalArgumentException("output already exists: " + destination);
        }
        Report sourceAnalysis = new Analyzer().analyze(source);

        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        copyProject(source, destination);

        List<String> applied = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        transformJava(destination, applied);
        if (!migratePom(destination.resolve("pom.xml"))) {
            notes.add("pom.xml: no org.seleniumhq.selenium:selenium-java dependency found to replace.");
        } else {
            applied.add("pom.xml: replaced org.seleniumhq.selenium:selenium-java with io.github.seleniumboot:selenium-boot");
            notes.add("pom.xml: retained the existing dependency version; confirm it matches a published selenium-boot release.");
        }
        Report outputAnalysis = new Analyzer().analyze(destination);
        return new Result(destination, List.copyOf(applied), List.copyOf(notes),
                includeSourceManualFindings(sourceAnalysis, outputAnalysis));
    }

    private static Report includeSourceManualFindings(Report source, Report output) {
        List<Finding> findings = new ArrayList<>(source.findings().stream()
                .filter(finding -> finding.status() == Finding.Status.MANUAL).toList());
        output.findings().stream().filter(finding -> !findings.contains(finding)).forEach(findings::add);
        Set<String> unparsable = new LinkedHashSet<>(source.unparsable());
        unparsable.addAll(output.unparsable());
        return new Report(output.filesFound(), output.filesParsed(), List.copyOf(unparsable), List.copyOf(findings));
    }

    private static void copyProject(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void transformJava(Path root, List<String> applied) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : sources) {
            var parsed = parser.parse(file);
            if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) continue;
            CompilationUnit unit = parsed.getResult().get();
            LexicalPreservingPrinter.setup(unit);
            boolean changed = removeAutoTypes(unit, applied, root.relativize(file).toString());
            changed |= removeWebDriverManagerSetup(unit, applied, root.relativize(file).toString());
            if (changed) changed |= removeUnusedExplicitImports(unit);
            if (changed && unit.getTypes().isEmpty()) {
                Files.delete(file);
            } else if (changed) {
                Files.writeString(file, LexicalPreservingPrinter.print(unit));
            }
        }
    }

    private static boolean removeAutoTypes(CompilationUnit unit, List<String> applied, String file) {
        boolean changed = false;
        for (var type : new ArrayList<>(unit.getTypes())) {
            if (!(type instanceof ClassOrInterfaceDeclaration declaration)) continue;
            boolean driverFactory = declaration.getNameAsString().endsWith("DriverFactory")
                    && declaration.findAll(FieldDeclaration.class).stream().anyMatch(Migrator::isThreadLocalDriver);
            boolean retry = declaration.getImplementedTypes().stream()
                    .anyMatch(implemented -> implemented.getNameAsString().equals("IRetryAnalyzer")
                            || implemented.getNameAsString().equals("IAnnotationTransformer"));
            boolean screenshotListener = declaration.getImplementedTypes().stream()
                    .anyMatch(implemented -> implemented.getNameAsString().equals("ITestListener"))
                    && (declaration.toString().contains("TakesScreenshot")
                    || declaration.toString().contains("getScreenshotAs"));
            if (driverFactory || retry || screenshotListener) {
                declaration.remove();
                applied.add(file + ": removed " + declaration.getNameAsString());
                changed = true;
            }
        }
        return changed;
    }

    private static boolean removeUnusedExplicitImports(CompilationUnit unit) {
        boolean changed = false;
        for (var declaration : new ArrayList<>(unit.getImports())) {
            if (declaration.isAsterisk() || declaration.isStatic()) continue;
            String simpleName = declaration.getName().getIdentifier();
            boolean used = unit.findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> type.getNameAsString().equals(simpleName))
                    || unit.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals(simpleName))
                    || unit.findAll(AnnotationExpr.class).stream()
                    .anyMatch(annotation -> annotation.getName().getIdentifier().equals(simpleName));
            if (!used) {
                declaration.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isThreadLocalDriver(FieldDeclaration field) {
        String type = field.getElementType().toString();
        return type.startsWith("ThreadLocal<") && type.contains("WebDriver");
    }

    private static boolean removeWebDriverManagerSetup(CompilationUnit unit, List<String> applied, String file) {
        boolean changed = false;
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("setup")
                    || call.getScope().map(scope -> !scope.toString().contains("WebDriverManager")).orElse(true)) {
                continue;
            }
            var statement = call.findAncestor(ExpressionStmt.class);
            if (statement.isPresent() && statement.get().getExpression() == call) {
                statement.get().remove();
                applied.add(file + ": removed WebDriverManager setup call");
                changed = true;
            }
        }
        if (changed) {
            unit.getImports().removeIf(importDeclaration ->
                    importDeclaration.getNameAsString().endsWith("WebDriverManager"));
        }
        return changed;
    }

    private static boolean migratePom(Path pom) throws IOException {
        if (!Files.isRegularFile(pom)) return false;
        try {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = builderFactory.newDocumentBuilder().parse(pom.toFile());
            NodeList dependencies = document.getElementsByTagName("dependency");
            boolean changed = false;
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String groupId = childText(dependency, "groupId");
                String artifactId = childText(dependency, "artifactId");
                if (groupId.equals("org.seleniumhq.selenium") && artifactId.equals("selenium-java")) {
                    setChildText(dependency, "groupId", "io.github.seleniumboot");
                    setChildText(dependency, "artifactId", "selenium-boot");
                    changed = true;
                }
            }
            if (!changed) return false;
            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(pom.toFile()));
            return true;
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException("could not update " + pom + ": " + exception.getMessage(), exception);
        }
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private static void setChildText(Element parent, String name, String value) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(name)) {
                child.setTextContent(value);
                return;
            }
        }
    }
}