import { useState, useEffect, useCallback } from 'react';
import AccountSelector from './AccountSelector';
import ErrorBanner from './ErrorBanner';
import type { LedgerEntry, AccountBalance } from '../types';
import { LEDGER_API_BASE } from '../config';

export default function LedgerExplorerPage() {
  const [accountId, setAccountId] = useState<number | ''>('');
  
  const [balance, setBalance] = useState<AccountBalance | null>(null);
  const [entries, setEntries] = useState<LedgerEntry[]>([]);
  
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const [hasMore, setHasMore] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const LIMIT = 50;

  const fetchBalanceAndEntries = useCallback(async (id: number) => {
    setIsLoading(true);
    setError(null);
    try {
      const [balRes, entriesRes] = await Promise.all([
        fetch(`${LEDGER_API_BASE}/api/v1/accounts/${id}/balance`),
        fetch(`${LEDGER_API_BASE}/api/v1/accounts/${id}/ledger-entries?limit=${LIMIT}&afterSequence=0`)
      ]);

      if (!balRes.ok) throw new Error(`Failed to fetch balance: ${balRes.status}`);
      if (!entriesRes.ok) throw new Error(`Failed to fetch entries: ${entriesRes.status}`);

      const balData = await balRes.json();
      const entriesData = await entriesRes.json();

      setBalance(balData);
      setEntries(entriesData);
      setHasMore(entriesData.length === LIMIT);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBalance(null);
      setEntries([]);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (accountId === '') {
      setBalance(null);
      setEntries([]);
      setError(null);
      return;
    }
    fetchBalanceAndEntries(accountId);
  }, [accountId, fetchBalanceAndEntries]);

  const loadMore = async () => {
    if (!accountId || entries.length === 0) return;
    
    setIsLoadingMore(true);
    setError(null);
    const lastSeq = entries[entries.length - 1].sequenceNo;

    try {
      const res = await fetch(`${LEDGER_API_BASE}/api/v1/accounts/${accountId}/ledger-entries?limit=${LIMIT}&afterSequence=${lastSeq}`);
      if (!res.ok) throw new Error(`Failed to fetch more entries: ${res.status}`);
      
      const newEntries = await res.json();
      setEntries(prev => [...prev, ...newEntries]);
      setHasMore(newEntries.length === LIMIT);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsLoadingMore(false);
    }
  };

  const formatCurrency = (amount: number, currency: string) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
      minimumFractionDigits: 2
    }).format(amount);
  };

  return (
    <div className="page-wrapper">
      <header className="page-header">
        <h1 className="page-title">Ledger Explorer</h1>
        <p className="page-subtitle">View account balances and immutable ledger entries.</p>
      </header>

      <div style={{ maxWidth: '400px', marginBottom: '24px' }}>
        <AccountSelector
          label="Select Account to View"
          value={accountId}
          onChange={setAccountId}
        />
      </div>

      {error && <ErrorBanner error={error} />}

      {isLoading ? (
        <div className="loading-state">Loading account data...</div>
      ) : accountId !== '' && balance ? (
        <div className="explorer-content">
          <div className="balance-display" style={{ background: 'var(--surface)', padding: '24px', borderRadius: '8px', border: '1px solid var(--border)', marginBottom: '24px', display: 'flex', alignItems: 'baseline', gap: '8px' }}>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)', fontWeight: 500, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Current Balance</div>
            <div style={{ fontFamily: 'var(--font-mono)', fontSize: '32px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {formatCurrency(balance.balance, 'USD')}
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginLeft: 'auto' }}>
              Last Updated: {new Date(balance.updatedAt).toLocaleString()}
            </div>
          </div>

          <h3 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px', color: 'var(--text-primary)' }}>Ledger Entries</h3>
          
          {entries.length === 0 ? (
            <div className="empty-state" style={{ padding: '40px 24px', border: '1px solid var(--border)', borderRadius: '8px', background: 'var(--surface)' }}>
              <div className="empty-state-title">No entries found</div>
              <div className="empty-state-body">This account has no transaction history.</div>
            </div>
          ) : (
            <>
              <div className="entries-table" style={{ background: 'var(--surface)', borderRadius: '8px', border: '1px solid var(--border)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '13px' }}>
                  <thead style={{ background: '#fafafa', borderBottom: '1px solid var(--border-subtle)' }}>
                    <tr>
                      <th style={{ padding: '12px 16px', fontWeight: 500, color: 'var(--text-secondary)' }}>Seq No</th>
                      <th style={{ padding: '12px 16px', fontWeight: 500, color: 'var(--text-secondary)' }}>Date</th>
                      <th style={{ padding: '12px 16px', fontWeight: 500, color: 'var(--text-secondary)' }}>Type</th>
                      <th style={{ padding: '12px 16px', fontWeight: 500, color: 'var(--text-secondary)', textAlign: 'right' }}>Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map(entry => (
                      <tr key={entry.id} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', color: 'var(--text-muted)', fontSize: '12px' }}>{entry.sequenceNo}</td>
                        <td style={{ padding: '12px 16px', color: 'var(--text-primary)' }}>{new Date(entry.createdAt).toLocaleString()}</td>
                        <td style={{ padding: '12px 16px' }}>
                          <span className={`status-badge ${entry.entryType === 'DEBIT' ? 'open' : 'resolved'}`} style={{ 
                            background: entry.entryType === 'DEBIT' ? '#fef2f2' : '#ecfdf5',
                            color: entry.entryType === 'DEBIT' ? '#dc2626' : '#047857',
                            border: `1px solid ${entry.entryType === 'DEBIT' ? '#fca5a5' : '#6ee7b7'}`
                          }}>
                            {entry.entryType}
                          </span>
                        </td>
                        <td style={{ padding: '12px 16px', textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 500 }}>
                          {formatCurrency(entry.amount, entry.currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              
              {hasMore && (
                <div style={{ textAlign: 'center', marginTop: '16px' }}>
                  <button 
                    className="filter-toggle-btn"
                    style={{ borderRadius: '6px', border: '1px solid var(--border)', padding: '8px 16px' }}
                    onClick={loadMore}
                    disabled={isLoadingMore}
                  >
                    {isLoadingMore ? 'Loading...' : 'Load More'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      ) : null}
    </div>
  );
}
