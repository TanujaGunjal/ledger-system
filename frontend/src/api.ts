import type { ReconciliationException, ReconciliationRunResult, StatusFilter } from './types';
import { RECON_API_BASE } from './config';

const RECON_BASE = `${RECON_API_BASE}/api/v1/reconciliation`;

/**
 * Fetch reconciliation exceptions.
 *
 * statusFilter maps to the ?status= query param on the backend:
 *   'OPEN'     → no param (backend default) — returns only OPEN exceptions
 *   'ALL'      → ?status=ALL — returns OPEN + RESOLVED, ordered by created_at DESC
 *   'RESOLVED' → ?status=RESOLVED — returns resolved exceptions only
 */
export async function fetchExceptions(
  statusFilter: StatusFilter = 'OPEN'
): Promise<ReconciliationException[]> {
  const url =
    statusFilter === 'OPEN'
      ? `${RECON_BASE}/exceptions`
      : `${RECON_BASE}/exceptions?status=${statusFilter}`;

  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(
      `Failed to load exceptions: HTTP ${res.status} ${res.statusText} — is reconciliation-worker running on port 9091?`
    );
  }
  return res.json() as Promise<ReconciliationException[]>;
}

/**
 * Trigger an immediate reconciliation run.
 * Uses POST — this endpoint writes rows (matches + resolved exceptions)
 * so GET would violate RFC 9110 §9.2.1.
 */
export async function triggerRun(): Promise<ReconciliationRunResult> {
  const res = await fetch(`${RECON_BASE}/run`, { method: 'POST' });
  if (!res.ok) {
    throw new Error(
      `Failed to trigger run: HTTP ${res.status} ${res.statusText}`
    );
  }
  return res.json() as Promise<ReconciliationRunResult>;
}

/**
 * Safely parse the details JSON string.
 * The backend stores details as a jsonb column but Jackson serialises the
 * Java String field as an escaped JSON string, not a nested object.
 * Returns {} on any parse failure so callers don't need try/catch.
 */
export function parseDetails(raw: string): Record<string, string> {
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') return parsed as Record<string, string>;
    return {};
  } catch {
    return {};
  }
}
