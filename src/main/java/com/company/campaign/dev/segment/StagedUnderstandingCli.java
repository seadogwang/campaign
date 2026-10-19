package com.company.campaign.dev.segment;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Command-line harness for the two-pass semantic understanding and clarification prototype. */
public final class StagedUnderstandingCli {

    private static final CanonicalJson JSON = new CanonicalJson();

    private StagedUnderstandingCli() {
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            DevelopmentModelConfiguration configuration = DevelopmentModelConfiguration.load();
            AtomicInteger stage = new AtomicInteger();
            DevelopmentAnthropicModelPort model = new DevelopmentAnthropicModelPort(
                    configuration,
                    output -> System.err.println(JSON.writeString(Map.of(
                            "stage", stage.incrementAndGet(), "rawModelOutput", output))));
            var result = new DevelopmentStagedUnderstandingService(model).analyze(
                    CoreLoyaltyDevelopmentProfile.request(parsed.requirement(), parsed.asOf()));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("model", configuration.model());
            output.put("promptContractVersion", result.promptContractVersion());
            output.put("promptContractHash", result.promptContractHash());
            output.put("discoveryDraft", result.discoveryDraft());
            output.put("refinedDraft", result.refinedDraft());
            output.put("clarificationBatch", result.clarificationBatch());
            System.out.write(JSON.writeString(output).getBytes(StandardCharsets.UTF_8));
            System.out.write('\n');
        } catch (RuntimeException | java.io.IOException failure) {
            System.err.println("error: staged semantic understanding invocation failed");
            System.exit(1);
        }
    }

    private record Arguments(String requirement, Instant asOf) {

        static Arguments parse(String[] args) {
            String requirement = System.getenv("SEGMENT_REQUIREMENT");
            Instant asOf = null;
            for (int index = 0; index < args.length; index++) {
                if ("--as-of".equals(args[index]) && index + 1 < args.length) {
                    try {
                        asOf = Instant.parse(args[++index]);
                    } catch (DateTimeParseException failure) {
                        throw new IllegalArgumentException("--as-of requires an ISO instant");
                    }
                } else {
                    throw new IllegalArgumentException("Unknown staged-understanding argument");
                }
            }
            if (requirement == null || requirement.isBlank() || asOf == null) {
                throw new IllegalArgumentException("SEGMENT_REQUIREMENT and --as-of are required");
            }
            return new Arguments(requirement, asOf);
        }
    }
}
