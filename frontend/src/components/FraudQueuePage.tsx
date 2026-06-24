import { useState, useEffect, useCallback } from 'react';
import type { FraudCase, FraudRiskLevel, FraudCaseStatus } from '../types';
import { fetchFraudCases, reviewFraudCase, parseDetails } from '../api';
import ErrorBanner from './ErrorBanner';

// ── Types ─────────────────────────────────────────────────────────────────────

type RiskFilter   = 'ALL' | 'HIGH' | 'MEDIUM' | 'LOW';
type StatusFilter = 'OPEN' | 'ALL' | 'REVIEWED' | 'DISMISSED';

// ── Helper functions ──────────────────────────────────────────────────────────

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('en-US', {
    month:    'short',
    day:      'numeric',
    year:     'numeric',
    hour:     '2-digit',
    minute:   '2-digit',
    hour12:   false,
    timeZone: 'UTC',
  }) + ' UTC';
}

function truncateRef(ref: string | null, max = 26): string {
  if (!ref) return '—';
  return ref.length > max ? ref.slice(0, max) + '…' : ref;
}

function formatAmount(amount: number | null, currency: string | null): string {
  if (amount === null) return '—';
  const formatted = Number(amount).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return currency ? `${formatted} ${currency}` : formatted;
}

/** Parse the triggered_rules comma-separated string into an array of display badges. */
function parseRules(triggeredRules: string): string[] {
  if (!triggeredRules) return [];
  return triggeredRules
    .split(',')
    .map(r => r.trim())
    .filter(Boolean)
    .map(r => r.replace('Rule', ''));  // "VelocityRule" → "Velocity"
}

/** Parse details JSON string for the reasons array. */
function parseReasons(details: string | null): string[] {
  if (!details) return [];
  const parsed = parseDetails(details);
  const reasons = parsed['reasons'];
  if (Array.isArray(reasons)) return reasons as string[];
  return [];
}

// ── Risk badge ────────────────────────────────────────────────────────────────

function RiskBadge({ level }: { level: FraudRiskLevel }) {
  const cls = `fraud-risk-badge fraud-risk-badge--${level.toLowerCase()}`;
  return <span className={cls}>{level}</span>;
}

// ── Status badge ──────────────────────────────────────────────────────────────

function FraudStatusBadge({ status }: { status: FraudCaseStatus }) {
  const cls = `status-badge fraud-status-badge--${status.toLowerCase()}`;
  return <span className={cls}>{status}</span>;
}

// ── Single fraud case row ─────────────────────────────────────────────────────

interface CaseRowProps {
  fraudCase: FraudCase;
  onAction: (id: number, action: 'CONFIRM' | 'DISMISS') => void;
  actionPending: number | null;
}

