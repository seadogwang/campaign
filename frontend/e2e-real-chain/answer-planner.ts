/**
 * Maps clarification question codes to test-provided answers.
 *
 * This is the ONLY place where test-input answers live. Every answer is the
 * evaluator's business input, never labelled as system output. When an answer
 * would materially contradict or invent a sensitive business fact, the planner
 * signals that the runner should stop honestly rather than forcing completion.
 */

/** A planned answer for a clarification question. */
export interface PlannedAnswer {
  /** The clarification question code this answers. */
  code: string
  /** The option ID to select, if this is a multiple-choice answer. */
  optionId?: string
  /** Free-text answer, if the clarification allows one. */
  freeText?: string
  /** When true, the runner must not force an answer and should record INCOMPLETE. */
  cannotAnswer: boolean
  /** Reason for why we cannot answer, recorded in evidence. */
  reason?: string
}

/**
 * Known answer profiles keyed by clarification question code.
 *
 * Only user-authorized configured catalog facts are present:
 *   - Channels: SMS, WeChat
 *   - Events: MEMBER_REGISTERED, POINTS_ISSUED, MEMBER_TIER_UPGRADED, MEMBER_TIER_DOWNGRADED
 *
 * The planner must not invent schedule, timezone, offer, branch, or any
 * fact beyond these catalog entries. An answer is valid only when the
 * scenario text explicitly supports it or the fact is a configured catalog entry.
 */
const KNOWN_ANSWERS: Record<string, PlannedAnswer> = {
  // === Configured channels (catalog facts) ===
  COMM_PREFERRED_CHANNEL_SMS: {
    code: 'COMM_PREFERRED_CHANNEL_SMS',
    optionId: 'SMS',
    cannotAnswer: false,
    reason: 'SMS is a configured channel in the catalog',
  },
  COMM_PREFERRED_CHANNEL_WECHAT: {
    code: 'COMM_PREFERRED_CHANNEL_WECHAT',
    optionId: 'WECHAT',
    cannotAnswer: false,
    reason: 'WeChat is a configured channel in the catalog',
  },

  // === Configured event triggers (catalog facts) ===
  TRIGGER_EVENT_MEMBER_REGISTERED: {
    code: 'TRIGGER_EVENT_MEMBER_REGISTERED',
    optionId: 'MEMBER_REGISTERED',
    cannotAnswer: false,
    reason: 'Available event trigger',
  },
  TRIGGER_EVENT_POINTS_ISSUED: {
    code: 'TRIGGER_EVENT_POINTS_ISSUED',
    optionId: 'POINTS_ISSUED',
    cannotAnswer: false,
    reason: 'Available event trigger',
  },
  TRIGGER_EVENT_MEMBER_TIER_UPGRADED: {
    code: 'TRIGGER_EVENT_MEMBER_TIER_UPGRADED',
    optionId: 'MEMBER_TIER_UPGRADED',
    cannotAnswer: false,
    reason: 'Available event trigger',
  },
  TRIGGER_EVENT_MEMBER_TIER_DOWNGRADED: {
    code: 'TRIGGER_EVENT_MEMBER_TIER_DOWNGRADED',
    optionId: 'MEMBER_TIER_DOWNGRADED',
    cannotAnswer: false,
    reason: 'Available event trigger',
  },
}

/**
 * Plans an answer for a given clarification code.
 *
 * Only exact code matches are honoured. Unknown or ambiguous codes must
 * return a cannot-answer signal with the code and component so the runner
 * records the missing fact honestly rather than forcing completion.
 */
export function planAnswer(code: string, componentCode?: string, prompt?: string): PlannedAnswer {
  // Exact match only — never fuzzy-match or guess
  if (KNOWN_ANSWERS[code]) {
    return { ...KNOWN_ANSWERS[code] }
  }

  // Unknown code — cannot safely answer; include all available context
  const ctx = [code, componentCode, prompt].filter(Boolean).join(' / ')
  return {
    code,
    cannotAnswer: true,
    reason: `Unknown clarification code: ${ctx}. Cannot invent a business fact.`,
  }
}

/**
 * Plans answers for a set of component-owned clarification issues.
 *
 * Verifies the UI labels the correct business area and that codes are deduplicated.
 */
export function planComponentAnswers(
  issues: Array<{ component: string; code: string; prompt: string }>,
): PlannedAnswer[] {
  const codes = issues.map((i) => i.code)
  const uniqueCodes = new Set(codes)
  const duplicated = codes.length !== uniqueCodes.size

  return issues.map((issue) => {
    const answer = planAnswer(issue.code, issue.component, issue.prompt)
    return {
      ...answer,
      code: issue.code,
      cannotAnswer: answer.cannotAnswer || (duplicated && codes.filter((c) => c === issue.code).length > 1),
      reason:
        duplicated && codes.filter((c) => c === issue.code).length > 1
          ? 'DUPLICATE_CODE: Repeated identical code set is a failure.'
          : answer.reason,
    }
  })
}

/**
 * Selects an offered option only when the scenario text explicitly supports it.
 *
 * Returns null for unknown or ambiguous selections — the caller must
 * handle the cannot-answer case rather than arbitrarily picking the first option.
 */
