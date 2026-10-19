import { defineConfig } from 'vitest/config'
import { resolve } from 'path'

export default defineConfig({
  test: {
    include: [
      'src/**/*.test.ts',
      '../.automation/real-chain-120-multiturn/deterministic/**/*.test.ts',
    ],
    exclude: ['node_modules'],
  },
})