import type { ViewState } from '../types';

interface NavBarProps {
  activeView: ViewState;
  onViewChange: (view: ViewState) => void;
}

export default function NavBar({ activeView, onViewChange }: NavBarProps) {
  const tabs: { id: ViewState; label: string }[] = [
    { id: 'queue', label: 'Reconciliation Queue' },
    { id: 'post', label: 'Post Transaction' },
    { id: 'explorer', label: 'Ledger Explorer' },
  ];

  return (
    <nav className="navbar" aria-label="Main Navigation">
      <div className="navbar-container">
        <div className="navbar-brand">Ledger System</div>
        <div className="filter-toggle" role="group">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              className={`filter-toggle-btn ${activeView === tab.id ? 'active' : ''}`}
              onClick={() => onViewChange(tab.id)}
              aria-pressed={activeView === tab.id}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>
    </nav>
  );
}
