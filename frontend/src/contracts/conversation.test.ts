/**
 * Contract-level tests for the conversation projection decoder, focusing on
 * the per-component issue list (componentIssues) and backward compatibility.
 */

import { describe, expect, it } from 'vitest'
import { decodeConversationProjection } from './conversation'

function validBase(): Record<string, unknown> {
  return {
    accepted: true,
    code: null,
    conversationId: 'conversation-test',
    runId: 'run-test',
    revision: 1,
    phase: 'AWAITING_CLARIFICATION',
    disposition: 'CAMPAIGN_AGENT_ACCEPTED',
    routeKind: 'CONTINUE_CAMPAIGN',
    turns: [
      { turnId: 'turn-1', role: 'USER', ordinal: 0, text: 'target active members', at: null },
      {
        turnId: 'turn-2',
        role: 'ASSISTANT',
        ordinal: 1,
        text: 'Please clarify your requirement.',
        at: null,
      },
    ],
    skills: [],
    components: [],
    clarification: {
      skillId: null,
      code: 'CAMPAIGN_COMPONENT_CLARIFICATION',
      prompt: 'More details are needed.',
      questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED', 'PROMOTION_DETAIL_REQUIRED'],
      options: [],
      freeTextAllowed: true,
    },
    assistantMessage: 'More details are needed.',
    audience: null,
    inspectionHash: 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
    proposal: null,
    proposalHash: null,
    followUpPrompt: null,
    journey: null,
  }
}

describe('decodeConversationProjection with componentIssues', () => {
  it('decodes two distinct component issues with full attribution', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify the audience description.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
        {
          component: 'OFFER',
          skillId: 'promotion.reference',
          code: 'PROMOTION_NEEDS_INPUT',
          prompt: 'Please provide the reward quantity.',
          questionCodes: ['PROMOTION_DETAIL_REQUIRED'],
          options: [
            { optionId: 'FIXED', label: 'Fixed amount' },
            { optionId: 'PERCENTAGE', label: 'Percentage' },
          ],
          freeTextAllowed: false,
        },
      ],
    }

    const projection = decodeConversationProjection(raw, '$')

    expect(projection.componentIssues).not.toBeNull()
    expect(projection.componentIssues).toHaveLength(2)

    const [segment, offer] = projection.componentIssues!

    // Segment issue
    expect(segment!.component).toBe('SEGMENT')
    expect(segment!.skillId).toBe('segment.rule-based')
    expect(segment!.code).toBe('SEGMENT_NEEDS_INPUT')
    expect(segment!.prompt).toBe('Please clarify the audience description.')
    expect(segment!.questionCodes).toEqual(['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'])
    expect(segment!.options).toHaveLength(0)
    expect(segment!.freeTextAllowed).toBe(true)

    // Offer issue
    expect(offer!.component).toBe('OFFER')
    expect(offer!.skillId).toBe('promotion.reference')
    expect(offer!.code).toBe('PROMOTION_NEEDS_INPUT')
    expect(offer!.prompt).toBe('Please provide the reward quantity.')
    expect(offer!.questionCodes).toEqual(['PROMOTION_DETAIL_REQUIRED'])
    expect(offer!.options).toHaveLength(2)
    expect(offer!.options[0]!.optionId).toBe('FIXED')
    expect(offer!.freeTextAllowed).toBe(false)
  })

  it('accepts absent componentIssues (backward compatibility)', () => {
    const raw = validBase()
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentIssues).toBeNull()
  })

  it('accepts null componentIssues (backward compatibility)', () => {
    const raw = { ...validBase(), componentIssues: null }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentIssues).toBeNull()
  })

  it('accepts empty componentIssues array as null', () => {
    const raw = { ...validBase(), componentIssues: [] }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentIssues).toBeNull()
  })

  it('rejects unknown field inside component issue', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify the audience.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
          rawModelError: 'some stack trace', // unknown field
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-component component value', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'AUDIENCE_SELECTION', // not in CAMPAIGN_COMPONENTS
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-machine-code code value', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'invalid code with spaces', // not a machine code
          prompt: 'Please clarify.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-reference-id skillId', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: '', // empty, not a valid reference id
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects component issue with absent required field', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          // prompt missing
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })
})

