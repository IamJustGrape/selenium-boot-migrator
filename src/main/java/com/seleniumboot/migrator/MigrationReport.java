package com.seleniumboot.migrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

final class MigrationReport {

    private MigrationReport() {
    }

    static Path write(Path directory, Report report) throws IOException {
        Path reportPath = directory.resolve("MIGRATION_REPORT.md");
        Files.writeString(reportPath, render(report));
        return reportPath;
    }

    static String render(Report report) {
        StringBuilder output = new StringBuilder("# Selenium Boot Migration Report\n\n");

        output.append("## Summary\n\n");
        output.append("- Files found: ").append(report.filesFound()).append('\n');
        output.append("- Files parsed: ").append(report.filesParsed()).append('\n');
        output.append("- Automatic findings: ")
                .append(report.count(Finding.Status.AUTO)).append('\n');
        output.append("- Manual findings: ")
                .append(report.count(Finding.Status.MANUAL)).append('\n');
        output.append("- Unparsable files: ")
                .append(report.unparsable().size()).append("\n\n");

        output.append("## Detected technologies\n\n");
        if (report.detectedTechnologies().isEmpty()) {
            output.append("- No supported build descriptor found\n");
        } else {
            report.detectedTechnologies()
                    .forEach(technology -> output.append("- ").append(technology).append('\n'));
        }

        output.append("\n## Recognized technologies\n\n");
        if (report.recognizedTechnologies().isEmpty()) {
            output.append("- No supported test technologies detected\n");
        } else {
            report.recognizedTechnologies()
                    .forEach(technology -> output.append("- ").append(technology).append('\n'));
        }

        output.append("\n## Rules applied per file\n\n");
        Map<String, StringBuilder> findingsByFile = new TreeMap<>();
        report.findings().forEach(finding -> findingsByFile
                .computeIfAbsent(finding.file(), file -> new StringBuilder())
                .append("- ")
                .append(finding.ruleId())
                .append(" at line ")
                .append(finding.line())
                .append(": ")
                .append(finding.detected())
                .append('\n'));

        if (findingsByFile.isEmpty()) {
            output.append("- No migration rules detected\n");
        } else {
            findingsByFile.forEach((file, findings) -> output
                    .append("### `")
                    .append(file)
                    .append("`\n\n")
                    .append(findings)
                    .append('\n'));
        }

        output.append("## Warnings\n\n");
        var manualFindings = report.findings().stream()
                .filter(finding -> finding.status() == Finding.Status.MANUAL)
                .toList();

        if (manualFindings.isEmpty() && report.unparsable().isEmpty()) {
            output.append("- None\n");
        } else {
            manualFindings.forEach(finding -> output
                    .append("- `")
                    .append(finding.file())
                    .append(':')
                    .append(finding.line())
                    .append("`: Detected: ")
                    .append(finding.detected())
                    .append(". Why not automatic: manual review is required. Suggested approach: ")
                    .append(finding.advice())
                    .append('\n'));

            report.unparsable().forEach(file -> output
                    .append("- `")
                    .append(file)
                    .append("` could not be parsed\n"));
        }

        output.append("\n## Manual actions\n\n");
        if (manualFindings.isEmpty()) {
            output.append("- None\n");
        } else {
            manualFindings.forEach(finding -> output
                    .append("- `")
                    .append(finding.file())
                    .append(':')
                    .append(finding.line())
                    .append("`: ")
                    .append(finding.advice())
                    .append('\n'));
        }

        output.append("\n## Estimated migration confidence\n\n");
        output.append(report.estimatedConfidence())
                .append("% estimated migration confidence. This is an estimate, not a guarantee.\n");

        return output.toString();
    }
}
