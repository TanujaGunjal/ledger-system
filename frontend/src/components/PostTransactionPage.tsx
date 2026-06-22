import { useState, useEffect } from 'react';
import AccountSelector from './AccountSelector';
import { LEDGER_API_BASE } from '../config';

function generateUUID() {
  return crypto.randomUUID();
}

export default function PostTransactionPage() {
  const [sourceId, setSourceId] = useState<number | ''>('');
  const [destId, setDestId] = useState<number | ''>('');
  const [amount, setAmount] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  
  const [idempotencyKey, setIdempotencyKey] = useState<string>('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<{ title: string; detail: string } | null>(null);
  const [successRef, setSuccessRef] = useState<string | null>(null);

  // Generate a fresh key on mount, and after every successful post
  useEffect(() => {
    setIdempotencyKey(generateUUID());
  }, []);

  const resetForm = () => {
    setSourceId('');
    setDestId('');
    setAmount('');
    setDescription('');
    setSuccessRef(null);
    setError(null);
    setIdempotencyKey(generateUUID());
  };

  const handleStartTyping = () => {
    // Hide success message once user starts the next entry
    if (successRef) {
      setSuccessRef(null);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!sourceId || !destId || !amount || !description) return;

    const parsedAmount = parseFloat(amount);
    if (isNaN(parsedAmount) || parsedAmount <= 0) {
      setError({ title: 'Validation Error', detail: 'Amount must be greater than zero.' });
      return;
    }

    setIsSubmitting(true);
    setError(null);
    setSuccessRef(null);

    const payload = {
      description,
      entries: [
        { accountId: sourceId, entryType: 'DEBIT', amount: parsedAmount, currency: 'USD' },
        { accountId: destId, entryType: 'CREDIT', amount: parsedAmount, currency: 'USD' }
      ]
    };

    try {
      const res = await fetch(`${LEDGER_API_BASE}/api/v1/transactions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey
        },
        body: JSON.stringify(payload)
      });

      if (!res.ok) {
        let errTitle = 'Network Error';
        let errDetail = 'Failed to communicate with the server.';
        try {
          const errBody = await res.json();
          if (res.status === 409) {
            errTitle = errBody.title || 'Insufficient Funds';
            errDetail = errBody.detail || 'The source account does not have sufficient balance.';
          } else if (res.status === 400) {
            errTitle = errBody.title || 'Bad Request';
            errDetail = errBody.detail || 'The transaction data is invalid.';
          } else {
            errTitle = errBody.title || `Error ${res.status}`;
            errDetail = errBody.detail || 'An unexpected error occurred.';
          }
        } catch {
          // JSON parse failed, keep defaults
        }
        setError({ title: errTitle, detail: errDetail });
        return;
      }

      const data = await res.json();
      setSuccessRef(data.transactionRef);
      setIdempotencyKey(generateUUID()); // cycle key for the next one
      // clear form fields
      setSourceId('');
      setDestId('');
      setAmount('');
      setDescription('');
    } catch (err) {
      setError({ 
        title: 'Network Error', 
        detail: err instanceof Error ? err.message : String(err) 
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="page-wrapper">
      <header className="page-header">
        <h1 className="page-title">Post Transaction</h1>
        <p className="page-subtitle">Move funds between accounts with atomic double-entry posting.</p>
      </header>

      {error && (
        <div className="error-banner" role="alert" aria-live="polite">
          <span className="error-banner-icon" aria-hidden="true">⚠</span>
          <div className="error-banner-text">
            <span className="error-banner-title">{error.title}</span>
            {error.detail}
          </div>
        </div>
      )}

      {successRef && (
        <div className="success-banner" style={{ background: '#ecfdf5', border: '1px solid #6ee7b7', padding: '16px', borderRadius: '8px', marginBottom: '16px' }}>
          <h3 style={{ color: '#047857', fontSize: '14px', margin: '0 0 4px 0', fontWeight: 600 }}>Transaction Posted Successfully</h3>
          <p style={{ color: '#065f46', fontSize: '13px', margin: 0 }}>Reference: <span style={{ fontFamily: 'var(--font-mono)' }}>{successRef}</span></p>
        </div>
      )}

      <form className="post-form" onSubmit={handleSubmit} onChange={handleStartTyping}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '16px' }}>
          <AccountSelector
            label="Source Account (Debit)"
            value={sourceId}
            onChange={setSourceId}
            excludeId={destId === '' ? undefined : destId}
            disabled={isSubmitting}
          />
          <AccountSelector
            label="Destination Account (Credit)"
            value={destId}
            onChange={setDestId}
            excludeId={sourceId === '' ? undefined : sourceId}
            disabled={isSubmitting}
          />
        </div>

        <div className="form-group" style={{ marginBottom: '16px' }}>
          <label className="form-label" style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)', marginBottom: '6px' }}>Amount (USD)</label>
          <input
            type="number"
            step="0.01"
            min="0.01"
            className="form-input"
            style={{ width: '100%', padding: '8px 12px', border: '1px solid var(--border)', borderRadius: '6px', fontFamily: 'var(--font-mono)' }}
            value={amount}
            onChange={e => setAmount(e.target.value)}
            disabled={isSubmitting}
            placeholder="0.00"
            required
          />
        </div>

        <div className="form-group" style={{ marginBottom: '24px' }}>
          <label className="form-label" style={{ display: 'block', fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)', marginBottom: '6px' }}>Description</label>
          <input
            type="text"
            className="form-input"
            style={{ width: '100%', padding: '8px 12px', border: '1px solid var(--border)', borderRadius: '6px' }}
            value={description}
            onChange={e => setDescription(e.target.value)}
            disabled={isSubmitting}
            placeholder="e.g. Invoice payment"
            required
          />
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button 
            type="submit" 
            className="run-btn" 
            disabled={isSubmitting || !sourceId || !destId || !amount || !description}
          >
            {isSubmitting ? 'Posting...' : 'Post Transaction'}
          </button>
          <button 
            type="button" 
            className="filter-toggle-btn" 
            style={{ borderRadius: '6px', border: '1px solid var(--border)', padding: '7px 14px' }}
            onClick={resetForm}
            disabled={isSubmitting}
          >
            Reset
          </button>
        </div>
        <p style={{ marginTop: '12px', fontSize: '11px', color: 'var(--text-muted)' }}>
          Idempotency Key: <span style={{ fontFamily: 'var(--font-mono)' }}>{idempotencyKey}</span>
        </p>
      </form>
    </div>
  );
}
