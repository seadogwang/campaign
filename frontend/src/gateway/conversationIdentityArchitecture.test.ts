// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * T34 requirement 9: the frontend never generates a conversation identity and never persists one.
 *
 * The identity is server-issued, kept in memory for the page session and bootstrapped from the one documented
 * create route. This scan reads the gateway, state and entry-point sources directly, so a future change that
 * reaches for browser storage, a client-generated UUID or a committed identity variable fails here.
 */

/** `frontend/src/gateway/` -> `frontend/src`. */
const SRC = fileURLToPath(new URL('..', import.meta.url))

function read(relative: string): string {
  return readFileSync(`${SRC}/${relative}`, 'utf8')
}

const IDENTITY_SOURCES = [
  'gateway/httpGateway.ts',
  'gateway/fixtureGateway.ts',
  'gateway/types.ts',
  'state/workspaceStore.tsx',
  'main.tsx',
]

/** Sources that must never generate a conversation identity UUID. */
const CONVERSATION_IDENTITY_SOURCES = [
  'gateway/httpGateway.ts',
  'gateway/fixtureGateway.ts',
  'gateway/types.ts',
  'main.tsx',
]

describe('server-issued conversation identity architecture', () => {
  it('never persists an identity in the browser', () => {
    for (const relative of IDENTITY_SOURCES) {
      const source = read(relative)
      expect(source, `${relative} must not use localStorage`).not.toContain('localStorage.')
      expect(source, `${relative} must not use sessionStorage`).not.toContain('sessionStorage.')
      expect(source, `${relative} must not read a cookie`).not.toContain('document.cookie')
    }
  })

  it('never generates a conversation identity UUID in gateway or entry-point sources', () => {
    for (const relative of CONVERSATION_IDENTITY_SOURCES) {
      const source = read(relative)
      expect(source, `${relative} must not generate a UUID`).not.toContain('crypto.randomUUID')
    }
  })

  it('removes the obsolete environment-provided identity variables', () => {
    for (const relative of IDENTITY_SOURCES) {
      const source = read(relative)
      expect(source, `${relative} must not read VITE_CONVERSATION_ID`).not.toContain('VITE_CONVERSATION_ID')
      expect(source, `${relative} must not read VITE_RUN_ID`).not.toContain('VITE_RUN_ID')
    }
  })

  it('bootstraps from the one documented server-issued conversation route', () => {
    const http = read('gateway/httpGateway.ts')
    expect(http).toContain('CONVERSATION_CREATE_ROUTE')
    expect(http).toContain("'/api/v1/campaign-agent/conversations'")
  })
})