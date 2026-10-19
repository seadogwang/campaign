package com.company.campaign.security;

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
 * Guards the T02 package boundary: the base {@code contracts}/{@code shared} layers must not reach into
 * security, Spring Security, persistence or a Provider SDK, and the new {@code security} core must stay
 * framework-independent.
 */
class SecurityDependencyBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "company", "campaign");

    private static final List<String> FORBIDDEN_IN_BASE_LAYERS = List.of(
            "com.company.campaign.security",
            "org.springframework.security",
            "jakarta.persistence",
            "javax.persistence",
            "org.springframework.data",
            "java.sql",
            "javax.sql",
            "org.hibernate",
            "com.zaxxer.hikari",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j",
            "org.springframework.web");

    private static final List<String> ALLOWED_IN_SECURITY = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.",
            "jakarta.validation.");

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    // Requirement 17: contracts/shared do not depend on security, Spring Security, persistence or Provider SDKs.
    @Test
    void baseLayersDoNotDependOnSecurityPersistenceOrProviders() {
        List<String> violations = new ArrayList<>();
        for (String packageDirectory : List.of("contracts", "shared")) {
            violations.addAll(forbiddenImports(SOURCE_ROOT.resolve(packageDirectory), FORBIDDEN_IN_BASE_LAYERS));
        }
        assertThat(violations).as("forbidden imports in contracts/shared").isEmpty();
    }

    // Requirement 17: the security core remains framework-independent.
    @Test
    void securityCoreIsFrameworkIndependent() {
        Path securityRoot = SOURCE_ROOT.resolve("security");
        assertThat(securityRoot).exists();

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path file : javaFiles(securityRoot)) {
            scanned++;
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String importedType = matcher.group(2);
                boolean allowed = ALLOWED_IN_SECURITY.stream().anyMatch(importedType::startsWith);
                if (!allowed) {
                    violations.add(file + " -> " + importedType);
                }
            }
        }

        assertThat(scanned).as("security source files").isGreaterThan(10);
        assertThat(violations).as("imports outside the security boundary").isEmpty();
    }

    private static List<String> forbiddenImports(Path root, List<String> forbiddenPrefixes) {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String importedType = matcher.group(2);
                for (String forbidden : forbiddenPrefixes) {
                    if (importedType.startsWith(forbidden)) {
                        violations.add(file + " -> " + importedType);
                    }
                }
            }
        }
        return violations;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}