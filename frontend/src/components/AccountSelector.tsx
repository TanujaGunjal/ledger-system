import { useState, useEffect } from 'react';
import type { Account } from '../types';
import { LEDGER_API_BASE } from '../config';

interface AccountSelectorProps {
  label: string;
  value: number | '';
  onChange: (value: number | '') => void;
  excludeId?: number; // to prevent selecting the same account for source and destination
  disabled?: boolean;
}

export default function AccountSelector({ label, value, onChange, excludeId, disabled }: AccountSelectorProps) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    const fetchAccounts = async () => {
      setIsLoading(true);
      try {
        const res = await fetch(`${LEDGER_API_BASE}/api/v1/accounts`);
        if (!res.ok) throw new Error('Failed to fetch accounts');
        const data = await res.json();
        if (mounted) setAccounts(data);
      } catch (err) {
        if (mounted) setError(err instanceof Error ? err.message : String(err));
      } finally {
        if (mounted) setIsLoading(false);
      }
    };
    fetchAccounts();
    return () => { mounted = false; };
  }, []);

  const options = excludeId 
    ? accounts.filter(a => a.id !== excludeId)
    : accounts;

  return (
    <div className="form-group">
      <label className="form-label">{label}</label>
      {error ? (
        <div className="form-error">Error loading accounts</div>
      ) : (
        <select 
          className="form-select"
          value={value} 
          onChange={(e) => onChange(e.target.value ? Number(e.target.value) : '')}
          disabled={disabled || isLoading}
        >
          <option value="">Select an account...</option>
          {options.map(acc => (
            <option key={acc.id} value={acc.id}>
              {acc.accountNumber} — {acc.ownerName} ({acc.accountType})
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
