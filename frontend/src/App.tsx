import { useState } from 'react';
import type { ViewState } from './types';
import NavBar from './components/NavBar';
import ExceptionsPage from './components/ExceptionsPage';
import PostTransactionPage from './components/PostTransactionPage';
import LedgerExplorerPage from './components/LedgerExplorerPage';
import FraudQueuePage from './components/FraudQueuePage';

export default function App() {
  const [activeView, setActiveView] = useState<ViewState>('queue');

  return (
    <>
      <NavBar activeView={activeView} onViewChange={setActiveView} />
      <main>
        {activeView === 'queue'    && <ExceptionsPage />}
        {activeView === 'post'     && <PostTransactionPage />}
        {activeView === 'explorer' && <LedgerExplorerPage />}
        {activeView === 'fraud'    && <FraudQueuePage />}
      </main>
    </>
  );
}