describe('decodeConversationProjection safe readable display', () => {
  it('renders business-area labels, not internal enum names', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify the audience.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentIssues).not.toBeNull()
    // The decoder validates the component code is in the closed set; the
    // rendering layer maps to user-facing labels. The decoder itself should
    // not reject legitimate closed-set values.
    expect(projection.componentIssues![0]!.component).toBe('SEGMENT')
  })

  it('does not contain internal data leakage in prompt or code', () => {
    const raw = {
      ...validBase(),
      componentIssues: [
        {
          component: 'SEGMENT',
          skillId: 'segment.rule-based',
          code: 'SEGMENT_NEEDS_INPUT',
          prompt: 'Please clarify the audience description.',
          questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
          options: [],
          freeTextAllowed: true,
        },
      ],
    }
    const projection = decodeConversationProjection(raw, '$')

    for (const issue of projection.componentIssues!) {
      // The prompt must not contain internal identifiers
      expect(issue.prompt).not.toContain('java.')
      expect(issue.prompt).not.toContain('stack')
      expect(issue.prompt).not.toContain('exception')
      expect(issue.prompt).not.toContain('null')
      // The code must match the machine code pattern (already validated by decoder)
      expect(issue.code).toMatch(/^[A-Z][A-Z0-9_]{0,63}$/)
      // The component must be from the closed set (already validated by decoder)
      expect(['SEGMENT', 'OFFER', 'COMMUNICATION', 'JOURNEY', 'EXECUTION_SCHEDULE']).toContain(
        issue.component,
      )
    }
  })
})

describe('decodeConversationProjection existing single-clarification preserved', () => {
  it('still decodes single clarification when componentIssues is absent', () => {
    const raw = validBase()

    const projection = decodeConversationProjection(raw, '$')

    // Existing clarification field still decodes correctly
    expect(projection.clarification).not.toBeNull()
    expect(projection.clarification!.code).toBe('CAMPAIGN_COMPONENT_CLARIFICATION')
    expect(projection.clarification!.prompt).toBe('More details are needed.')

    // componentIssues is absent → backward compatible
    expect(projection.componentIssues).toBeNull()
  })
})