export function selectOptionFromOffered(
  options: Array<{ optionId: string; label: string }>,
  scenarioDescription?: string,
): { optionId: string; label: string } | null {
  if (options.length === 0) return null

  // Only act when the scenario explicitly names an option
  if (scenarioDescription) {
    const lower = scenarioDescription.toLowerCase()

    // Explicit WeChat requirement
    if (lower.includes('wechat') || scenarioDescription.includes('微信')) {
      const wechatOption = options.find(
        (o) =>
          o.label.toLowerCase().includes('wechat') ||
          o.optionId.toUpperCase().includes('WECHAT') ||
          o.label.includes('微信'),
      )
      if (wechatOption) return wechatOption
    }

    // Explicit SMS requirement
    if (lower.includes('sms') || lower.includes('short message')) {
      const smsOption = options.find(
        (o) => o.label.toLowerCase().includes('sms') || o.optionId.toUpperCase().includes('SMS'),
      )
      if (smsOption) return smsOption
    }
  }

  // No explicit scenario support — cannot select arbitrarily
  return null
}

// ── Option identity normalization ──────────────────────────────────────────

/**
 * Bidirectional alias map: any key (English lower-case code or Chinese label)
 * maps to the canonical English identifier.
 *
 * Used to match DOM-displayed Chinese labels (短信, 微信) against server-supplied
 * optionIds (SMS, WECHAT) so the runner can find the correct DOM button.
 */
const OPTION_ALIASES: Readonly<Record<string, string>> = {
  // Channels
  'sms': 'SMS',
  '短信': 'SMS',
  'wechat': 'WECHAT',
  '微信': 'WECHAT',
  // Events
  'member_registered': 'MEMBER_REGISTERED',
  '会员注册': 'MEMBER_REGISTERED',
  'points_issued': 'POINTS_ISSUED',
  '积分发放': 'POINTS_ISSUED',
  'member_tier_upgraded': 'MEMBER_TIER_UPGRADED',
  '会员升级': 'MEMBER_TIER_UPGRADED',
  'member_tier_downgraded': 'MEMBER_TIER_DOWNGRADED',
  '会员降级': 'MEMBER_TIER_DOWNGRADED',
  // Cadence
  'one_time': 'ONE_TIME',
  '单次': 'ONE_TIME',
  'recurring': 'RECURRING',
  '周期性': 'RECURRING',
  // Timezone
  'asia/shanghai': 'Asia/Shanghai',
  'shanghai': 'Asia/Shanghai',
  '上海': 'Asia/Shanghai',
}

/**
 * Strips a trailing parenthetical note — an explanatory suffix enclosed in
 * full-width （）or half-width () — from the end of a label.
 *
 * Only the rightmost parenthetical at the very end is removed. The base label
 * before the parenthetical is preserved intact. Mid-span and nested brackets
 * are left in place.
 *
 * This is the ONLY normalisation applied before alias lookup: stripping a
 * trailing parenthetical makes {@code "短信（本地测试）"} equivalent to
 * {@code "短信"} while {@code "非短信"} and {@code "短信关闭"} are untouched
 * — they carry no parenthetical suffix and will not match the SMS alias.
 */
function stripTrailingParenthetical(text: string): string {
  // Full-width parentheses  （ … ）
  let result = text.replace(/\s*（[^）]*）\s*$/, '')
  // Half-width parentheses  ( … )
  result = result.replace(/\s*\([^)]*\)\s*$/, '')
  return result.trim()
}

/**
 * Normalizes any option identity text (English code, Chinese label, or
 * mixed-case variant) to its canonical English identifier.
 *
 * Trailing parenthetical notes (e.g. "短信（本地测试）") are safely stripped
 * so the base label can match a known alias. Text that carries no trailing
 * parenthetical but merely *contains* a known term ("非短信", "短信关闭")
 * is never matched — only exact alias lookup after parenthetical removal.
 */
export function normalizeOptionIdentity(text: string): string {
  const trimmed = text.trim()
  if (!trimmed) return trimmed

  const lower = trimmed.toLowerCase()
  const canonical = OPTION_ALIASES[lower]
  if (canonical) return canonical

  for (const c of Object.values(OPTION_ALIASES)) {
    if (c.toLowerCase() === lower) return c
  }

  // Strip trailing parenthetical and retry with the base label
  const base = stripTrailingParenthetical(trimmed)
  if (base !== trimmed) {
    const baseLower = base.toLowerCase()
    const baseCanonical = OPTION_ALIASES[baseLower]
    if (baseCanonical) return baseCanonical

    for (const c of Object.values(OPTION_ALIASES)) {
      if (c.toLowerCase() === baseLower) return c
    }
  }

  return trimmed
}

/**
 * Checks whether a DOM-displayed text matches a planned optionId after
 * normalizing both through the alias map.
 */
export function matchOptionIdentity(optionId: string, domText: string): boolean {
  const normalizedDom = normalizeOptionIdentity(domText)
  const normalizedOption = normalizeOptionIdentity(optionId)
  return normalizedDom.toUpperCase() === normalizedOption.toUpperCase()
}