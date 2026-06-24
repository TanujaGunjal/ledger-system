import type { ReconciliationException } from '../types';
import { parseDetails } from '../api';
import StatusBadge from './StatusBadge';

interface Props {
  exception: ReconciliationException;
}

/** Format a bare number amount for display. Amounts arrive as JSON numbers with 4dp. */
function formatAmount(amount: number | null): string {
  if (amount === null) return '—';
  return Number(amount).toFixed(2);
}

/**
 * Format a UTC ISO-8601 timestamp for display.
 * Confirmed format from live API: "2026-06-21T17:33:20.145979Z" (microseconds, Z suffix).
 */
function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'UTC',
  }) + ' UTC';
}

/** Truncate a reference UUID for display; always show full value in title tooltip. */
function truncateRef(ref: string | null, max = 26): string {
  if (!ref) return '—';
  return ref.length > max ? ref.slice(0, max) + '…' : ref;
}

function exceptionTypeLabel(type: ReconciliationException['exceptionType']): string {
  switch (type) {
    case 'MISSING_EXTERNAL': return 'Missing external';
    case 'MISSING_INTERNAL': return 'Missing internal';
    case 'AMOUNT_MISMATCH':  return 'Amount mismatch';
  }
}

export default function ExceptionRow({ exception }: Props) {
  const {
    exceptionType, status, transactionRef, externalReference,
    internalAmount, externalAmount, details, createdAt,
  } = exception;

  // details is a JSON string from the backend — must be parsed, not spread directly
  const parsed = parseDetails(details) as Record<string, string>;
  const delta   = parsed.delta;

  const isMissingExternal = exceptionType === 'MISSING_EXTERNAL';
  const isMissingInternal = exceptionType === 'MISSING_INTERNAL';
  const isAmountMismatch  = exceptionType === 'AMOUNT_MISMATCH';

  return (
    <article
      className="exception-row"
      aria-label={`${exceptionTypeLabel(exceptionType)} exception, ${status}`}
    >
      {/* ── Row header: badge · type label · delta · timestamp ── */}
      <div className="exception-row-header">
        <StatusBadge status={status} />
        <span className="exception-type-label">{exceptionTypeLabel(exceptionType)}</span>
        {isAmountMismatch && delta && (
          <span className="delta-badge" title={`Amount delta: ${delta}`}>
            Δ {Number(delta).toFixed(2)}
          </span>
        )}
        <span className="exception-row-header-right">{formatDate(createdAt)}</span>
      </div>

      {/* ── Row body: internal side | center | external side ── */}
      <div className="exception-row-body">

        {/* Internal ledger column */}
        <div className="exception-side">
          <div className="exception-side-header">Internal ledger</div>
          {isMissingInternal ? (
            <span className="exception-missing-state" aria-label="No internal transaction found">
              ⊘ Not in ledger
            </span>
          ) : (
            <>
              <div
                className="exception-ref"
                title={transactionRef ?? ''}
                aria-label={`Transaction ref: ${transactionRef}`}
              >
                {truncateRef(transactionRef)}
              </div>
              <span className={`exception-amount${isAmountMismatch ? ' highlight' : ''}`}>
                {formatAmount(internalAmount)}
              </span>
            </>
          )}
        </div>

        {/* Center column — "vs" separator */}
        <div className="exception-center" aria-hidden="true">
          <span className="exception-center-text">vs</span>
        </div>

        {/* External statement column */}
        <div className="exception-side">
          <div className="exception-side-header">External statement</div>
          {isMissingExternal ? (
            <span className="exception-missing-state" aria-label="No external statement received">
              ⊘ Not received
            </span>
          ) : (
            <>
              <div
                className="exception-ref"
                title={externalReference ?? ''}
                aria-label={`External ref: ${externalReference}`}
              >
                {truncateRef(externalReference)}
              </div>
              <span className={`exception-amount${isAmountMismatch ? ' highlight' : ''}`}>
                {formatAmount(externalAmount)}
              </span>
            </>
          )}
        </div>
      </div>
    </article>
  );
}