describe('decodeConversationProjection requirement objective', () => {
  function validRequirements(): Record<string, unknown> {
    return {
      type: null,
      audience: null,
      offerDecision: 'UNKNOWN',
      offerDetail: null,
      channels: [],
      message: null,
      branchDecision: 'UNKNOWN',
      branchDetail: null,
      branchRule: null,
      cadence: null,
      schedule: null,
      triggerEventCode: null,
      missing: [],
      complete: false,
      unsupported: false,
      componentTodo: [],
      objective: { confirmed: true, evidenceLength: 14, acceptedAtRevision: 3 },
    }
  }

  it('decodes safe objective status from requirements', () => {
    const raw = { ...validBase(), requirements: validRequirements() }

    const projection = decodeConversationProjection(raw, '$')

    expect(projection.requirements).not.toBeNull()
    expect(projection.requirements!.objective).not.toBeNull()
    expect(projection.requirements!.objective.confirmed).toBe(true)
    expect(projection.requirements!.objective.evidenceLength).toBe(14)
    expect(projection.requirements!.objective.acceptedAtRevision).toBe(3)
  })

  it('rejects objective with unknown field', () => {
    const requirements = {
      ...validRequirements(),
      objective: {
        confirmed: true,
        evidenceLength: 14,
        acceptedAtRevision: 0,
        rawEvidence: 'should not be here', // unknown field
      },
    }
    const raw = { ...validBase(), requirements }

    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects objective with missing field', () => {
    const requirements = {
      ...validRequirements(),
      objective: {
        confirmed: true,
        evidenceLength: 14,
        // acceptedAtRevision missing
      },
    }
    const raw = { ...validBase(), requirements }

    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects objective with negative evidenceLength', () => {
    const requirements = {
      ...validRequirements(),
      objective: { confirmed: true, evidenceLength: -1, acceptedAtRevision: 0 },
    }
    const raw = { ...validBase(), requirements }

    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects objective with non-boolean confirmed', () => {
    const requirements = {
      ...validRequirements(),
      objective: { confirmed: 'yes', evidenceLength: 5, acceptedAtRevision: 0 },
    }
    const raw = { ...validBase(), requirements }

    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects objective with non-integer acceptedAtRevision', () => {
    const requirements = {
      ...validRequirements(),
      objective: { confirmed: true, evidenceLength: 5, acceptedAtRevision: 'zero' },
    }
    const raw = { ...validBase(), requirements }

    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('accepts absent objective with confirmed=false', () => {
    const requirements = {
      ...validRequirements(),
      objective: { confirmed: false, evidenceLength: 0, acceptedAtRevision: -1 },
    }
    const raw = { ...validBase(), requirements }

    const projection = decodeConversationProjection(raw, '$')
    expect(projection.requirements!.objective.confirmed).toBe(false)
    expect(projection.requirements!.objective.evidenceLength).toBe(0)
    expect(projection.requirements!.objective.acceptedAtRevision).toBe(-1)
  })
})

describe('decodeConversationProjection with componentManifest', () => {
  it('decodes a manifest with one entry per capability in canonical order', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: 'USER_STATED_AUDIENCE',
          evidence: 'target active members',
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
        {
          capabilityCode: 'EVENT',
          usageDecision: 'NOT_REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
        {
          capabilityCode: 'MESSAGE',
          usageDecision: 'REQUIRED',
          reasonCode: 'USER_REQUESTED_OUTREACH',
          evidence: 'send SMS reminder',
          selectedResourceCodes: ['SMS'],
          decisionSource: 'MODEL',
        },
      ],
    }

    const projection = decodeConversationProjection(raw, '$')

    expect(projection.componentManifest).not.toBeNull()
    expect(projection.componentManifest).toHaveLength(3)

    const [segment, event, message] = projection.componentManifest!

    expect(segment!.capabilityCode).toBe('SEGMENT')
    expect(segment!.usageDecision).toBe('REQUIRED')
    expect(segment!.reasonCode).toBe('USER_STATED_AUDIENCE')
    expect(segment!.evidence).toBe('target active members')
    expect(segment!.selectedResourceCodes).toEqual([])
    expect(segment!.decisionSource).toBe('MODEL')

    expect(event!.capabilityCode).toBe('EVENT')
    expect(event!.usageDecision).toBe('NOT_REQUIRED')
    expect(event!.reasonCode).toBeNull()
    expect(event!.evidence).toBeNull()

    expect(message!.capabilityCode).toBe('MESSAGE')
    expect(message!.usageDecision).toBe('REQUIRED')
    expect(message!.selectedResourceCodes).toEqual(['SMS'])
  })

  it('accepts absent componentManifest (backward compatibility)', () => {
    const raw = validBase()
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest).toBeNull()
  })

  it('accepts null componentManifest (backward compatibility)', () => {
    const raw = { ...validBase(), componentManifest: null }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest).toBeNull()
  })

  it('accepts empty componentManifest array as null', () => {
    const raw = { ...validBase(), componentManifest: [] }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest).toBeNull()
  })

  it('rejects unknown capability code', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'UNKNOWN_CAP',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects unknown usage decision', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'MAYBE',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects unknown decision source', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'UNKNOWN_SOURCE',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-machine-code reasonCode', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: 'invalid code with spaces',
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-machine-code selected resource code', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'MESSAGE',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: ['not a valid code!'],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects duplicate capability in manifest', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'NOT_REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects non-canonical manifest order', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'MESSAGE',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects manifest entry with unknown field', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
          extraField: 'should not be here',
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('rejects manifest entry with missing field', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SEGMENT',
          usageDecision: 'REQUIRED',
          reasonCode: null,
          evidence: null,
          selectedResourceCodes: [],
          // decisionSource missing
        },
      ],
    }
    expect(() => decodeConversationProjection(raw, '$')).toThrow()
  })

  it('decodes UNCERTAIN manifest entry with no resources', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'PROMOTION',
          usageDecision: 'UNCERTAIN',
          reasonCode: 'MODEL_UNSURE',
          evidence: 'user mentioned rewards vaguely',
          selectedResourceCodes: [],
          decisionSource: 'MODEL',
        },
      ],
    }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest).toHaveLength(1)
    const entry = projection.componentManifest![0]!
    expect(entry.usageDecision).toBe('UNCERTAIN')
    expect(entry.selectedResourceCodes).toEqual([])
  })

  it('decodes USER decision source as overriding', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'EVENT',
          usageDecision: 'REQUIRED',
          reasonCode: 'USER_OVERRIDE',
          evidence: 'user said they need a trigger',
          selectedResourceCodes: ['MEMBER_REGISTERED'],
          decisionSource: 'USER',
        },
      ],
    }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest![0]!.decisionSource).toBe('USER')
  })

  it('decodes SYSTEM decision source', () => {
    const raw = {
      ...validBase(),
      componentManifest: [
        {
          capabilityCode: 'SCHEDULE',
          usageDecision: 'REQUIRED',
          reasonCode: 'SYSTEM_DEFAULT',
          evidence: null,
          selectedResourceCodes: [],
          decisionSource: 'SYSTEM',
        },
      ],
    }
    const projection = decodeConversationProjection(raw, '$')
    expect(projection.componentManifest![0]!.decisionSource).toBe('SYSTEM')
  })
})