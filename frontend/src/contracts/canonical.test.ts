import { describe, expect, it } from 'vitest'
import { canonicalize, contentHashOfBytes, contentHashOfText, formatCanonicalNumber } from './canonical'
import { sha256Hex } from './sha256'
import { utf8Bytes } from './canonical'

function hex(text: string): string {
  return sha256Hex(utf8Bytes(text))
}

describe('sha256', () => {
  it('matches the published vectors', () => {
    expect(hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    )
  })
})

describe('canonical json', () => {
  it('sorts object keys by code unit and preserves array order', () => {
    expect(canonicalize({ b: 1, a: [{ z: 1, y: 2 }], C: 'x' })).toBe('{"C":"x","a":[{"y":2,"z":1}],"b":1}')
  })

  it('writes numbers without exponent or negative zero', () => {
    expect(formatCanonicalNumber(0)).toBe('0')
    expect(formatCanonicalNumber(-0)).toBe('0')
    expect(formatCanonicalNumber(1284)).toBe('1284')
    expect(formatCanonicalNumber(1e21)).toBe('1000000000000000000000')
    expect(formatCanonicalNumber(1e-7)).toBe('0.0000001')
  })

  it('produces exactly one form per value', () => {
    expect(canonicalize({ a: 1, b: 'x' })).toBe(canonicalize({ b: 'x', a: 1 }))
  })

  it('hashes canonical text and bytes identically', () => {
    const text = canonicalize({ a: 1 })
    expect(contentHashOfText(text)).toBe(contentHashOfBytes(utf8Bytes(text)))
    expect(contentHashOfText(text)).toMatch(/^sha256:[0-9a-f]{64}$/)
  })
})