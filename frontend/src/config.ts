// Defaults to localhost — works for `npm run dev` without any .env file.
// Override with --build-arg at docker build time for production images.
export const LEDGER_API_BASE =
  import.meta.env.VITE_LEDGER_API_BASE ?? 'http://localhost:9090';

export const RECON_API_BASE =
  import.meta.env.VITE_RECON_API_BASE ?? 'http://localhost:9091';
