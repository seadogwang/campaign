import { describe, expect, it } from 'vitest'
import { ACCEPTED_PREVIEW_ROUTE, CONVERSATION_CREATE_ROUTE, HttpCampaignWorkspaceGateway } from './httpGateway'
import { UI_ERROR_CODES, UiError } from '../contracts/errors'
import { PROPOSAL_SCHEMA_VERSION } from '../contracts/proposal'
import type { CampaignProposalPatch } from '../contracts/proposal'
import type { ContentHash } from '../contracts/primitives'

const CATALOG_HASH = 'sha256:1111111111111111111111111111111111111111111111111111111111111111' as ContentHash
const POLICY_HASH = 'sha256:2222222222222222222222222222222222222222222222222222222222222222' as ContentHash
const PLAN_HASH = 'sha256:3333333333333333333333333333333333333333333333333333333333333333' as ContentHash

interface FakeResponse {
  readonly status: number
  text(): Promise<string>
}

interface RecordedRequest {
  readonly url: string
  readonly method: string
  readonly headers: Record<string, string>
  readonly body: string | null
  readonly signal: AbortSignal | undefined
}

function fakeFetch(
  handler: (request: RecordedRequest) => FakeResponse | Promise<FakeResponse>,
  recorded: RecordedRequest[] = [],
): { readonly impl: typeof fetch; readonly recorded: RecordedRequest[] } {
  const impl = (async (input: string | URL | Request, init?: RequestInit): Promise<FakeResponse> => {
    const request: RecordedRequest = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: (init?.headers as Record<string, string> | undefined) ?? {},
      body: typeof init?.body === 'string' ? init.body : null,
      signal: init?.signal ?? undefined,
    }
    recorded.push(request)
    return await handler(request)
  }) as unknown as typeof fetch
  return { impl, recorded }
}

function json(body: unknown, status = 200): FakeResponse {
  return { status, text: async () => JSON.stringify(body) }
}

function success(data: unknown): FakeResponse {
  return json({ success: true, data, requestId: 'req-1' })
}

function failure(code: string): FakeResponse {
  return json(
    { success: false, error: { code, message: 'server prose is never rendered', details: {} }, requestId: 'req-1' },
    409,
  )
}

function gatewayWith(impl: typeof fetch, timeoutMs = 50): HttpCampaignWorkspaceGateway {
  return new HttpCampaignWorkspaceGateway({
    baseUrl: 'https://host.example',
    campaignId: 'campaign-1',
    timeoutMs,
    fetchImpl: impl,
  })
}

async function expectUiError(promise: Promise<unknown>, code: string): Promise<UiError> {
  try {
    await promise
  } catch (thrown) {
    expect(thrown).toBeInstanceOf(UiError)
    const error = thrown as UiError
    expect(error.code).toBe(code)
    return error
  }
  throw new Error('expected a bounded UiError')
}

const patch: CampaignProposalPatch = {
  schemaVersion: PROPOSAL_SCHEMA_VERSION,
  mode: 'EDIT',
  campaignId: 'campaign-1',
  baseRevisions: {
    campaignRevision: 3,
    catalogVersion: 'catalog-1',
    catalogHash: CATALOG_HASH,
    policyVersion: 'policy-1',
    policyHash: POLICY_HASH,
  },
  operations: [
    {
      op: 'UPSERT',
      capability: 'message.template',
      logicalId: null,
      clientRef: 'message-new',
      value: { referenceCode: 't-1' },
      before: null,
      after: null,
    },
  ],
  clientContext: { locale: 'en-GB' },
  journeyTopology: null,
}

describe('HTTP gateway envelope handling', () => {
  it('decodes a documented success envelope', async () => {
    const { impl } = fakeFetch(() => success([]))
    const result = await gatewayWith(impl).listExecutions('campaign-1', {})
    expect(result).toEqual([])
  })

  it('maps a server error code onto the bounded client vocabulary', async () => {
    const { impl } = fakeFetch(() => failure('LIFECYCLE_PREPARATION_EXPIRED'))
    const error = await expectUiError(
      gatewayWith(impl).listExecutions('campaign-1', {}),
      UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED,
    )
    // The server message is never rendered.
    expect(error.message).not.toContain('server prose')
  })

  it('refuses a body that is not an envelope', async () => {
    const { impl } = fakeFetch(() => json({ unexpected: true }))
    await expectUiError(
      gatewayWith(impl).listExecutions('campaign-1', {}),
      UI_ERROR_CODES.CONTRACT_FIELD_UNKNOWN,
    )
  })

  it('refuses an unreadable body without leaking it', async () => {
    const { impl } = fakeFetch(() => ({ status: 200, text: async () => '<html>gateway</html>' }))
    const error = await expectUiError(
      gatewayWith(impl).listExecutions('campaign-1', {}),
      UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
    )
    expect(JSON.stringify(error)).not.toContain('<html>')
  })
})

