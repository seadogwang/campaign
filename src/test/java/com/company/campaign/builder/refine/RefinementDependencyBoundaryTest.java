package com.company.campaign.builder.refine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 4 architecture scans: the T12 production code depends only on the accepted contracts and the accepted
 * T05/T11 boundaries. It reaches no SQL, JDBC, JPA, repository, persistence, Save/Publish/Runtime path, no array
 * index or JSON Patch addressing, no wall clock, random source, default locale/timezone, global context or
 * reflection, and it contains no Loyalty-specific business rule, field alias or test utterance.
 *
 * <p>One scan is behavioural rather than textual: the operation union is inspected reflectively, so "never
 * array indexes, JSON Pointer or JSON Patch paths" is proved about the actual contract records, not only about
 * their source text.
 */
class RefinementDependencyBoundaryTest {

    private static final Path REFINE_ROOT =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "refine");

    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "com.company.campaign.",
            "com.fasterxml.jackson.",
            "jakarta.validation.");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "java.net",
            "javax.script",
            "org.hibernate",
            "org.flywaydb",
            "com.company.campaign.query.sql",
            "com.company.campaign.query.fixture",
            "com.company.campaign.dev",
            "com.company.campaign.evaluation",
            "com.company.campaign.repository",
            "com.company.campaign.runtime",
            "com.company.campaign.builder.compiler",
            "com.company.campaign.builder.proposal",
            "com.company.campaign.CampaignApplication");

    private static final List<String> FORBIDDEN_TEXT = List.of(
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "OffsetDateTime.now", "ZonedDateTime.now",
            "Clock.system", "ZoneId.systemDefault", "Locale.getDefault", "UUID.randomUUID", "Math.random",
            "new Random(", "ThreadLocal", "System.getenv", "System.getProperty", "System.getProperties",
            "System.currentTimeMillis", "System.nanoTime", "System.out", "System.err", "System.lineSeparator",
            "Class.forName", "getDeclaredMethod", "getDeclaredField", "setAccessible", "Method.invoke",
            "ProcessBuilder", "Runtime.getRuntime", "DriverManager", "jdbc:", "DataSource", "ScriptEngine",
            "Thread.sleep", "FileWriter", "Files.write", "Executors", "ScheduledExecutorService");

    /** Symbols that belong to an array-index JSON Patch, JSON Pointer or JSONPath library. */
    private static final List<String> FORBIDDEN_PATCHING_TEXT = List.of(
            "JsonPointer", "jsonpointer", "JSON Pointer", "JSON-Pointer",
            "json-path", "jsonpath", "JsonPath", "org.json", "com.flipkart", "com.google.gson");

    /** Library imports that would implement positional patching; the refinement contract never uses one. */
    private static final List<String> FORBIDDEN_PATCHING_IMPORTS = List.of(
            "JsonPatch", "jsonpatch", "JsonPointer", "jsonpath", "JsonPath", "org.json", "flipkart");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//[^\\n]*");

    @Test
    void theProductionRefinementSourcesExist() {
        assertThat(fileNames()).contains(
                "SegmentRefinementPatch.java", "SegmentRefinementPatchCodec.java",
                "SegmentRefinementOperation.java", "RefinementOpKind.java", "RefinementField.java",
                "RefinementReplacement.java", "RefinementLimits.java", "SegmentRefinementErrorCodes.java",
                "SegmentRefinementException.java", "SegmentRefinementEditor.java",
                "SegmentRefinementAdapter.java", "SegmentRefinementAdapterErrorCodes.java",
                "SegmentRefinementEnvironment.java", "SegmentRefinementRequest.java",
                "SegmentRefinementResult.java", "SegmentRefinementState.java",
                "SegmentRefinementStateMachine.java", "SegmentRefinementTransition.java",
                "SegmentRefinementAuditRecord.java", "SegmentRefinementAuditSink.java",
                "SegmentRefinementSkillPreflight.java");
    }

    @Test
    void refinementImportsStayInsideTheAllowedBoundary() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
        assertThat(violations).as("imports outside the refinement boundary").isEmpty();
    }

    @Test
    void refinementReachesNoSqlPersistenceWebEvaluationOrRuntimePath() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
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
        assertThat(violations).as("forbidden imports in the refinement package").isEmpty();
    }

    @Test
    void refinementContainsNoClockRandomGlobalContextOrReflectionText() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(file);
            for (String forbidden : FORBIDDEN_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("forbidden clock/random/reflection text").isEmpty();
    }

    @Test
    void refinementUsesNoJsonPointerOrJsonPatchLibrary() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(file);
            for (String forbidden : FORBIDDEN_PATCHING_TEXT) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_PATCHING_IMPORTS) {
                    if (imported.contains(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("patching library symbols in the refinement package").isEmpty();
    }

    @Test
    void everyMentionOfJsonPatchIsARefusalRatherThanAUse() {
        // The only JSON-Patch knowledge in T12 is the classification and rejection of its verbs. The codec
        // carries the stable refusal; the wire projection only classifies the verb.
        String codec = code(REFINE_ROOT.resolve("SegmentRefinementPatchCodec.java"));
        String wire = code(REFINE_ROOT.resolve("SegmentRefinementWire.java"));

        assertThat(codec).contains("isJsonPatchVerb").contains("JSON_PATCH_REJECTED");
        assertThat(wire).contains("isJsonPatchVerb");
        assertThat(codec).doesNotContain(".apply(").doesNotContain("JsonPatch.class");
    }

    @Test
    void noRawJsonTreeOrOpenMapSurvivesIntoTheDomainTypes() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            String source = code(file);
            if (source.contains("JsonNode") && !name.equals("SegmentRefinementPatchCodec.java")
                    && !name.equals("SegmentRefinementWire.java")) {
                violations.add(name + " -> JsonNode");
            }
            if (source.contains("Map<String, Object>") && !name.equals("SegmentRefinementWire.java")) {
                violations.add(name + " -> Map<String, Object>");
            }
        }
        assertThat(violations).as("raw JSON retained outside the codec and its wire projection").isEmpty();
    }

    @Test
    void noOperationAddressesItsTargetByIndexPositionOrPath() {
        List<RecordComponent> components = new ArrayList<>();
        for (Class<?> variant : SegmentRefinementOperation.class.getPermittedSubclasses()) {
            assertThat(variant.isRecord()).isTrue();
            components.addAll(List.of(variant.getRecordComponents()));
        }
        assertThat(components).isNotEmpty();
        assertThat(components).allSatisfy(component -> {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name).doesNotMatch(".*(index|position|offset|path|pointer|ordinal).*");
            assertThat(component.getType()).isNotIn(int.class, Integer.class, long.class, Long.class);
        });
        assertThat(SegmentRefinementOperation.class.getPermittedSubclasses()).hasSize(4);
    }

    @Test
    void refinementContainsNoBusinessPhraseFieldAliasOrTestUtterance() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(file) + read(file);
            if (source.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF)) {
                violations.add(file + " -> CJK text");
            }
            for (String business : List.of(
                    "\"member.", "\"order.", "segment.rule-based", "message.template", "promotion.reference",
                    "flow.wait", "schedule.basic", "governance.limit", "GOLD", "SILVER", "CNY", "Shanghai")) {
                if (source.contains(business)) {
                    violations.add(file + " -> " + business);
                }
            }
        }
        assertThat(violations).as("business semantics must live in Metadata and fixtures, never in T12 code")
                .isEmpty();
    }

    @Test
    void refinementNeverSubstitutesItsOwnProjectionForTheAcceptedT05Codec() {
        // No refinement source re-derives a predicate, window or typed-value wire form: whoever projects one
        // must do it through the accepted SegmentDslCodec, so there is exactly one DSL serializer in the system.
        List<String> projecting = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(file);
            if (source.contains("writePredicateString") || source.contains("writeWindowString")
                    || source.contains("writeValueString")) {
                projecting.add(file.getFileName().toString());
                assertThat(source).contains("SegmentDslCodec");
            }
        }
        assertThat(projecting).containsExactly("SegmentRefinementWire.java");
    }

    @Test
    void refinementDependsOnNoSavePublishOrRuntimeOperation() {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(file);
            for (String operation : List.of(
                    "publishDraft", "applyDraft", "saveDraft", "PublishPreparation", "PublishedCampaign",
                    "CampaignCompiler", "CampaignPlanStore", "RuntimeVersion")) {
                if (source.contains(operation)) {
                    violations.add(file + " -> " + operation);
                }
            }
        }
        assertThat(violations).as("no Save/Publish/Runtime capability may enter T12").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(REFINE_ROOT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames() {
        return sources().stream().map(path -> path.getFileName().toString()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The source with block and line comments removed, so a scan never matches prose. */
    private static String code(Path file) {
        String withoutBlocks = BLOCK_COMMENT.matcher(read(file)).replaceAll(" ");
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll(" ");
    }
}