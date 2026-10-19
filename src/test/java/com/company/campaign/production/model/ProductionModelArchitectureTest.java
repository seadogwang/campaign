package com.company.campaign.production.model;

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
 * T35B: the production model package is architecture-scanned for the properties the task requires and the
 * implementation must keep.
 *
 * <p>It is a source scan and not a behavioural proof. It exists so a future change that reintroduces an ambient
 * clock, randomness, a direct process/environment read, a vendor SDK, a second model channel or a development
 * import fails loudly.
 */
class ProductionModelArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "production", "model");

    private static final Path ADAPTER = PACKAGE.resolve("ProductionAnthropicModelAdapter.java");

    private static final Path CONFIGURATION = PACKAGE.resolve("ProductionModelConfiguration.java");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.dev.",
            "com.anthropic",
            "com.openai",
            "org.springframework.web.client",
            "org.apache.http",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.databind.jsontype",
            "java.lang.reflect");

    private static final List<String> FORBIDDEN_ADAPTER_CONSTRUCTS = List.of(
            "Instant.now(",
            "LocalDateTime.now(",
            "ZonedDateTime.now(",
            "Clock.system",
            "System.currentTimeMillis",
            "System.nanoTime",
            "new Random",
            "Math.random",
            "UUID.randomUUID",
            "Thread.sleep",
            "System.getenv",
            "System.getProperties",
            "System.out",
            "System.err",
            "DriverManager",
            "Class.forName",
            "setAccessible");

    @Test
    void thePackageDependsOnNoVendorSdkNoReflectionAndNoDevelopmentType() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("no vendor SDK, reflection or development import").isEmpty();
    }

    @Test
    void theAdapterCoreHoldsNoAmbientClockRandomnessSleepOrProcessRead() {
        String source = read(ADAPTER);
        for (String forbidden : FORBIDDEN_ADAPTER_CONSTRUCTS) {
            assertThat(source).as("the adapter core must not contain " + forbidden).doesNotContain(forbidden);
        }
        assertThat(source)
                .contains("implements StructuredModelPort, SegmentIntentModelPort")
                .contains("HttpClient.Redirect.NEVER")
                .contains("readNBytes(")
                .contains("onMalformedInput(CodingErrorAction.REPORT)");
    }

    @Test
    void onlyTheConfigurationBeanPerformsTheBoundedWait() {
        assertThat(read(CONFIGURATION)).contains("Thread.sleep(millis)");
        for (Path file : javaFiles()) {
            if (!file.equals(CONFIGURATION)) {
                assertThat(read(file)).doesNotContain("Thread.sleep");
            }
        }
    }

    @Test
    void theConfigurationNamesAndQualifiesEveryBeanItPublishes() {
        String source = read(CONFIGURATION);
        assertThat(source)
                .contains("@Bean(name = MODEL_BEAN_NAME)")
                .contains("@Bean(name = BACKOFF_BEAN_NAME)")
                .contains("@Bean(name = EVIDENCE_BEAN_NAME)")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production\", name = \"enabled\", "
                        + "havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.model\", name = \"enabled\", "
                        + "havingValue = \"true\")")
                .doesNotContain("DriverManager");
    }

    @Test
    void noCommittedProductionModelConfigurationCarriesAValue() {
        String source = read(PACKAGE.resolve("ProductionModelProperties.java"));
        assertThat(source)
                .contains("private boolean enabled = false;")
                .contains("private String endpoint;")
                .contains("private String credential;")
                .contains("private String modelId;")
                .doesNotContain("https://");
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
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