describe('HTTP gateway mutation safety', () => {
  it('sends an Idempotency-Key on every mutation', async () => {
    const { impl, recorded } = fakeFetch(() => failure('RESOURCE_NOT_FOUND'))
    await expectUiError(
      gatewayWith(impl).compileProposal(patch, { idempotencyKey: 'key-1' }),
      UI_ERROR_CODES.GATEWAY_NOT_FOUND,
    )
    expect(recorded[0]?.headers['Idempotency-Key']).toBe('key-1')
    expect(recorded[0]?.method).toBe('POST')
  })

  it('refuses a mutation that carries no idempotency key', async () => {
    const { impl, recorded } = fakeFetch(() => success({}))
    await expectUiError(
      gatewayWith(impl).compileProposal(patch, {}),
      UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
    )
    expect(recorded).toHaveLength(0)
  })

  it('sends only the Plan identity for Apply', async () => {
    const { impl, recorded } = fakeFetch(() =>
      success({
        campaignId: 'campaign-1',
        campaignRevision: 4,
        draftId: 'draft-1',
        draftRevision: 1,
        manifestHash: PLAN_HASH,
        draftHash: PLAN_HASH,
        appliedAt: '2026-10-08T09:00:00Z',
        clientRefBindings: [],
      }),
    )
    await gatewayWith(impl).applyPlan({ planId: 'plan-1', planHash: PLAN_HASH }, { idempotencyKey: 'apply-1' })
    const body: unknown = JSON.parse(recorded[0]?.body ?? '{}')
    expect(Object.keys(body as Record<string, unknown>).sort()).toEqual(['planHash', 'planId'])
    expect(recorded[0]?.url).toBe('https://host.example/api/v1/campaign-plans/plan-1/apply')
    expect(JSON.stringify(body)).not.toContain('changeSet')
    expect(JSON.stringify(body)).not.toContain('manifest')
  })
})

describe('HTTP gateway transport bounds', () => {
  it('aborts and reports a bounded timeout', async () => {
    const impl = (async (_input: unknown, init?: RequestInit): Promise<FakeResponse> => {
      return await new Promise<FakeResponse>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new Error('aborted')))
      })
    }) as unknown as typeof fetch
    await expectUiError(gatewayWith(impl, 10).listExecutions('campaign-1', {}), UI_ERROR_CODES.GATEWAY_TIMEOUT)
  })

  it('reports a bounded abort when the caller has already aborted', async () => {
    const controller = new AbortController()
    controller.abort()
    const { impl, recorded } = fakeFetch(() => success([]))
    await expectUiError(
      gatewayWith(impl).listExecutions('campaign-1', { signal: controller.signal }),
      UI_ERROR_CODES.GATEWAY_ABORTED,
    )
    expect(recorded).toHaveLength(0)
  })

  it('reports a bounded transport failure without a body', async () => {
    const impl = (async (): Promise<FakeResponse> => {
      throw new Error('ECONNRESET at /internal/host')
    }) as unknown as typeof fetch
    const error = await expectUiError(
      gatewayWith(impl).listExecutions('campaign-1', {}),
      UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED,
    )
    expect(JSON.stringify(error)).not.toContain('ECONNRESET')
    expect(JSON.stringify(error)).not.toContain('/internal/host')
  })
})

