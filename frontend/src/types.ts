export type ExceptionType = 'MISSING_EXTERNAL' | 'MISSING_INTERNAL' | 'AMOUNT_MISMATCH';
export type ExceptionStatus = 'OPEN' | 'RESOLVED';

/**
 * Matches the ReconciliationException Java record serialised by Jackson.
 *
 * Key shapes confirmed from live curl output:
 *   - internalAmount / externalAmount: bare JSON number with 4 decimal places
 *     (e.g. 200.0000), NOT a quoted string. Use Number(val).toFixed(2) to display.
 *   - details: escaped JSON STRING (not a nested object). Must be JSON.parse()'d
 *     to access fields like delta, posted_at, currency etc.
 *   - createdAt / resolvedAt: ISO-8601 with microseconds and Z suffix.
 *   - Nullable fields are null (not absent from the JSON object).
 */
export interface ReconciliationException {
  id: number;
  exceptionType: ExceptionType;
  status: ExceptionStatus;
  transactionRef: string | null;       // null for MISSING_INTERNAL
  externalReference: string | null;    // null for MISSING_EXTERNAL
  internalAmount: number | null;       // null for MISSING_INTERNAL
  externalAmount: number | null;       // null for MISSING_EXTERNAL
  details: string;                     // JSON string — must be JSON.parse()'d
  createdAt: string;
  resolvedAt: string | null;           // null while OPEN
}

export interface ReconciliationRunResult {
  matched: number;
  resolved: number;
  missingExternal: number;
  missingInternal: number;
  amountMismatch: number;
}

export interface Account {
  id: number;
  accountNumber: string;
  ownerName: string;
  accountType: string;
  currency: string;
  status: string;
  createdAt: string;
}

export interface LedgerEntry {
  id: number;
  transactionId: number;
  accountId: number;
  entryType: 'DEBIT' | 'CREDIT';
  amount: number;
  currency: string;
  sequenceNo: number;
  createdAt: string;
}

export interface AccountBalance {
  accountId: number;
  balance: number;
  lastEntrySequence: number;
  updatedAt: string;
}

export type StatusFilter = 'OPEN' | 'ALL' | 'RESOLVED';
export type ViewState = 'queue' | 'post' | 'explorer';
