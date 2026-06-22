interface Props {
  error: string;
}

export default function ErrorBanner({ error }: Props) {
  return (
    <div className="error-banner" role="alert" aria-live="polite">
      <span className="error-banner-icon" aria-hidden="true">⚠</span>
      <div className="error-banner-text">
        <span className="error-banner-title">Backend unreachable</span>
        {error}
      </div>
    </div>
  );
}
