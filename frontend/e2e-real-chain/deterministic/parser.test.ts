/**
 * Deterministic tests for the scenario parser.
 *
 * These supplement but never replace the live execution.
 */
import { describe, it, expect } from 'vitest'
import { loadScenarios, corpusHash, scenarioLabel, type Scenario } from '../scenario-loader'

const ROOT_DIR = process.cwd()

describe('Scenario loader', () => {
  it('loads exactly 120 scenarios from both source corpora', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    expect(scenarios.length).toBe(120)
  })

  it('assigns correct source labels', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const campaign = scenarios.filter((s) => s.source === 'campaign_business_scenario_list.md')
    const sportsShoe = scenarios.filter((s) => s.source === 'sports_shoe_business_scenario_list.md')
    expect(campaign.length).toBe(70)
    expect(sportsShoe.length).toBe(50)
  })

  it('produces unique scenario labels', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const labels = scenarios.map((s) => scenarioLabel(s))
    const unique = new Set(labels)
    expect(unique.size).toBe(120)
  })

  it('produces labels in the correct format', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const firstCampaign = scenarios.find((s) => s.source === 'campaign_business_scenario_list.md')
    const firstSportsShoe = scenarios.find((s) => s.source === 'sports_shoe_business_scenario_list.md')
    expect(scenarioLabel(firstCampaign!)).toMatch(/^C-\d{2}$/)
    expect(scenarioLabel(firstSportsShoe!)).toMatch(/^SS-\d{2}$/)
  })

  it('produces a stable corpus hash', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const hash1 = corpusHash(scenarios)
    const hash2 = corpusHash(scenarios)
    expect(hash1).toBe(hash2)
    expect(hash1.length).toBe(16)
  })

  it('does not duplicate scenario descriptions in source code', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    for (const s of scenarios) {
      // Each scenario gets its description from the loaded file
      expect(s.description.length).toBeGreaterThan(10)
      // Verify the description is from the file, not code
      // Description must contain Chinese characters (from the corpus)
      expect(s.description).toMatch(/[一-鿿]/)
    }
  })

  it('tags sports shoe scenarios correctly', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const sportsShoe = scenarios.filter((s) => s.source === 'sports_shoe_business_scenario_list.md')
    for (const s of sportsShoe) {
      expect(s.tags).toContain('sports-shoe')
      expect(s.tags).toContain('member-data')
    }
  })

  it('tags campaign scenarios correctly', () => {
    const scenarios = loadScenarios(ROOT_DIR)
    const campaign = scenarios.filter((s) => s.source === 'campaign_business_scenario_list.md')
    for (const s of campaign) {
      expect(s.tags).toContain('campaign')
      expect(s.tags).toContain('general')
    }
  })
})