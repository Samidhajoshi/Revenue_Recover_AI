import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import useApi from "../hooks/useApi";
import { getCustomers } from "../api/endpoints";
import { EmptyBanner, ErrorBanner, LoadingBanner } from "../components/StatusBanner";
import { formatCurrency, formatNumber } from "../utils/format";

const SEGMENT_COLORS = ["#16171b", "#6b6d76", "#a5690a", "#1a8a4a", "#c0362c", "#8b8d94"];

function summarize(rows) {
  const total = rows.length;
  const totalLtv = rows.reduce((sum, c) => sum + (c.ltv || 0), 0);
  const avgLtv = total > 0 ? totalLtv / total : 0;
  const optedOut = rows.filter((c) => c.optedOut).length;
  const totalPayments = rows.reduce((sum, c) => sum + (c.totalPayments || 0), 0);
  const successfulPayments = rows.reduce((sum, c) => sum + (c.successfulPayments || 0), 0);
  const failedPayments = rows.reduce((sum, c) => sum + (c.failedPayments || 0), 0);
  const successRate = totalPayments > 0 ? successfulPayments / totalPayments : 0;

  const bySegment = {};
  rows.forEach((c) => {
    const seg = c.segment || "UNSPECIFIED";
    if (!bySegment[seg]) bySegment[seg] = { segment: seg, count: 0, ltv: 0 };
    bySegment[seg].count += 1;
    bySegment[seg].ltv += c.ltv || 0;
  });
  const segments = Object.values(bySegment).sort((a, b) => b.count - a.count);

  const topByLtv = [...rows]
    .sort((a, b) => (b.ltv || 0) - (a.ltv || 0))
    .slice(0, 8)
    .map((c) => ({ name: c.name || c.id, ltv: c.ltv || 0 }));

  return { total, totalLtv, avgLtv, optedOut, totalPayments, successfulPayments, failedPayments, successRate, segments, topByLtv };
}

