import type { StatusFilter } from '../types';

interface Props {
  filter: StatusFilter;
}

export default function EmptyState({ filter }: Props) {
  if (filter === 'OPEN') {
    return (
      <div className="empty-state">
        <div className="empty-state-icon" aria-hidden="true">✓</div>
        <p className="empty-state-title">All transactions reconciled</p>
        <p className="empty-state-body">
          No open exceptions. Every posted transaction has a matching external statement entry
          within the configured amount and date tolerances.
        </p>
      </div>
    );
  }

  if (filter === 'RESOLVED') {
    return (
      <div className="empty-state">
        <div className="empty-state-icon" aria-hidden="true">○</div>
        <p className="empty-state-title">No resolved exceptions</p>
        <p className="empty-state-body">
          No exceptions have been resolved yet. Run reconciliation after inserting
          matching external entries to see resolved rows here.
        </p>
      </div>
    );
  }

  // filter === 'ALL'
  return (
    <div className="empty-state">
      <div className="empty-state-icon" aria-hidden="true">○</div>
      <p className="empty-state-title">No exceptions found</p>
      <p className="empty-state-body">
        The exceptions table is empty. Run the mock feed generator and then
        trigger a reconciliation pass to populate it.
      </p>
    </div>
  );
}
