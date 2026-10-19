package com.company.campaign.preview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 16-18: the Preview core, adapter and HTTP surfaces introduce no JDBC/JPA/Flyway/database
 * driver, SQL execution, mutation, Save/Publish/Runtime/provider/model/T10+ dependency, wall clock, default
 * timezone/locale, randomness, ThreadLocal/global context or body logging; there is no permissive/default
 * Preview dependency; and the T06 corpus and human-review assets are untouched.
 */
class PreviewDependencyBoundaryTest {

    private static final Path PREVIEW_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "preview");
    private static final Path CORE_ROOT = PREVIEW_ROOT;
    private static final Path ADAPTER_ROOT = PREVIEW_ROOT.resolve("adapter");
    private static final Path API_ROOT = PREVIEW_ROOT.resolve("api");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "jdbc:", "DriverManager", "DataSource", "PreparedStatement", "EntityManager", "JpaRepository",
            "org.flywaydb", "HikariPool", "ThreadLocal", "SecurityContextHolder", "RequestContextHolder",
            "System.out", "System.err", "java.util.logging", "org.slf4j",
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "ZonedDateTime.now", "OffsetDateTime.now",
            "System.currentTimeMillis", "System.nanoTime", "Math.random", "new Random", "UUID.randomUUID",
            "TimeZone.getDefault", "Locale.getDefault", "ZoneId.systemDefault", "Clock.system");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "java.sql", "javax.sql", "org.hibernate", "org.springframework.data", "org.flywaydb", "com.zaxxer",
            "com.company.campaign.query.sql", "com.company.campaign.runtime", "com.company.campaign.builder",
            "com.company.campaign.message", "com.company.campaign.reward", "com.company.campaign.schedule",
            "com.company.campaign.audience", "com.company.campaign.journey", "com.company.campaign.campaign",
            "com.company.campaign.builder", "com.openai", "dev.langchain4j");

    private static final List<String> CORE_FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.springframework.", "jakarta.", "com.company.campaign.preview.api",
            "com.company.campaign.preview.adapter", "com.company.campaign.query.fixture");

    private static final List<String> API_FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.company.campaign.query.fixture", "com.company.campaign.query.sql");

    private static final List<String> ADAPTER_ALLOWED_IMPORT_PREFIXES = List.of(
            "java.", "com.company.campaign.");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    // ------------------------------------------------------------------ source scans

    @Test
    void thePreviewBoundarySourcesExist() {
        assertThat(flatNames(CORE_ROOT)).contains(
                "SegmentPreviewService.java", "PreviewRequestCodec.java", "PreviewRequest.java",
                "PreviewPresentation.java", "PreviewPolicy.java", "PreviewLimits.java", "PreviewErrorCodes.java",
                "PreviewException.java", "PreviewResponse.java", "PreviewExecutionContext.java",
                "PreviewConditionStatistic.java", "PreviewExecutionBackend.java", "PreviewBackendIdentity.java",
                "PreviewBackendResult.java", "PreviewBackendException.java", "PreviewSampleHasher.java");
        assertThat(flatNames(ADAPTER_ROOT)).contains("FixturePreviewExecutionBackend.java");
        assertThat(flatNames(API_ROOT)).contains(
                "SegmentPreviewController.java", "PreviewEnvelope.java", "PreviewErrorBody.java",
                "PreviewRequestId.java", "PreviewHttpResponses.java");
    }

    @Test
    void noPreviewSourceDependsOnJdbcJpaFlywaySqlExecutionMutationProviderModelOrT10Plus() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("forbidden preview imports").isEmpty();
    }

    @Test
    void thePreviewCoreDoesNotDependOnSpringHttpOrTheFixtureAdapter() {
        List<String> violations = new ArrayList<>();
        for (Path file : filesIn(CORE_ROOT)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : CORE_FORBIDDEN_IMPORT_PREFIXES) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("the Preview core must stay framework- and fixture-free").isEmpty();
    }

    @Test
    void theApiSurfaceDoesNotDependOnSqlOrFixtureExecution() {
        List<String> violations = new ArrayList<>();
        for (Path file : filesIn(API_ROOT)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : API_FORBIDDEN_IMPORT_PREFIXES) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("the Preview HTTP adapter must not execute SQL or fixtures").isEmpty();
    }

    @Test
    void theFixtureAdapterOnlyDependsOnAcceptedFixtureContractsAndThePreviewPort() {
        List<String> violations = new ArrayList<>();
        for (Path file : filesIn(ADAPTER_ROOT)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (imported.startsWith("static ")) {
                    continue;
                }
                if (ADAPTER_ALLOWED_IMPORT_PREFIXES.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("the fixture adapter must only use accepted contracts").isEmpty();
    }

    @Test
    void noPreviewSourceReadsAClockDefaultLocaleOrRandomnessOrLogsBodies() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("wall clock, ambient default, randomness, global context or body logging").isEmpty();
    }

    @Test
    void thePreviewSurfacesDoNotTouchTheT06CorpusOrReviewAssets() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String marker : List.of("corpus-v1", "review-ledger", "reviewStatus", "reviewedBy",
                    "evaluation/segment/corpus")) {
                if (source.contains(marker)) {
                    violations.add(file + " -> " + marker);
                }
            }
        }
        assertThat(violations).as("T06 corpus/review assets must remain untouched").isEmpty();
    }

    // ------------------------------------------------------------------ reflection

    @Test
    void thePreviewServiceHasNoPermissiveOrDefaultConstructor() {
        Class<SegmentPreviewService> type = SegmentPreviewService.class;
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertThat(constructors).isNotEmpty();
        for (Constructor<?> constructor : constructors) {
            assertThat(constructor.getParameterCount())
                    .as("every Preview service constructor must be fully injected")
                    .isEqualTo(10);
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            }
        }
    }

    @Test
    void thePreviewReadBoundariesAreImmutableAndNonForgeable() {
        assertThat(Modifier.isFinal(PreviewBackendResult.class.getModifiers())).isTrue();
        for (Constructor<?> constructor : PreviewBackendResult.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).as("only publish() may build a result").isTrue();
        }
        assertThat(Arrays.stream(PreviewBackendResult.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("publish");
        assertThat(PreviewResponse.class.isRecord()).isTrue();
        assertThat(PreviewRequest.class.isRecord()).isTrue();
        assertThat(PreviewExecutionContext.class.isRecord()).isTrue();
        assertThat(PreviewConditionStatistic.class.isRecord()).isTrue();
        assertThat(PreviewPresentation.class.isRecord()).isTrue();
    }

    @Test
    void theExecutionPortExposesNoMutationOrSelectorOperation() {
        List<String> methods = Arrays.stream(PreviewExecutionBackend.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        assertThat(methods).containsExactlyInAnyOrder("identity", "execute");
        assertThat(methods).noneMatch(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("save") || lower.contains("apply") || lower.contains("publish")
                    || lower.contains("mutate") || lower.contains("delete") || lower.contains("update")
                    || lower.contains("select") || lower.contains("transaction");
        });
    }

    @Test
    void theSampleHasherHasNoPermissiveConstructorAndNoKeyAccessor() {
        assertThat(PreviewSampleHasher.class.getDeclaredConstructors()).hasSize(1);
        assertThat(PreviewSampleHasher.class.getDeclaredConstructors()[0].getParameterCount()).isEqualTo(1);
        assertThat(Arrays.stream(PreviewSampleHasher.class.getMethods())
                .noneMatch(method -> method.getReturnType() == byte[].class))
                .as("the key must never be exposed")
                .isTrue();
        assertThat(Arrays.stream(PreviewSampleHasher.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("key"));
    }

    @Test
    void theControllerIsConditionallyRegisteredAndNeverRestController() {
        assertThat(SegmentPreviewControllerAnnotations.isAnnotatedWithController()).isTrue();
        assertThat(SegmentPreviewControllerAnnotations.isNotAnnotatedWithRestController()).isTrue();
        assertThat(SegmentPreviewControllerAnnotations.isConditionalOnThePreviewService()).isTrue();
    }

    private static final class SegmentPreviewControllerAnnotations {

        private static Class<?> controller() {
            try {
                return Class.forName("com.company.campaign.preview.api.SegmentPreviewController");
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(exception);
            }
        }

        static boolean isAnnotatedWithController() {
            return org.springframework.core.annotation.AnnotatedElementUtils
                    .hasAnnotation(controller(), org.springframework.stereotype.Controller.class);
        }

        static boolean isNotAnnotatedWithRestController() {
            return !org.springframework.core.annotation.AnnotatedElementUtils
                    .hasAnnotation(controller(), org.springframework.web.bind.annotation.RestController.class);
        }

        static boolean isConditionalOnThePreviewService() {
            return org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                    controller(),
                    org.springframework.boot.autoconfigure.condition.ConditionalOnBean.class);
        }
    }

    // ------------------------------------------------------------------ file helpers

    private static List<Path> javaFiles() {
        return walk(PREVIEW_ROOT);
    }

    /** Only the files declared directly in one package directory, never a subpackage. */
    private static List<Path> filesIn(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<Path> walk(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> flatNames(Path root) {
        return walk(root).stream().map(path -> path.getFileName().toString()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}