function FraudCaseRow({ fraudCase, onAction, actionPending }: CaseRowProps) {
  const rules   = parseRules(fraudCase.triggeredRules);
  const reasons = parseReasons(fraudCase.details);
  const isPending = actionPending === fraudCase.id;
  const isOpen    = fraudCase.status === 'OPEN';

  return (
    <article className="exception-row fraud-case-row" aria-label={`Fraud case ${fraudCase.id}, ${fraudCase.riskLevel} risk`}>

      {/* ── Header row ── */}
      <div className="exception-row-header">
        <RiskBadge level={fraudCase.riskLevel} />
        <FraudStatusBadge status={fraudCase.status} />

        <span className="fraud-score-chip" title="Composite fraud score (0–100)">
          Score: {fraudCase.score}
        </span>

        {rules.map(rule => (
          <span key={rule} className="fraud-rule-chip">{rule}</span>
        ))}

        <span className="exception-row-header-right">{formatDate(fraudCase.createdAt)}</span>
      </div>

      {/* ── Body ── */}
      <div className="fraud-case-body">

        {/* Left: transaction & account info */}
        <div className="fraud-case-col">
          <div className="exception-side-header">Transaction</div>
          <div
            className="exception-ref"
            title={fraudCase.transactionRef}
            aria-label={`Transaction ref: ${fraudCase.transactionRef}`}
          >
            {truncateRef(fraudCase.transactionRef)}
          </div>
          <div className="fraud-case-meta">
            <span className="fraud-meta-label">Account</span>
            <span className="fraud-meta-value">#{fraudCase.accountId}</span>
          </div>
          <div className="fraud-case-meta">
            <span className="fraud-meta-label">Amount</span>
            <span className="fraud-meta-value fraud-meta-value--amount">
              {formatAmount(fraudCase.amount, fraudCase.currency)}
            </span>
          </div>
        </div>

        {/* Right: triggered reasons */}
        <div className="fraud-case-col">
          <div className="exception-side-header">Triggered Signals</div>
          {reasons.length > 0 ? (
            <ul className="fraud-reasons-list">
              {reasons.map((reason, i) => (
                <li key={i} className="fraud-reason-item">{reason}</li>
              ))}
            </ul>
          ) : (
            <span className="fraud-no-reasons">No additional details</span>
          )}
        </div>

        {/* Action buttons — only shown for OPEN cases */}
        {isOpen && (
          <div className="fraud-case-actions">
            <button
              id={`fraud-confirm-${fraudCase.id}`}
              className="fraud-action-btn fraud-action-btn--confirm"
              onClick={() => onAction(fraudCase.id, 'CONFIRM')}
              disabled={isPending}
              aria-label="Confirm fraud case"
            >
              {isPending ? 'Saving…' : 'Confirm Fraud'}
            </button>
            <button
              id={`fraud-dismiss-${fraudCase.id}`}
              className="fraud-action-btn fraud-action-btn--dismiss"
              onClick={() => onAction(fraudCase.id, 'DISMISS')}
              disabled={isPending}
              aria-label="Dismiss fraud case"
            >
              {isPending ? 'Saving…' : 'Dismiss'}
            </button>
          </div>
        )}

        {/* Reviewed timestamp for closed cases */}
        {!isOpen && fraudCase.reviewedAt && (
          <div className="fraud-case-actions fraud-case-reviewed-at">
            <span className="fraud-meta-label">Reviewed</span>
            <span className="fraud-meta-value">{formatDate(fraudCase.reviewedAt)}</span>
          </div>
        )}
      </div>
    </article>
  );
}

// ── Main page component ───────────────────────────────────────────────────────

/**
 * FraudQueuePage — analyst workbench for reviewing fraud cases.
 *
 * Data strategy mirrors ExceptionsPage:
 *   - Fetch all cases once with the active status/risk filter.
 *   - Update the list locally on review action (optimistic UI) then re-fetch.
 *   - Show counts per risk level in the filter tabs.
 */