// Shows exactly what's in the customers table right now - whatever you
// uploaded via customers.csv, plus any customer auto-created (with default
// LTV/segment) the first time a transaction or subscription referenced an
// unknown customer_id.
export default function Customers() {
  const [query, setQuery] = useState("");
  const navigate = useNavigate();
  const { data, loading, error, reload } = useApi(() => getCustomers(), []);

  const rows = useMemo(() => {
    if (!Array.isArray(data)) return [];
    const q = query.trim().toLowerCase();
    if (!q) return data;
    return data.filter(
      (c) =>
        (c.id || "").toLowerCase().includes(q) ||
        (c.name || "").toLowerCase().includes(q) ||
        (c.email || "").toLowerCase().includes(q)
    );
  }, [data, query]);

  const stats = useMemo(() => summarize(rows), [rows]);

  return (
    <div className="page">
      <h1>Customers</h1>

      <div className="card">
        <p className="muted">
          The customer records currently on file — whatever you uploaded via <code>customers.csv</code>, plus
          any customer auto-created (LTV 0, segment STANDARD) the first time a transaction or subscription
          referenced a <code>customer_id</code> that didn't exist yet.
        </p>
        <div className="actions-row" style={{ marginTop: 0 }}>
          <input
            type="text"
            placeholder="Search by customer ID, name, or email..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            style={{ minWidth: "280px" }}
          />
          {Array.isArray(data) && (
            <span className="muted">
              {rows.length} of {data.length} customer{data.length === 1 ? "" : "s"}
            </span>
          )}
        </div>
      </div>

      {!loading && !error && stats.total > 0 && (
        <div className="card">
          <h3>Summary{query ? ` — matching "${query}"` : ""}</h3>
          <p>
            <strong>{formatNumber(stats.total)}</strong> customer{stats.total === 1 ? "" : "s"} on file, worth{" "}
            <strong>{formatCurrency(stats.totalLtv)}</strong> in combined lifetime value (an average of{" "}
            <strong>{formatCurrency(stats.avgLtv)}</strong> each), spread across{" "}
            <strong>{stats.segments.length}</strong> segment{stats.segments.length === 1 ? "" : "s"}
            {stats.segments[0] && (
              <>
                {" "}
                — mostly <strong>{stats.segments[0].segment}</strong> ({formatNumber(stats.segments[0].count)}{" "}
                customers)
              </>
            )}
            .{" "}
            <strong>{formatNumber(stats.optedOut)}</strong> {stats.optedOut === 1 ? "has" : "have"} opted out of
            communications
            {stats.total > 0 && ` (${Math.round((stats.optedOut / stats.total) * 100)}%)`}. Across{" "}
            {formatNumber(stats.totalPayments)} recorded payment{stats.totalPayments === 1 ? "" : "s"}, the
            historical success rate is <strong>{Math.round(stats.successRate * 100)}%</strong> (
            {formatNumber(stats.successfulPayments)} successful, {formatNumber(stats.failedPayments)} failed).
          </p>

          <div className="summary-grid">
            <div className="card summary-card">
              <div className="summary-label">Total customers</div>
              <div className="summary-value">{formatNumber(stats.total)}</div>
            </div>
            <div className="card summary-card">
              <div className="summary-label">Total LTV</div>
              <div className="summary-value">{formatCurrency(stats.totalLtv)}</div>
            </div>
            <div className="card summary-card">
              <div className="summary-label">Average LTV</div>
              <div className="summary-value">{formatCurrency(stats.avgLtv)}</div>
            </div>
            <div className="card summary-card">
              <div className="summary-label">Opted out</div>
              <div className="summary-value">{formatNumber(stats.optedOut)}</div>
            </div>
            <div className="card summary-card">
              <div className="summary-label">Payment success rate</div>
              <div className="summary-value">{Math.round(stats.successRate * 100)}%</div>
            </div>
          </div>

          <div className="chart-grid">
            <div className="card chart-card">
              <h3>Customers by segment</h3>
              <ResponsiveContainer width="100%" height={240}>
                <PieChart>
                  <Pie data={stats.segments} dataKey="count" nameKey="segment" cx="50%" cy="50%" outerRadius={80} label>
                    {stats.segments.map((_, i) => (
                      <Cell key={i} fill={SEGMENT_COLORS[i % SEGMENT_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            </div>

            <div className="card chart-card">
              <h3>Top customers by lifetime value</h3>
              <ResponsiveContainer width="100%" height={240}>
                <BarChart data={stats.topByLtv} layout="vertical" margin={{ left: 24 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" />
                  <YAxis type="category" dataKey="name" width={90} tick={{ fontSize: 11 }} />
                  <Tooltip formatter={(v) => formatCurrency(v)} />
                  <Bar dataKey="ltv" fill="#16171b" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>
      )}

      <div className="card">
        {loading && <LoadingBanner label="Loading customers..." />}
        {error && <ErrorBanner message={error} onRetry={reload} />}
        {!loading && !error && rows.length === 0 && (
          <EmptyBanner label={query ? "No customers match that search." : "No customers imported yet."} />
        )}
        {!loading && !error && rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Email</th>
                  <th>LTV</th>
                  <th>Segment</th>
                  <th>Payments</th>
                  <th>Opted out</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr
                    key={c.id}
                    className="clickable-row"
                    onClick={() => navigate(`/cases?customer=${encodeURIComponent(c.id)}`)}
                    title="View this customer's recovery cases"
                  >
                    <td>{c.id}</td>
                    <td>{c.name || "-"}</td>
                    <td>{c.email || "-"}</td>
                    <td>{formatCurrency(c.ltv)}</td>
                    <td>{c.segment || "-"}</td>
                    <td>
                      {formatNumber(c.successfulPayments)} / {formatNumber(c.totalPayments)} successful
                      {c.failedPayments ? `, ${formatNumber(c.failedPayments)} failed` : ""}
                    </td>
                    <td>{c.optedOut ? "Yes" : "No"}</td>
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
