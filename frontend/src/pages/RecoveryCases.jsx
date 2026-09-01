import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import useApi from "../hooks/useApi";
import { getRecoveryCases } from "../api/endpoints";
import { EmptyBanner, ErrorBanner, LoadingBanner } from "../components/StatusBanner";
import { formatCurrency } from "../utils/format";

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

export default function RecoveryCases() {
  const [active, setActive] = useState("ALL");
  const navigate = useNavigate();

  const filter = FILTERS.find((f) => f.key === active) || FILTERS[0];
  const { data, loading, error, reload } = useApi(
    () => getRecoveryCases(filter.params),
    [active]
  );

  const rows = useMemo(() => {
    if (!data) return [];
    if (Array.isArray(data)) return data;
    if (Array.isArray(data.items)) return data.items;
    if (Array.isArray(data.data)) return data.data;
    return [];
  }, [data]);

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
        {loading && <LoadingBanner label="Loading cases..." />}
        {error && <ErrorBanner message={error} onRetry={reload} />}
        {!loading && !error && rows.length === 0 && <EmptyBanner label="No cases found." />}
        {!loading && !error && rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
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
                    <td>{c.customerName}</td>
                    <td>{c.entityType}</td>
                    <td>{formatCurrency(c.amount)}</td>
                    <td>
                      <span className={"risk-badge risk-" + (c.riskTier || "").toLowerCase()}>
                        {c.riskTier || c.riskScore}
                      </span>
                    </td>
                    <td>{c.diagnosis}</td>
                    <td>{c.selectedAction}</td>
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
