/**
 * The exact build-time environment variables this application reads.
 *
 * Declared explicitly rather than pulled from `vite/client`, so no implicit
 * `any` index signature is available in application code.
 */
interface ImportMetaEnv {
  /** `http` selects the documented HTTP gateway; anything else uses the fixture. */
  readonly VITE_GATEWAY_MODE?: string
  /** Origin only, e.g. `https://host`. Documented routes already include `/api/v1`. */
  readonly VITE_API_BASE_URL?: string
  /** Vite development proxy target; browser requests remain same-origin. */
  readonly VITE_DEV_PROXY_TARGET?: string
  readonly VITE_CAMPAIGN_ID?: string
  /** `true` sends Segment Preview to the accepted `POST /ai/segments/preview` slice. */
  readonly VITE_ACCEPTED_PREVIEW_SLICE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * Local stylesheets are imported for their side effect only. Declared here so
 * the strict project does not need `vite/client` (and its implicit `any` index
 * signature) to typecheck the two CSS imports.
 */
declare module '*.css' {
  const cssText: string
  export default cssText
}
