package com.company.campaign.builder.segment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * The T11A development-slice boundary: the slice composes accepted components but reaches no database, JDBC,
 * DataSource, Web endpoint, persistence, model Tool, review mutation, clock, random source, default locale,
 * global context or Gate/Save/Publish/Runtime path.
 */
class DevelopmentAiToSqlSliceBoundaryTest {

    private static final Path SLICE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "segment");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "java.sql", "javax.sql", "jakarta.persistence", "javax.persistence",
            "org.springframework.", "org.hibernate", "org.springframework.data",
            "java.net", "javax.script", "jdk.internal",
            "org.springframework.ai", "com.openai", "dev.langchain4j",
            "com.company.campaign.preview", "com.company.campaign.runtime",
            "com.company.campaign.evaluation", "com.company.campaign.repository",
            "com.company.campaign.builder.compiler", "com.company.campaign.builder.proposal",
            "com.company.campaign.query.fixture");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "DriverManager", "jdbc:", "DataSource", "Connection",
            "ScriptEngine", "Thread.sleep", "FileWriter", "Files.write", "DataSource.getConnection");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theSliceSourcesExist() {
        assertThat(fileNames()).contains(
                "SegmentIntentModelPort.java", "SegmentModelRequest.java", "SegmentModelRequestFactory.java",
                "SegmentModelOutputGuard.java", "ResolvedIntentDslCompiler.java", "DevelopmentAiToSqlService.java",
                "DevelopmentAiToSqlResult.java", "DevelopmentAiToSqlRequest.java");
    }

    @Test
    void sliceImportsStayInsideTheAcceptedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : sliceFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("forbidden imports in the development slice").isEmpty();
    }

    @Test
    void sliceContainsNoJdbcClockRandomEnvironmentOrGlobalContextText() {
        List<String> violations = new ArrayList<>();
        for (Path file : sliceFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden text in the development slice").isEmpty();
    }

    @Test
    void theModelPortIsInjectedWithNoProductionImplementation() {
        assertThat(SegmentIntentModelPort.class.isInterface()).isTrue();
        for (Method method : SegmentIntentModelPort.class.getDeclaredMethods()) {
            assertThat(Modifier.isAbstract(method.getModifiers()))
                    .as("%s must be abstract so no default implementation exists", method.getName())
                    .isTrue();
        }
        assertThat(implementationSources()).as("no production model port implementation").isEmpty();
    }

    @Test
    void theResultIsAlwaysDevelopmentOnlyAndNeverAProductionDecision() {
        assertThat(DevelopmentEvidence.DEVELOPMENT_ONLY).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(sourceText()).contains("DEVELOPMENT_ONLY");
        // No result component is a Gate status, an AI-ready flag or a PASSED/FAILED decision field.
        for (java.lang.reflect.RecordComponent component : DevelopmentAiToSqlResult.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name).doesNotContain("gate").doesNotContain("ready")
                    .doesNotContain("passed").doesNotContain("failed").doesNotContain("status");
        }
        assertThat(DevelopmentAiToSqlResult.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("evidenceClass", "disposition");
    }

    @Test
    void theRequestAndModelRequestCarryNoAuthorizationField() {
        // The single authorization boundary is the service constructor argument. Neither the host request nor
        // the model request has a field that could supply, override or weaken it.
        for (java.lang.reflect.RecordComponent component : DevelopmentAiToSqlRequest.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name).doesNotContain("authoriz").doesNotContain("permission").doesNotContain("credential");
            assertThat(com.company.campaign.security.authorization.AuthorizationService.class
                    .isAssignableFrom(component.getType())).isFalse();
        }
        for (java.lang.reflect.RecordComponent component : SegmentModelRequest.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name)
                    .doesNotContain("authoriz").doesNotContain("permission").doesNotContain("credential")
                    .doesNotContain("token").doesNotContain("tenant").doesNotContain("user").doesNotContain("scope");
            assertThat(com.company.campaign.security.authorization.AuthorizationService.class
                    .isAssignableFrom(component.getType())).isFalse();
        }
    }

    @Test
    void theAuthorizationServiceIsInjectedOnlyThroughTheServiceConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = DevelopmentAiToSqlService.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(java.util.Arrays.stream(constructors[0].getParameterTypes())
                .map(Class::getSimpleName))
                .containsExactly("SegmentIntentModelPort", "AuthorizationService");
        // The request type carries no authorization accessor at all, so no caller can substitute one.
        assertThat(java.util.Arrays.stream(DevelopmentAiToSqlRequest.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("authorizationService");
    }

    private static List<String> implementationSources() {
        List<String> implementers = new ArrayList<>();
        for (Path file : sliceFiles()) {
            if (read(file).contains("implements SegmentIntentModelPort")) {
                implementers.add(file.toString());
            }
        }
        return implementers;
    }

    private static List<Path> sliceFiles() {
        try (Stream<Path> walk = Files.walk(SLICE_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames() {
        return sliceFiles().stream().map(path -> path.getFileName().toString()).toList();
    }

    private static String sourceText() {
        StringBuilder text = new StringBuilder();
        for (Path file : sliceFiles()) {
            text.append(read(file));
        }
        return text.toString();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}