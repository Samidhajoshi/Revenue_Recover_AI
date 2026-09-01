export function LoadingBanner({ label = "Loading..." }) {
  return <div className="banner banner-loading">{label}</div>;
}

export function ErrorBanner({ message, onRetry }) {
  return (
    <div className="banner banner-error">
      <span>{message || "Something went wrong."}</span>
      {onRetry && (
        <button className="btn btn-small" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function EmptyBanner({ label = "No data yet." }) {
  return <div className="banner banner-empty">{label}</div>;
}
