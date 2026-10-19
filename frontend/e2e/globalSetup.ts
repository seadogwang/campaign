import { createServer } from 'vite'

const PORT = Number(process.env.E2E_PORT ?? 4173)

export default async function globalSetup(): Promise<() => Promise<void>> {
  process.env.VITE_GATEWAY_MODE = 'fixture'
  process.env.VITE_API_BASE_URL = ''
  process.env.VITE_CAMPAIGN_ID = ''

  const server = await createServer({
    logLevel: 'error',
    server: {
      host: '127.0.0.1',
      port: PORT,
      strictPort: true,
    },
  })
  await server.listen()

  return async () => {
    await server.close()
  }
}