describe('HTTP gateway documented routes', () => {
  it('uses the documented /api/v1 preview route by default', async () => {
    const { impl, recorded } = fakeFetch(() => failure('SEGMENT_PREVIEW_VALIDATION_REJECTED'))
    await expectUiError(
      gatewayWith(impl).previewSegment({ planId: 'plan-1', planHash: PLAN_HASH, expiresAt: '2026-10-08T09:15:00Z' }, { idempotencyKey: 'k' }),
      UI_ERROR_CODES.GATEWAY_UNEXPECTED,
    )
    expect(recorded[0]?.url).toBe('https://host.example/api/v1/campaign-plans/plan-1/preview')
  })

  it('uses the accepted preview slice when configured', async () => {
    const { impl, recorded } = fakeFetch(() => failure('SEGMENT_PREVIEW_BACKEND_UNAVAILABLE'))
    const gateway = new HttpCampaignWorkspaceGateway({
      baseUrl: 'https://host.example',
      campaignId: 'campaign-1',
      acceptedPreviewSlice: true,
      fetchImpl: impl,
    })
    await expectUiError(
      gateway.previewSegment({ planId: 'plan-1', planHash: PLAN_HASH, expiresAt: '2026-10-08T09:15:00Z' }, { idempotencyKey: 'k' }),
      UI_ERROR_CODES.GATEWAY_UNEXPECTED,
    )
    expect(recorded[0]?.url).toBe(`https://host.example${ACCEPTED_PREVIEW_ROUTE}`)
  })

  it('declares HTTP mode without claiming production evidence', () => {
    const { impl } = fakeFetch(() => success([]))
    const gateway = gatewayWith(impl)
    expect(gateway.mode).toBe('HTTP')
    expect(gateway.environment.simulated).toBe(false)
    expect(gateway.environment.productionGateEvidence).toBe(false)
    expect(gateway.environment.statement).toContain('T25')
  })

  it('bootstraps a server-issued identity instead of reading the environment', async () => {
    const { impl, recorded } = fakeFetch(() =>
      success({ conversationId: 'conversation-issued-1', runId: 'run-issued-1', revision: 0 }),
    )
    const gateway = gatewayWith(impl)
    await expect(gateway.conversationIdentity()).resolves.toEqual({
      conversationId: 'conversation-issued-1',
      runId: 'run-issued-1',
    })
    expect(recorded).toHaveLength(1)
    expect(recorded[0]?.url).toBe(`https://host.example${CONVERSATION_CREATE_ROUTE}`)
    expect(recorded[0]?.method).toBe('POST')
    expect(recorded[0]?.body).toBe('{}')
    expect(recorded[0]?.headers['Idempotency-Key']).toBeTruthy()
  })

  it('shares one bootstrap across concurrent sends', async () => {
    const { impl, recorded } = fakeFetch(() =>
      success({ conversationId: 'conversation-issued-1', runId: 'run-issued-1', revision: 0 }),
    )
    const gateway = gatewayWith(impl)
    const [first, second, third] = await Promise.all([
      gateway.conversationIdentity(),
      gateway.conversationIdentity(),
      gateway.conversationIdentity(),
    ])
    expect(first).toEqual(second)
    expect(second).toEqual(third)
    expect(recorded).toHaveLength(1)
  })

  it('keeps a failed bootstrap retryable with the same idempotency key', async () => {
    let attempt = 0
    const { impl, recorded } = fakeFetch(() => {
      attempt += 1
      if (attempt === 1) {
        throw new Error('ECONNRESET')
      }
      return success({ conversationId: 'conversation-issued-1', runId: 'run-issued-1', revision: 0 })
    })
    const gateway = gatewayWith(impl)
    await expect(gateway.conversationIdentity()).rejects.toBeInstanceOf(UiError)
    await expect(gateway.conversationIdentity()).resolves.toMatchObject({
      conversationId: 'conversation-issued-1',
    })
    expect(recorded).toHaveLength(2)
    expect(recorded[0]?.headers['Idempotency-Key']).toBe(recorded[1]?.headers['Idempotency-Key'])
  })

  it('refuses a malformed issued identity', async () => {
    const { impl } = fakeFetch(() => success({ conversationId: 'has space', runId: 'run-1', revision: 0 }))
    await expect(gatewayWith(impl).conversationIdentity()).rejects.toMatchObject({
      code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
    })
  })

  it('refuses an issued identity that is not at revision zero', async () => {
    const { impl } = fakeFetch(() =>
      success({ conversationId: 'conversation-issued-1', runId: 'run-issued-1', revision: 3 }),
    )
    await expect(gatewayWith(impl).conversationIdentity()).rejects.toMatchObject({
      code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
    })
  })
})