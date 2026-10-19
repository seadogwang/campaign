package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult;
import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line development harness for model -> formal Intent -> validation -> parameterized SQL. */
public final class AiToSqlCli {

    private static final CanonicalJson JSON = new CanonicalJson();

    private AiToSqlCli() {
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            DevelopmentModelConfiguration configuration = DevelopmentModelConfiguration.load();
            DevelopmentAnthropicModelPort modelPort = parsed.traceModelOutput()
                    ? new DevelopmentAnthropicModelPort(configuration, output -> System.err.println(
                            JSON.writeString(Map.of("rawModelOutput", output))))
                    : new DevelopmentAnthropicModelPort(configuration);
            DevelopmentAiToSqlService service = new DevelopmentAiToSqlService(
                    modelPort, CoreLoyaltyDevelopmentProfile.authorizationService());
            DevelopmentAiToSqlResult result;
            if (parsed.selections().isEmpty() && !parsed.interactive()) {
                result = service.invoke(CoreLoyaltyDevelopmentProfile.request(
                        parsed.requirement(), parsed.asOf()));
            } else {
                var selections = parsed.selections().iterator();
                BufferedReader console = new BufferedReader(new InputStreamReader(
                        System.in, StandardCharsets.UTF_8));
                result = service.invoke(
                        CoreLoyaltyDevelopmentProfile.request(parsed.requirement(), parsed.asOf()),
                        clarification -> {
                            String selectedDefinition;
                            if (selections.hasNext()) {
                                selectedDefinition = selections.next();
                            } else if (parsed.interactive()) {
                                System.err.println(JSON.writeString(Map.of(
                                        "clarification", clarification(clarification))));
                                System.err.print("Select definitionId: ");
                                System.err.flush();
                                try {
                                    selectedDefinition = console.readLine();
                                } catch (java.io.IOException failure) {
                                    throw new IllegalStateException("Unable to read clarification selection");
                                }
                            } else {
                                return null;
                            }
                            if (selectedDefinition == null || selectedDefinition.isBlank()) {
                                return null;
                            }
                            return clarification.options().stream()
                                    .filter(option -> option.definitionId().equals(selectedDefinition))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "Selected definition is not offered by the current clarification"))
                                    .optionId();
                        });
            }
            System.out.write(JSON.writeString(output(result, configuration.model())).getBytes(StandardCharsets.UTF_8));
            System.out.write('\n');
        } catch (RuntimeException | java.io.IOException failure) {
            System.err.println("error: AI-to-SQL development invocation failed");
            System.exit(1);
        }
    }

    private static Map<String, Object> output(DevelopmentAiToSqlResult result, String model) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evidenceClass", result.evidenceClass());
        output.put("profileVersion", CoreLoyaltyDevelopmentProfile.PROFILE_VERSION);
        output.put("promptContractVersion", SegmentIntentPromptContract.VERSION);
        output.put("promptContractHash", SegmentIntentPromptContract.HASH.value());
        output.put("metadataSnapshotId", CoreLoyaltyDevelopmentProfile.snapshot().snapshotId());
        output.put("metadataSnapshotVersion", CoreLoyaltyDevelopmentProfile.snapshot().snapshotVersion());
        output.put("metadataContentHash", CoreLoyaltyDevelopmentProfile.snapshot().contentHash().value());
        output.put("resolutionPolicyHash", CoreLoyaltyDevelopmentProfile.resolutionPolicy().contentHash().value());
        output.put("storageCatalogHash", CoreLoyaltyDevelopmentProfile.storageCatalog().contentHash().value());
        output.put("model", model);
        output.put("disposition", result.disposition().name());
        output.put("failureCode", result.failureCode());
        output.put("intentHash", value(result.intentHash()));
        output.put("clarification", clarification(result.clarification()));
        output.put("dslEditableHash", value(result.dslEditableHash()));
        output.put("canonicalAstHash", value(result.canonicalAstHash()));
        output.put("planHash", value(result.planHash()));
        output.put("sql", result.sql());
        output.put("parameters", result.parameters());
        output.put("compiledHash", value(result.compiledHash()));
        return output;
    }

    private static Map<String, Object> clarification(
            com.company.campaign.segment.resolution.Clarification clarification) {
        if (clarification == null) {
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resolutionId", clarification.resolutionId().value());
        output.put("questionCode", clarification.questionCode().name());
        output.put("selectionTarget", clarification.clauseId());
        output.put("questionText", clarification.questionText());
        output.put("options", clarification.options().stream().map(option -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("optionId", option.optionId());
            item.put("definitionId", option.definitionId());
            item.put("label", option.label());
            return item;
        }).toList());
        return output;
    }

    private static String value(com.company.campaign.shared.canonical.ContentHash hash) {
        return hash == null ? null : hash.value();
    }

    private record Arguments(
            Instant asOf,
            String requirement,
            List<String> selections,
            boolean traceModelOutput,
            boolean interactive) {

        private static Arguments parse(String[] args) {
            Instant asOf = null;
            String requirement = null;
            List<String> selections = new ArrayList<>();
            boolean traceModelOutput = false;
            boolean interactive = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--as-of".equals(argument) && index + 1 < args.length) {
                    try {
                        asOf = Instant.parse(args[++index]);
                    } catch (DateTimeParseException failure) {
                        throw new IllegalArgumentException("--as-of must be an ISO-8601 instant");
                    }
                } else if ("--requirement".equals(argument) && index + 1 < args.length) {
                    requirement = args[++index];
                } else if ("--select".equals(argument) && index + 1 < args.length) {
                    selections.add(args[++index]);
                } else if ("--trace-model-output".equals(argument)) {
                    traceModelOutput = true;
                } else if ("--interactive".equals(argument)) {
                    interactive = true;
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument");
                }
            }
            if (asOf == null || requirement == null || requirement.isBlank()) {
                requirement = System.getenv("SEGMENT_REQUIREMENT");
            }
            if (asOf == null || requirement == null || requirement.isBlank()) {
                throw new IllegalArgumentException(
                        "--as-of and either --requirement or SEGMENT_REQUIREMENT are required");
            }
            return new Arguments(
                    asOf, requirement, List.copyOf(selections), traceModelOutput, interactive);
        }
    }
}
