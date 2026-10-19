/**
 * The one response envelope, mirroring `PreviewEnvelope` exactly:
 *   {"success":true,"data":{...},"requestId":"..."}
 *   {"success":false,"error":{"code":"...","message":"...","details":{}},"requestId":"..."}
 *
 * The unused branch is absent on the wire, so the decoder accepts exactly one
 * of `data`/`error` and rejects a body that carries both or neither.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import {
  requireBoolean,
  requireExactFields,
  requireObject,
  requireString,
  type JsonValue,
} from './validate'

export interface SuccessEnvelope<T> {
  readonly success: true
  readonly data: T
  readonly requestId: string
}

export interface ErrorEnvelopeBody {
  readonly code: string
  readonly message: string
  readonly details: Readonly<Record<string, JsonValue>>
}

export interface ErrorEnvelope {
  readonly success: false
  readonly error: ErrorEnvelopeBody
  readonly requestId: string
}

export type Envelope<T> = SuccessEnvelope<T> | ErrorEnvelope

const ENVELOPE_FIELDS = ['success', 'data', 'error', 'requestId'] as const
const ERROR_FIELDS = ['code', 'message', 'details'] as const

function decodeErrorBody(raw: unknown, path: string): ErrorEnvelopeBody {
  const body = requireObject(raw, path)
  requireExactFields(body, ERROR_FIELDS, path)
  const detailsRaw = body['details']
  let details: Record<string, JsonValue> = {}
  if (detailsRaw !== undefined && detailsRaw !== null) {
    details = requireObject(detailsRaw, `${path}.details`)
  }
  return {
    code: requireString(body['code'], `${path}.code`),
    message: requireString(body['message'], `${path}.message`),
    details,
  }
}

export function decodeEnvelope<T>(
  raw: unknown,
  decodeData: (value: unknown, path: string) => T,
): Envelope<T> {
  const body = requireObject(raw, '$')
  requireExactFields(body, ENVELOPE_FIELDS, '$')
  const success = requireBoolean(body['success'], '$.success')
  const requestId = requireString(body['requestId'], '$.requestId')
  if (success) {
    if (body['error'] !== undefined) {
      throw contractError(UI_ERROR_CODES.CONTRACT_ENVELOPE_INVALID, '$.error', 'unexpected-error')
    }
    return { success: true, data: decodeData(body['data'], '$.data'), requestId }
  }
  if (body['data'] !== undefined) {
    throw contractError(UI_ERROR_CODES.CONTRACT_ENVELOPE_INVALID, '$.data', 'unexpected-data')
  }
  return { success: false, error: decodeErrorBody(body['error'], '$.error'), requestId }
}

/** Build a request envelope would-be body; only used by tests and fixtures. */
export function successEnvelope<T>(data: T, requestId: string): SuccessEnvelope<T> {
  return { success: true, data, requestId }
}

export function errorEnvelope(error: ErrorEnvelopeBody, requestId: string): ErrorEnvelope {
  return { success: false, error, requestId }
}

/** Narrow a decoded envelope to its success branch or throw the bounded error. */
export function requireSuccess<T>(envelope: Envelope<T>, path: string): T {
  if (envelope.success) {
    return envelope.data
  }
  throw contractError(UI_ERROR_CODES.CONTRACT_ENVELOPE_INVALID, path, 'error-envelope')
}