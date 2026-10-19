/**
 * Deterministic tests for evidence redaction.
 */
import { describe, it, expect } from 'vitest'
import { redactSecrets } from '../evidence-collector'

describe('Evidence redaction', () => {
  it('redacts Bearer tokens', () => {
    const input = 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgN'
    const result = redactSecrets(input)
    expect(result).not.toContain('eyJhbGci')
    expect(result).toContain('[REDACTED]')
  })

  it('redacts API keys', () => {
    const input = 'api_key=sk-abc123def456 and api-key: secret-key-here'
    const result = redactSecrets(input)
    expect(result).not.toContain('sk-abc')
    expect(result).toContain('[REDACTED]')
  })

  it('redacts passwords', () => {
    const input = 'password=superSecret123'
    const result = redactSecrets(input)
    expect(result).not.toContain('superSecret123')
    expect(result).toContain('[REDACTED]')
  })

  it('redacts JDBC URLs with credentials', () => {
    const input = 'jdbc:postgresql://127.0.0.1:5432/db?user=admin&password=secret'
    const result = redactSecrets(input)
    expect(result).toContain('[REDACTED]')
  })

  it('redacts postgres connection strings', () => {
    const input = 'postgres://user:password@localhost:5432/db'
    const result = redactSecrets(input)
    expect(result).toContain('[REDACTED]')
  })

  it('passes through safe content unchanged', () => {
    const input = 'Campaign progress: 3 of 5 parts complete.'
    const result = redactSecrets(input)
    expect(result).toBe(input)
  })

  it('passes through null/empty strings', () => {
    expect(redactSecrets('')).toBe('')
  })

  it('redacts multiple secrets in one string', () => {
    const input =
      'Failed request: POST http://example.com/api with Authorization=Bearer token123 and api_key=abc'
    const result = redactSecrets(input)
    expect(result).not.toContain('token123')
    expect(result).not.toContain('abc')
    // Should have multiple [REDACTED] replacements
    const redactedCount = (result.match(/\[REDACTED\]/g) || []).length
    expect(redactedCount).toBeGreaterThanOrEqual(2)
  })

  it('never redacts conversation IDs or run IDs', () => {
    const input = 'conversationId: conv-abc-123, runId: run-def-456'
    const result = redactSecrets(input)
    expect(result).toContain('conv-abc-123')
    expect(result).toContain('run-def-456')
  })

  it('never redacts test scenario labels', () => {
    const input = 'Scenario C-01, SS-15 result: PROPOSAL_READY'
    const result = redactSecrets(input)
    expect(result).toBe(input)
  })
})