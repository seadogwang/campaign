import { defineConfig } from '@playwright/test'

/**
 * The T26 real-browser end-to-end configuration.
 *
 * It drives the installed Microsoft Edge channel, so no bundled browser is downloaded, and it starts a real
 * Vite server on a bounded local port whose document it actually loads. Fixture mode is pinned explicitly, so
 * the run is deterministic and offline: the fixture gateway performs no network call.
 *
 * Overrides: `E2E_PORT` selects another local port, and `E2E_EDGE_PATH` points at an Edge executable when the
 * channel lookup cannot find the installed browser.
 */
const PORT = Number(process.env.E2E_PORT ?? 4173)
const BASE_URL = `http://127.0.0.1:${PORT}`
const EDGE_PATH = process.env.E2E_EDGE_PATH

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/globalSetup.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  timeout: 45_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
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
