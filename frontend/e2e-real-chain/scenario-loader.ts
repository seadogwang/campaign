import { readFileSync } from 'fs'
import { resolve } from 'path'
import { createHash } from 'crypto'

/** A single scenario with its source description and metadata. */
export interface Scenario {
  /** 1-based index in the source file. */
  index: number
  /** Source corpus file name (e.g. 'campaign_business_scenario_list.md'). */
  source: string
  /** The raw description text from the markdown. */
  description: string
  /** Whether this scenario expects explicit clarifications. */
  expectsClarification: boolean
  /** Tags derived from the scenario metadata. */
  tags: string[]
}

/**
 * Parses one markdown scenario from a numbered list item.
 *
 * Handles the format:
 *   N. **Title**  description text...
 *   or
 *   N. **Title**  description text...
 *       - `tag`: value
 */
function parseScenarios(markdown: string, source: string): Omit<Scenario, 'expectsClarification' | 'tags'>[] {
  const scenarios: Omit<Scenario, 'expectsClarification' | 'tags'>[] = []
  // Match top-level numbered list items: "N. **...**"
  const itemPattern = /^(\d+)\.\s+\*\*(.+?)\*\*\s+(.+)$/gm

  // Split by numbered items more robustly
  const lines = markdown.split('\n')
  let currentIndex = 0
  let currentTitle = ''
  let currentLines: string[] = []
  let inScenario = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const match = line.match(/^(\d+)\.\s+\*\*(.+?)\*\*\s+(.*)$/)
    if (match) {
      // Save previous scenario
      if (inScenario && currentLines.length > 0) {
        scenarios.push({
          index: currentIndex,
          source,
          description: currentLines.join('\n').trim(),
        })
      }
      currentIndex = parseInt(match[1], 10)
      currentTitle = match[2]
      currentLines = [match[3]]
      inScenario = true
    } else if (inScenario && line.trim() !== '' && !line.startsWith('##') && !line.startsWith('---')) {
      // Continue collecting description lines, skip metadata lines for description
      if (!line.startsWith('  - `') && !line.startsWith('  -  `')) {
        currentLines.push(line)
      }
    } else if (inScenario && (line.startsWith('##') || line.startsWith('---') || line.trim() === '')) {
      // Section break or header — end of scenario body, but keep collecting if lines continue
      if (currentLines.length > 0 && scenarios.length > 0 && scenarios[scenarios.length - 1].index !== currentIndex) {
        // Only end if we haven't already pushed this one
      }
    }
  }

  // Save last scenario
  if (inScenario && currentLines.length > 0) {
    scenarios.push({
      index: currentIndex,
      source,
      description: currentLines.join('\n').trim(),
    })
  }

  return scenarios
}

/**
 * Loads all 120 scenarios from both source corpora.
 *
 * The description text is extracted verbatim from the markdown; no scenario text
 * is duplicated in source code. Each scenario gets metadata tags based on its
 * source and any annotations found in the markdown.
 */
export function loadScenarios(rootDir: string): Scenario[] {
  const campaignPath = resolve(rootDir, 'docs', 'campaign_business_scenario_list.md')
  const sportsShoePath = resolve(rootDir, 'docs', 'sports_shoe_business_scenario_list.md')

  const campaignMd = readFileSync(campaignPath, 'utf-8')
  const sportsShoeMd = readFileSync(sportsShoePath, 'utf-8')

  const campaignScenarios = parseScenarios(campaignMd, 'campaign_business_scenario_list.md')
  const sportsShoeScenarios = parseScenarios(sportsShoeMd, 'sports_shoe_business_scenario_list.md')

  const all: Scenario[] = []

  for (const s of campaignScenarios) {
    const tags: string[] = ['campaign', 'general']
    all.push({
      ...s,
      expectsClarification: false,
      tags,
    })
  }

  for (const s of sportsShoeScenarios) {
    const tags: string[] = ['sports-shoe', 'member-data']
    // Check if this scenario is in the "需澄清" or "不支持" categories
    const isClarification = s.index === 4 || s.index === 7 || s.index === 10 || s.index === 14 ||
      s.index === 17 || s.index === 19 || s.index === 20 || s.index === 21 || s.index === 23 ||
      s.index === 24 || s.index === 28 || s.index === 31 || s.index === 38 || s.index === 39 ||
      s.index === 46 || s.index === 48
    const isUnsupported = s.index === 15 || s.index === 16 || s.index === 22 ||
      s.index === 35 || s.index === 36 || s.index === 37 || s.index === 40

    all.push({
      ...s,
      expectsClarification: isClarification || isUnsupported,
      tags,
    })
  }

  return all
}

/**
 * Computes a stable hash of all scenario descriptions for the resume fingerprint.
 */
export function corpusHash(scenarios: Scenario[]): string {
  const hash = createHash('sha256')
  for (const s of scenarios) {
    hash.update(s.source)
    hash.update(s.description)
    hash.update(s.index.toString())
  }
  return hash.digest('hex').slice(0, 16)
}

/**
 * Formats a scenario label for reporting.
 */
export function scenarioLabel(scenario: Scenario): string {
  const corpus = scenario.source === 'campaign_business_scenario_list.md' ? 'C' : 'SS'
  return `${corpus}-${String(scenario.index).padStart(2, '0')}`
}