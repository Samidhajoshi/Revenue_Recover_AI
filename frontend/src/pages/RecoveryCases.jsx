import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import useApi from "../hooks/useApi";
import { getRecoveryCases, runRecoveryCase } from "../api/endpoints";
import { EmptyBanner, ErrorBanner, LoadingBanner } from "../components/StatusBanner";
import { formatCurrency, formatNumber } from "../utils/format";
import { describeApiError } from "../api/client";

// Filters map to query params per the API contract: type, riskTier, status.
const FILTERS = [
  { key: "ALL", label: "All", params: {} },
  { key: "PAYMENT", label: "Payment", params: { type: "PAYMENT" } },
  { key: "SUBSCRIPTION", label: "Subscription", params: { type: "SUBSCRIPTION" } },
  { key: "GATEWAY", label: "Gateway", params: { type: "GATEWAY" } },
  { key: "HIGH_RISK", label: "High Risk", params: { riskTier: "HIGH" } },
  { key: "RECOVERED", label: "Recovered", params: { status: "RECOVERED" } },
  { key: "ESCALATED", label: "Escalated", params: { status: "ESCALATED" } },
];

// Runs case-run calls with a bounded number in flight at once, rather than
// one giant serial loop (slow) or firing everything at once (hammers the
// backend). Reports progress as each call settles.
async function runWithConcurrency(ids, worker, concurrency, onProgress) {
  const results = [];
  let cursor = 0;
  let done = 0;

  async function runNext() {
    while (cursor < ids.length) {
      const index = cursor++;
      const id = ids[index];
      try {
        const res = await worker(id);
        results[index] = { id, ok: true, data: res.data };
      } catch (err) {
        results[index] = { id, ok: false, error: err };
      }
      done++;
      onProgress?.(done, ids.length);
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, ids.length) }, runNext);
  await Promise.all(workers);
  return results;
}

function summarizeRunResults(results) {
  const ok = results.filter((r) => r.ok);
  const recoveredAmount = ok.reduce((sum, r) => sum + (r.data?.recoveredAmount || 0), 0);
  const byState = {};
  ok.forEach((r) => {
    const state = r.data?.finalState || "UNKNOWN";
    byState[state] = (byState[state] || 0) + 1;
  });
  return {
    total: results.length,
    succeeded: ok.length,
    failed: results.length - ok.length,
    recoveredAmount,
    byState,
  };
}

