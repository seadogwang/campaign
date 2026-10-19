package com.company.campaign.builder.journey;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One explicit scenario fact of a Journey simulation request.
 *
 * <p>The shape is closed by the declared outcome: a known fact carries a value type and a value, an unknown
 * fact carries nothing else, and an error fact carries only a bounded reason code. The adapter converts this to
 * the accepted T20 scenario fact, which is the authority on whether the value really is the canonical text of
 * its declared kind — so a malformed fact fails closed there rather than being reinterpreted here.
 */
public record JourneyScenarioFact(
        String factCode,
        String outcome,
        String valueType,
        String value,
        String reasonCode) {

    private static final Pattern FACT_CODE =
            Pattern.compile("^[a-z][a-z0-9_]{0,30}(\\.[a-z][a-z0-9_]{0,30}){0,2}$");
    private static final Pattern REASON_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    /** The one closed outcome code a known fact declares. */
    public static final String KNOWN = "KNOWN";

    /** The one closed outcome code an explicitly unknown fact declares. */
    public static final String UNKNOWN = "UNKNOWN";

    /** The one closed outcome code an explicit fact error declares. */
    public static final String ERROR = "ERROR";

    public JourneyScenarioFact {
        if (factCode == null || !FACT_CODE.matcher(factCode).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        if (KNOWN.equals(outcome)) {
            if (valueType == null || value == null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
            }
        } else if (UNKNOWN.equals(outcome)) {
            if (valueType != null || value != null || reasonCode != null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
            }
        } else if (ERROR.equals(outcome)) {
            if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()
                    || valueType != null || value != null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
            }
        } else {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
    }

    /** Redacted diagnostics: the fact code and its outcome only, never the declared value. */
    @Override
    public String toString() {
        return "JourneyScenarioFact[factCode=" + factCode + ", outcome=" + outcome + ']';
    }
}