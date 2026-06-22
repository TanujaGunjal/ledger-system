import { useState, useEffect, useCallback } from 'react';
import type { ReconciliationException, ReconciliationRunResult, StatusFilter } from '../types';
import { fetchExceptions, triggerRun } from '../api';
import ErrorBanner from './ErrorBanner';
import EmptyState from './EmptyState';
import ExceptionRow from './ExceptionRow';
import RunButton from './RunButton';

/**
 * Single-fetch strategy:
 *   - Always fetch ?status=ALL once on mount and after every Run completes.
 *   - Store the full array in `allExceptions`.
 *   - Derive the three per-tab lists and all badge counts client-side.
 *   - No extra network round-trips when the user switches tabs.
 *
 * This avoids the Open(N) count bug where the badge only reflected whatever
 * was already visible in the current tab's filtered response.
 */
export default function ExceptionsPage() {
  const [allExceptions, setAllExceptions] = useState<ReconciliationException[]>([]);
  const [isLoading,     setIsLoading]     = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [isRunning,     setIsRunning]     = useState(false);
  const [lastResult,    setLastResult]    = useState<ReconciliationRunResult | null>(null);
  const [filter,        setFilter]        = useState<StatusFilter>('OPEN');

  /** Fetch the full dataset once. All derived views come from this. */
  const loadAll = useCallback(async () => {
    setError(null);
    try {
      const data = await fetchExceptions('ALL');
      setAllExceptions(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      // Intentional: DO NOT setAllExceptions([]) here. 
      // Clearing the data would trigger the EmptyState ('All reconciled'),
      // which is dangerously misleading if the backend is simply down.
      // Better to preserve stale data (with the error banner above it)
      // or show an empty list without the success icon.
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Load on mount only.
  useEffect(() => {
    setIsLoading(true);
    loadAll();
  }, [loadAll]);

  // Derived lists — computed synchronously from allExceptions, no fetch needed.
  const openList     = allExceptions.filter(e => e.status === 'OPEN');
  const resolvedList = allExceptions.filter(e => e.status === 'RESOLVED');

  const openCount     = openList.length;
  const resolvedCount = resolvedList.length;
  const allCount      = allExceptions.length;

  // The list shown for the active tab.
  const visibleList =
    filter === 'OPEN'     ? openList :
    filter === 'RESOLVED' ? resolvedList :
    allExceptions;

  const handleRun = async () => {
    setIsRunning(true);
    setLastResult(null);
    setError(null);
    try {
      const result = await triggerRun();
      setLastResult(result);
      // Re-fetch the full dataset so all tab counts and lists update atomically.
      await loadAll();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsRunning(false);
    }
  };

  /** Tab config drives the filter toggle — label + badge count + id. */
  const tabs: { key: StatusFilter; label: string; count: number }[] = [
    { key: 'OPEN',     label: 'Open',     count: openCount     },
    { key: 'ALL',      label: 'All',      count: allCount      },
    { key: 'RESOLVED', label: 'Resolved', count: resolvedCount },
  ];

  return (
    <div className="page-wrapper">
      {/* ── Page header ── */}
      <header className="page-header">
        <div className="page-header-top">
          <div>
            <h1 className="page-title">Reconciliation Queue</h1>
            <p className="page-subtitle">
              Discrepancies between posted ledger transactions and external statement entries.
            </p>
          </div>
        </div>

        {/* ── Toolbar ── */}
        <div className="toolbar">
          <RunButton isRunning={isRunning} onRun={handleRun} />

          <div
            className="filter-toggle"
            role="group"
            aria-label="Filter exceptions by status"
          >
            {tabs.map(({ key, label, count }) => (
              <button
                key={key}
                id={`filter-btn-${key.toLowerCase()}`}
                className={`filter-toggle-btn${filter === key ? ' active' : ''}`}
                onClick={() => setFilter(key)}
                aria-pressed={filter === key}
              >
                {isLoading ? label : `${label} (${count})`}
              </button>
            ))}
          </div>

          {lastResult && (
            <span className="run-result" aria-live="polite">
              Last run: {lastResult.matched} matched · {lastResult.resolved} resolved ·{' '}
              {lastResult.missingExternal + lastResult.missingInternal + lastResult.amountMismatch} new exceptions
            </span>
          )}
        </div>
      </header>

      {/* ── Error banner — stays visible until next successful fetch ── */}
      {error && <ErrorBanner error={error} />}

      {/* ── Content ── */}
      {isLoading ? (
        <div className="loading-state" aria-live="polite">Loading…</div>
      ) : visibleList.length === 0 ? (
        error ? null : <EmptyState filter={filter} />
      ) : (
        <main className="exceptions-list" aria-label="Reconciliation exceptions">
          {visibleList.map(exc => (
            <ExceptionRow key={exc.id} exception={exc} />
          ))}
        </main>
      )}
    </div>
  );
}
