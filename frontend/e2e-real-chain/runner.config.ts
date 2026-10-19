import { defineConfig } from '@playwright/test'

/**
 * Real-chain Campaign multi-turn evaluation runner.
 *
 * Attaches to the already-running user-facing HTTP application at 127.0.0.1:5173
 * through the installed Microsoft Edge channel. No Vite server is started here:
 * the app must already be running.
 *
 * Overrides:
 *   REAL_CHAIN_PORT  — another local port (default 5173)
 *   REAL_CHAIN_WORKERS — bounded parallelism (default 1, max 4)
 *   REAL_CHAIN_EDGE_PATH — explicit Edge executable path
 */
const PORT = Number(process.env.REAL_CHAIN_PORT ?? 5173)
const BASE_URL = `http://127.0.0.1:${PORT}`
const EDGE_PATH = process.env.REAL_CHAIN_EDGE_PATH
const WORKERS = Math.min(Number(process.env.REAL_CHAIN_WORKERS ?? 1), 4)

export default defineConfig({
  testDir: '.',
  // Only run the spec files explicitly listed; no globalSetup.
  fullyParallel: false,
  workers: WORKERS,
  forbidOnly: true,
  timeout: 900_000, // 15 minutes per scenario
  expect: { timeout: 120_000 }, // 2 minutes for individual assertions
  reporter: [['list'], ['json', { outputFile: 'report.json' }]],
  use: {
    baseURL: BASE_URL,
    channel: EDGE_PATH === undefined ? 'msedge' : undefined,
    launchOptions: EDGE_PATH === undefined ? {} : { executablePath: EDGE_PATH },
    headless: true,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
})