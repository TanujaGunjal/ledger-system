// Defaults to localhost — works for `npm run dev` without any .env file.
// Override with --build-arg at docker build time for production images.
export const LEDGER_API_BASE =
  import.meta.env.VITE_LEDGER_API_BASE ?? 'http://localhost:9090';

export const RECON_API_BASE =
  import.meta.env.VITE_RECON_API_BASE ?? 'http://localhost:9091';

// fraud-service is on host port 9093 (maps to container port 9092).
// Port 9092 on the host is occupied by Redpanda, so we shift fraud-service
// to 9093 in docker-compose to avoid a conflict.
export const FRAUD_API_BASE =
  import.meta.env.VITE_FRAUD_API_BASE ?? 'http://localhost:9093';
