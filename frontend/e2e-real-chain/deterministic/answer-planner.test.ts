/**
 * Deterministic tests for the answer planner.
 */
import { describe, it, expect } from 'vitest'
import {
  planAnswer,
  planComponentAnswers,
  selectOptionFromOffered,
  normalizeOptionIdentity,
  matchOptionIdentity,
} from '../answer-planner'

describe('Answer planner', () => {
  describe('planAnswer', () => {
    it('returns known answer for COMM_PREFERRED_CHANNEL_SMS', () => {
      const answer = planAnswer('COMM_PREFERRED_CHANNEL_SMS')
      expect(answer.cannotAnswer).toBe(false)
      expect(answer.optionId).toBe('SMS')
    })

    it('returns known answer for COMM_PREFERRED_CHANNEL_WECHAT', () => {
      const answer = planAnswer('COMM_PREFERRED_CHANNEL_WECHAT')
      expect(answer.cannotAnswer).toBe(false)
      expect(answer.optionId).toBe('WECHAT')
    })

    it('returns known answer for trigger events', () => {
      for (const code of [
        'TRIGGER_EVENT_MEMBER_REGISTERED',
        'TRIGGER_EVENT_POINTS_ISSUED',
        'TRIGGER_EVENT_MEMBER_TIER_UPGRADED',
        'TRIGGER_EVENT_MEMBER_TIER_DOWNGRADED',
      ]) {
        const answer = planAnswer(code)
        expect(answer.cannotAnswer).toBe(false)
        expect(answer.optionId).toBeTruthy()
      }
    })

    it('returns cannot-answer for unknown codes', () => {
      const answer = planAnswer('UNKNOWN_RANDOM_CODE_XYZ')
      expect(answer.cannotAnswer).toBe(true)
      expect(answer.reason).toContain('Unknown')
    })

    it('matches by prefix for similar codes', () => {
      const answer = planAnswer('COMM_PREFERRED_CHANNEL_SMS_V2')
      expect(answer.cannotAnswer).toBe(false)
      expect(answer.optionId).toBe('SMS')
    })
  })

  describe('planComponentAnswers', () => {
    it('plans answers for multiple component issues', () => {
      const issues = [
        { component: 'COMMUNICATION', code: 'COMM_PREFERRED_CHANNEL_SMS', prompt: 'Which channel?' },
        { component: 'EXECUTION_SCHEDULE', code: 'EXECUTION_FREQUENCY_ONCE', prompt: 'How often?' },
      ]
      const answers = planComponentAnswers(issues)
      expect(answers.length).toBe(2)
      expect(answers[0]!.optionId).toBe('SMS')
      expect(answers[1]!.optionId).toBe('ONE_TIME')
    })

    it('detects duplicate codes as failures', () => {
      const issues = [
        { component: 'COMMUNICATION', code: 'SAME_CODE', prompt: 'Q1?' },
        { component: 'EXECUTION_SCHEDULE', code: 'SAME_CODE', prompt: 'Q2?' },
      ]
      const answers = planComponentAnswers(issues)
      // Both should be marked as failures due to duplication
      expect(answers[0]!.cannotAnswer).toBe(true)
      expect(answers[1]!.cannotAnswer).toBe(true)
      expect(answers[0]!.reason).toContain('DUPLICATE')
      expect(answers[1]!.reason).toContain('DUPLICATE')
    })
  })

  describe('selectOptionFromOffered', () => {
    it('prefers SMS option', () => {
      const options = [
        { optionId: 'WECHAT', label: 'WeChat' },
        { optionId: 'SMS', label: 'SMS' },
        { optionId: 'EMAIL', label: 'Email' },
      ]
      const selected = selectOptionFromOffered(options)
      expect(selected?.optionId).toBe('SMS')
    })

    it('prefers WeChat when scenario explicitly requires it', () => {
      const options = [
        { optionId: 'SMS', label: 'SMS' },
        { optionId: 'WECHAT', label: 'WeChat/微信' },
      ]
      const selected = selectOptionFromOffered(options, '需要使用微信触达会员')
      expect(selected?.optionId).toBe('WECHAT')
    })

    it('falls back to first non-WeChat option', () => {
      const options = [
        { optionId: 'WECHAT', label: 'WeChat' },
        { optionId: 'EMAIL', label: 'Email' },
      ]
      // Without SMS, prefer non-WeChat
      const selected = selectOptionFromOffered(options)
      expect(selected?.optionId).toBe('EMAIL')
    })

    it('returns null for empty options', () => {
      const selected = selectOptionFromOffered([])
      expect(selected).toBeNull()
    })

    it('returns first option when only one exists', () => {
      const options = [{ optionId: 'ONLY_ONE', label: 'Only Option' }]
      const selected = selectOptionFromOffered(options)
      expect(selected?.optionId).toBe('ONLY_ONE')
    })
  })

  // ── Option identity normalization ─────────────────────────────────────
  describe('normalizeOptionIdentity', () => {
    it('normalizes English option code to canonical form', () => {
      expect(normalizeOptionIdentity('SMS')).toBe('SMS')
      expect(normalizeOptionIdentity('sms')).toBe('SMS')
      expect(normalizeOptionIdentity('MEMBER_REGISTERED')).toBe('MEMBER_REGISTERED')
      expect(normalizeOptionIdentity('ONE_TIME')).toBe('ONE_TIME')
    })

    it('normalizes Chinese label to canonical English code', () => {
      expect(normalizeOptionIdentity('短信')).toBe('SMS')
      expect(normalizeOptionIdentity('微信')).toBe('WECHAT')
      expect(normalizeOptionIdentity('会员注册')).toBe('MEMBER_REGISTERED')
      expect(normalizeOptionIdentity('积分发放')).toBe('POINTS_ISSUED')
      expect(normalizeOptionIdentity('单次')).toBe('ONE_TIME')
      expect(normalizeOptionIdentity('周期性')).toBe('RECURRING')
    })

    it('returns original text for unrecognized options', () => {
      expect(normalizeOptionIdentity('未知选项')).toBe('未知选项')
      expect(normalizeOptionIdentity('')).toBe('')
    })

    // ── Trailing parenthetical stripping ───────────────────────────
    describe('trailing parenthetical stripping', () => {
      it('strips full-width parenthetical suffix before alias lookup', () => {
        expect(normalizeOptionIdentity('短信（本地测试）')).toBe('SMS')
        expect(normalizeOptionIdentity('微信（本地测试）')).toBe('WECHAT')
        expect(normalizeOptionIdentity('会员注册（本地测试）')).toBe('MEMBER_REGISTERED')
        expect(normalizeOptionIdentity('积分发放（本地测试）')).toBe('POINTS_ISSUED')
        expect(normalizeOptionIdentity('会员升级（本地测试）')).toBe('MEMBER_TIER_UPGRADED')
        expect(normalizeOptionIdentity('会员降级（本地测试）')).toBe('MEMBER_TIER_DOWNGRADED')
        expect(normalizeOptionIdentity('单次（一次性活动）')).toBe('ONE_TIME')
        expect(normalizeOptionIdentity('周期性（重复执行）')).toBe('RECURRING')
      })

      it('strips half-width parenthetical suffix before alias lookup', () => {
        expect(normalizeOptionIdentity('短信(local test)')).toBe('SMS')
        expect(normalizeOptionIdentity('微信(local)')).toBe('WECHAT')
        expect(normalizeOptionIdentity('会员注册(test)')).toBe('MEMBER_REGISTERED')
        expect(normalizeOptionIdentity('积分发放(dev)')).toBe('POINTS_ISSUED')
      })

      it('does NOT match labels that merely contain a known term without a parenthetical suffix', () => {
        expect(normalizeOptionIdentity('非短信')).toBe('非短信')
        expect(normalizeOptionIdentity('短信关闭')).toBe('短信关闭')
        expect(normalizeOptionIdentity('非微信')).toBe('非微信')
        expect(normalizeOptionIdentity('禁止短信')).toBe('禁止短信')
      })

      it('returns original when base label after strip is unknown', () => {
        expect(normalizeOptionIdentity('电子邮件（本地测试）')).toBe('电子邮件（本地测试）')
      })
    })
  })

  describe('matchOptionIdentity', () => {
    it('matches English optionId against Chinese DOM label', () => {
      expect(matchOptionIdentity('SMS', '短信')).toBe(true)
      expect(matchOptionIdentity('WECHAT', '微信')).toBe(true)
      expect(matchOptionIdentity('MEMBER_REGISTERED', '会员注册')).toBe(true)
      expect(matchOptionIdentity('POINTS_ISSUED', '积分发放')).toBe(true)
    })

    it('matches English optionId against English DOM label', () => {
      expect(matchOptionIdentity('SMS', 'SMS')).toBe(true)
      expect(matchOptionIdentity('SMS', 'sms')).toBe(true)
    })

    it('returns false for mismatched code and label', () => {
      expect(matchOptionIdentity('SMS', '微信')).toBe(false)
      expect(matchOptionIdentity('WECHAT', '短信')).toBe(false)
    })

    it('returns false when planned option is not among offered', () => {
      expect(matchOptionIdentity('SMS', '邮件')).toBe(false)
      expect(matchOptionIdentity('EMAIL', '短信')).toBe(false)
    })

    // ── Parenthetical label matching ──────────────────────────────
    it('matches when DOM label carries a trailing full-width parenthetical', () => {
      expect(matchOptionIdentity('SMS', '短信（本地测试）')).toBe(true)
      expect(matchOptionIdentity('WECHAT', '微信（本地测试）')).toBe(true)
      expect(matchOptionIdentity('MEMBER_REGISTERED', '会员注册（本地测试）')).toBe(true)
      expect(matchOptionIdentity('POINTS_ISSUED', '积分发放（本地测试）')).toBe(true)
      expect(matchOptionIdentity('MEMBER_TIER_UPGRADED', '会员升级（本地测试）')).toBe(true)
      expect(matchOptionIdentity('MEMBER_TIER_DOWNGRADED', '会员降级（本地测试）')).toBe(true)
    })

    it('does NOT match when DOM label merely contains a known term without parenthetical', () => {
      expect(matchOptionIdentity('SMS', '非短信')).toBe(false)
      expect(matchOptionIdentity('SMS', '短信关闭')).toBe(false)
      expect(matchOptionIdentity('SMS', '禁止短信')).toBe(false)
      expect(matchOptionIdentity('WECHAT', '非微信')).toBe(false)
    })

    it('does NOT match when DOM label has a parenthetical but the base is wrong', () => {
      expect(matchOptionIdentity('SMS', '微信（本地测试）')).toBe(false)
      expect(matchOptionIdentity('WECHAT', '短信（本地测试）')).toBe(false)
    })
  })
})