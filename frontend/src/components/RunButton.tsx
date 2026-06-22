interface Props {
  isRunning: boolean;
  onRun: () => void;
}

export default function RunButton({ isRunning, onRun }: Props) {
  return (
    <button
      id="run-reconciliation-btn"
      className="run-btn"
      onClick={onRun}
      disabled={isRunning}
      aria-busy={isRunning}
      aria-label={isRunning ? 'Reconciliation run in progress' : 'Run reconciliation now'}
    >
      {isRunning ? (
        <>
          <span className="run-btn-spinner" aria-hidden="true" />
          Running…
        </>
      ) : (
        'Run Reconciliation Now'
      )}
    </button>
  );
}