export default function RecoveryCases() {
  const [active, setActive] = useState("ALL");
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const filter = FILTERS.find((f) => f.key === active) || FILTERS[0];
  const { data, loading, error, reload } = useApi(
    () => getRecoveryCases(filter.params),
    [active]
  );

  const allRows = useMemo(() => {
    if (!data) return [];
    if (Array.isArray(data)) return data;
    if (Array.isArray(data.items)) return data.items;
    if (Array.isArray(data.data)) return data.data;
    return [];
  }, [data]);

  const [customerQuery, setCustomerQuery] = useState(() => searchParams.get("customer") || "");
  const rows = useMemo(() => {
    const q = customerQuery.trim().toLowerCase();
    if (!q) return allRows;
    return allRows.filter((r) => (r.customerId || "").toLowerCase().includes(q));
  }, [allRows, customerQuery]);

  const [selected, setSelected] = useState(() => new Set());
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(null); // { done, total }
  const [runSummary, setRunSummary] = useState(null);
  const [runError, setRunError] = useState(null);

  // Selection and the customer search are scoped to whatever's currently shown -
  // switching filters starts fresh.
  useEffect(() => {
    setSelected(new Set());
    setRunSummary(null);
    setRunError(null);
    setCustomerQuery("");
  }, [active]);

  function toggleOne(id) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAllVisible() {
    setSelected((prev) => {
      const allSelected = rows.length > 0 && rows.every((r) => prev.has(r.id));
      if (allSelected) return new Set();
      return new Set(rows.map((r) => r.id));
    });
  }

  async function runIds(ids, label) {
    if (ids.length === 0) return;
    setRunning(true);
    setRunError(null);
    setRunSummary(null);
    setProgress({ done: 0, total: ids.length });
    try {
      const results = await runWithConcurrency(ids, runRecoveryCase, 8, (done, total) =>
        setProgress({ done, total })
      );
      setRunSummary({ label, ...summarizeRunResults(results) });
      setSelected(new Set());
      reload();
    } catch (err) {
      setRunError(describeApiError(err));
    } finally {
      setRunning(false);
      setProgress(null);
    }
  }

  const selectedIds = Array.from(selected);
  const allVisibleSelected = rows.length > 0 && rows.every((r) => selected.has(r.id));

  return (
    <div className="page">
      <h1>Recovery Cases</h1>

      <div className="filter-bar">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            className={"btn btn-filter" + (active === f.key ? " active" : "")}
            onClick={() => setActive(f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="card">
        <div className="actions-row" style={{ marginTop: 0, marginBottom: "0.75rem" }}>
          <input
            type="text"
            placeholder="Search by customer ID..."
            value={customerQuery}
            onChange={(e) => setCustomerQuery(e.target.value)}
            style={{ minWidth: "240px" }}
          />
          {customerQuery && (
            <span className="muted">
              {rows.length} match{rows.length === 1 ? "" : "es"} in "{filter.label}"
            </span>
          )}
        </div>
        <div className="actions-row" style={{ marginTop: 0, flexWrap: "wrap" }}>
          <button
            className="btn"
            onClick={() => runIds(selectedIds, `${selectedIds.length} selected case${selectedIds.length === 1 ? "" : "s"}`)}
            disabled={running || selectedIds.length === 0}
          >
            Run selected {selectedIds.length > 0 ? `(${selectedIds.length})` : ""}
          </button>
          <button
            className="btn btn-primary"
            onClick={() =>
              runIds(
                rows.map((r) => r.id),
                `all ${rows.length} case${rows.length === 1 ? "" : "s"} in "${filter.label}"` +
                  (customerQuery ? ` matching "${customerQuery}"` : "")
              )
            }
            disabled={running || rows.length === 0}
          >
            Run all in "{filter.label}"{customerQuery ? ` matching "${customerQuery}"` : ""} ({rows.length})
          </button>
          {running && progress && (
            <span className="muted">
              Running {progress.done}/{progress.total}...
            </span>
          )}
        </div>
        {runError && <ErrorBanner message={runError} onRetry={() => runIds(selectedIds, "retry")} />}
        {runSummary && (
          <p style={{ marginTop: "0.75rem" }}>
            Ran <strong>{runSummary.label}</strong>: {formatNumber(runSummary.succeeded)} completed
            {runSummary.failed > 0 && `, ${formatNumber(runSummary.failed)} failed to run`} — recovered{" "}
            <strong>{formatCurrency(runSummary.recoveredAmount)}</strong>.{" "}
            {Object.entries(runSummary.byState)
              .map(([state, count]) => `${count} ${state}`)
              .join(", ")}
            .
          </p>
        )}
      </div>

      <div className="card">
        {loading && <LoadingBanner label="Loading cases..." />}
        {error && <ErrorBanner message={error} onRetry={reload} />}
        {!loading && !error && rows.length === 0 && <EmptyBanner label="No cases found." />}
        {!loading && !error && rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>
                    <input
                      type="checkbox"
                      checked={allVisibleSelected}
                      onChange={toggleAllVisible}
                      onClick={(e) => e.stopPropagation()}
                      aria-label="Select all visible cases"
                    />
                  </th>
                  <th>Customer</th>
                  <th>Type</th>
                  <th>Amount</th>
                  <th>Risk</th>
                  <th>Diagnosis</th>
                  <th>Selected action</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.id} onClick={() => navigate(`/cases/${c.id}`)} className="clickable-row">
                    <td onClick={(e) => e.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={selected.has(c.id)}
                        onChange={() => toggleOne(c.id)}
                        aria-label={`Select case ${c.id}`}
                      />
                    </td>
                    <td>{c.customerId || "-"}</td>
                    <td>{c.entityType}</td>
                    <td>{formatCurrency(c.amountAtRisk)}</td>
                    <td>
                      {c.riskTier ? (
                        <span className={"risk-badge risk-" + c.riskTier.toLowerCase()}>{c.riskTier}</span>
                      ) : (
                        <span className="muted">not yet run</span>
                      )}
                    </td>
                    <td>{c.diagnosis || <span className="muted">-</span>}</td>
                    <td>{c.selectedAction || <span className="muted">-</span>}</td>
                    <td>{c.currentState}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
