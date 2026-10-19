package com.company.campaign.builder.skill;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the versioned Segment Skill boundary.
 *
 * <p>It carries a {@link SegmentSkillErrorCodes} code and an immutable detail map that never contains the
 * rejected Skill text, a payload value or an exception message. It extends {@link ContractException}, so the
 * existing code/detail conventions apply.
 */
public final class SegmentSkillException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentSkillException(String code, String message) {
        super(code, message, Map.of());
    }

    public SegmentSkillException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Builds one bounded Skill rejection. */
    public static SegmentSkillException invalid(String code, String message) {
        return new SegmentSkillException(code, message);
    }
}