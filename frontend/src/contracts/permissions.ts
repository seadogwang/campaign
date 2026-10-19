/**
 * The closed permission vocabulary. Wire names are exact and case-sensitive;
 * a second casing of `campaign.read` is not a distinct authority.
 */

import { UI_ERROR_CODES, contractError } from './errors'

export const CAMPAIGN_PERMISSIONS = [
  'campaign.read',
  'campaign.edit',
  'audience.preview',
  'campaign.publish',
  'campaign.operate',
  'metadata.manage',
  'segment.evaluate',
] as const
export type CampaignPermission = (typeof CAMPAIGN_PERMISSIONS)[number]

export const ALL_PERMISSIONS: ReadonlySet<CampaignPermission> = new Set(CAMPAIGN_PERMISSIONS)

export function isCampaignPermission(value: unknown): value is CampaignPermission {
  return typeof value === 'string' && (CAMPAIGN_PERMISSIONS as readonly string[]).includes(value)
}

export function toPermissionSet(raw: readonly string[], path = '$.permissions'): ReadonlySet<CampaignPermission> {
  const set = new Set<CampaignPermission>()
  raw.forEach((value, index) => {
    if (!isCampaignPermission(value)) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'unknown-permission')
    }
    set.add(value)
  })
  return set
}

export function hasPermission(
  permissions: ReadonlySet<CampaignPermission>,
  required: CampaignPermission,
): boolean {
  return permissions.has(required)
}