export default function FraudQueuePage() {
  const [cases,         setCases]         = useState<FraudCase[]>([]);
  const [isLoading,     setIsLoading]     = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [statusFilter,  setStatusFilter]  = useState<StatusFilter>('OPEN');
  const [riskFilter,    setRiskFilter]    = useState<RiskFilter>('ALL');
  const [actionPending, setActionPending] = useState<number | null>(null);
  const [actionError,   setActionError]   = useState<string | null>(null);

  // ── Data loading ─────────────────────────────────────────────────────────

  const loadCases = useCallback(async () => {
    setError(null);
    try {
      const data = riskFilter !== 'ALL'
        ? await fetchFraudCases(statusFilter, riskFilter)
        : await fetchFraudCases(statusFilter);
      setCases(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      // Preserve stale data — don't show an empty list when the service is down.
    } finally {
      setIsLoading(false);
    }
  }, [statusFilter, riskFilter]);

  useEffect(() => {
    setIsLoading(true);
    loadCases();
  }, [loadCases]);

  // ── Derived counts for tab badges ─────────────────────────────────────────

  const highCount   = cases.filter(c => c.riskLevel === 'HIGH').length;
  const mediumCount = cases.filter(c => c.riskLevel === 'MEDIUM').length;

  // ── Review action ─────────────────────────────────────────────────────────

  const handleReview = async (id: number, action: 'CONFIRM' | 'DISMISS') => {
    setActionPending(id);
    setActionError(null);
    try {
      await reviewFraudCase(id, action);
      // Re-fetch the list so counts and statuses are consistent with the server.
      await loadCases();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionPending(null);
    }
  };

  // ── Status filter tabs ────────────────────────────────────────────────────

  const statusTabs: { key: StatusFilter; label: string }[] = [
    { key: 'OPEN',      label: 'Open'      },
    { key: 'ALL',       label: 'All'       },
    { key: 'REVIEWED',  label: 'Reviewed'  },
    { key: 'DISMISSED', label: 'Dismissed' },
  ];

  // ── Risk level filter tabs ────────────────────────────────────────────────

  const riskTabs: { key: RiskFilter; label: string }[] = [
    { key: 'ALL',    label: 'All Risk'  },
    { key: 'HIGH',   label: '🔴 High'  },
    { key: 'MEDIUM', label: '🟡 Medium' },
    { key: 'LOW',    label: '🟢 Low'   },
  ];

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="page-wrapper">

      {/* ── Page header ── */}
      <header className="page-header">
        <div className="page-header-top">
          <div>
            <h1 className="page-title">Fraud Queue</h1>
            <p className="page-subtitle">
              Transactions flagged by the fraud detection engine. Review and action each case.
              {!isLoading && cases.length > 0 && (
                <> &nbsp;·&nbsp;
                  {highCount > 0 && <span className="fraud-summary-chip fraud-summary-chip--high">{highCount} HIGH</span>}
                  {mediumCount > 0 && <span className="fraud-summary-chip fraud-summary-chip--medium">&nbsp;{mediumCount} MEDIUM</span>}
                </>
              )}
            </p>
          </div>
        </div>

        {/* ── Toolbar: status + risk filters ── */}
        <div className="toolbar">

          {/* Status filter */}
          <div className="filter-toggle" role="group" aria-label="Filter by case status">
            {statusTabs.map(({ key, label }) => (
              <button
                key={key}
                id={`fraud-status-${key.toLowerCase()}`}
                className={`filter-toggle-btn${statusFilter === key ? ' active' : ''}`}
                onClick={() => { setStatusFilter(key); setRiskFilter('ALL'); }}
                aria-pressed={statusFilter === key}
              >
                {label}
              </button>
            ))}
          </div>

          {/* Risk level filter */}
          <div className="filter-toggle" role="group" aria-label="Filter by risk level">
            {riskTabs.map(({ key, label }) => (
              <button
                key={key}
                id={`fraud-risk-${key.toLowerCase()}`}
                className={`filter-toggle-btn${riskFilter === key ? ' active' : ''}`}
                onClick={() => setRiskFilter(key)}
                aria-pressed={riskFilter === key}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      </header>

      {/* ── Error banners ── */}
      {error      && <ErrorBanner error={error} />}
      {actionError && <ErrorBanner error={`Action failed: ${actionError}`} />}

      {/* ── Content ── */}
      {isLoading ? (
        <div className="loading-state" aria-live="polite">Loading fraud cases…</div>
      ) : cases.length === 0 ? (
        error ? null : (
          <div className="empty-state">
            <div className="empty-state-icon">🛡</div>
            <div className="empty-state-title">No fraud cases</div>
            <div className="empty-state-body">
              {statusFilter === 'OPEN'
                ? 'No open fraud cases — the queue is clear.'
                : 'No fraud cases match the current filter.'}
            </div>
          </div>
        )
      ) : (
        <div className="exceptions-list" aria-label="Fraud cases">
          {cases.map(c => (
            <FraudCaseRow
              key={c.id}
              fraudCase={c}
              onAction={handleReview}
              actionPending={actionPending}
            />
          ))}
        </div>
      )}
    </div>
  );
}
