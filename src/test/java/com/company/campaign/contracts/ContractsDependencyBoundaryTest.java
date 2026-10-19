package com.company.campaign.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the T01 scope constraint that the base contracts stay free of persistence, web, security,
 * database and provider dependencies.
 *
 * <p>The check is an allowlist over imported packages rather than a denylist of known-bad ones: a package
 * added to {@code contracts} later has to be justified here, which is what keeps a later task from
 * quietly introducing JPA, Spring Data, SQL or a provider/LLM SDK into the layer every other contract
 * depends on.
 */
class ContractsDependencyBoundaryTest {

    private static final Path CONTRACTS_ROOT = Path.of("src", "main", "java", "com", "company", "campaign");

    private static final List<String> ALLOWED_IMPORT_PREFIXES = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.",
            "jakarta.validation.");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void contractsAndSharedImportNothingOutsideTheAllowedBoundary() {
        List<String> scannedFiles = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (String packageDirectory : List.of("contracts", "shared")) {
            Path root = CONTRACTS_ROOT.resolve(packageDirectory);
            assertThat(root).as("source root %s must exist", root).exists();

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scannedFiles.add(file.toString());
                    violations.addAll(violationsIn(file));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        assertThat(scannedFiles).as("scanned source files").hasSizeGreaterThan(10);
        assertThat(violations).as("imports outside the allowed boundary").isEmpty();
    }

    private static List<String> violationsIn(Path file) {
        List<String> violations = new ArrayList<>();
        String source = read(file);
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            String importedType = matcher.group(2);
            boolean allowed = ALLOWED_IMPORT_PREFIXES.stream().anyMatch(importedType::startsWith);
            if (!allowed) {
                violations.add(file + " -> " + importedType);
            }
        }
        return violations;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}