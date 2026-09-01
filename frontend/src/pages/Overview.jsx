import { useMemo } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import useApi from "../hooks/useApi";
import {
  getDashboardSummary,
  getRecoveryByType,
  getRecoveryCases,
  getTimeline,
} from "../api/endpoints";
import { EmptyBanner, ErrorBanner, LoadingBanner } from "../components/StatusBanner";
import { formatCurrency, formatNumber, formatPercent } from "../utils/format";

const COLORS = ["#16171b", "#6b6d76", "#a5690a", "#c0362c", "#1a8a4a", "#8b8d94"];

function SummaryCard({ label, value, icon }) {
  return (
    <div className="card summary-card">
      {icon && <div className="summary-icon">{icon}</div>}
      <div className="summary-label">{label}</div>
      <div className="summary-value">{value}</div>
    </div>
  );
}

function ProgressRing({ percent, label }) {
  const pct = Math.max(0, Math.min(1, Number.isFinite(percent) ? percent : 0));
  const radius = 46;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - pct);
  return (
    <div>
      <div className="progress-ring-wrap">
        <svg width="120" height="120" viewBox="0 0 120 120">
          <circle cx="60" cy="60" r={radius} fill="none" stroke="var(--surface-sunken)" strokeWidth="12" />
          <circle
            cx="60"
            cy="60"
            r={radius}
            fill="none"
            stroke="var(--ink)"
            strokeWidth="12"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={offset}
            transform="rotate(-90 60 60)"
          />
          <text x="60" y="66" textAnchor="middle" fontSize="22" fontWeight="700" fill="var(--ink)">
            {Math.round(pct * 100)}%
          </text>
        </svg>
      </div>
      <div className="progress-ring-label">{label}</div>
    </div>
  );
}

function ChartCard({ title, children, isEmpty, isError, error, onRetry }) {
  return (
    <div className="card chart-card">
      <h3>{title}</h3>
      {isError ? (
        <ErrorBanner message={error} onRetry={onRetry} />
      ) : isEmpty ? (
        <EmptyBanner />
      ) : (
        <ResponsiveContainer width="100%" height={260}>
          {children}
        </ResponsiveContainer>
      )}
    </div>
  );
}

export default function Overview() {
  const summary = useApi(getDashboardSummary, []);
  const byType = useApi(getRecoveryByType, []);
  const timeline = useApi(getTimeline, []);
  const cases = useApi(() => getRecoveryCases({}), []);

  const byTypeData = useMemo(() => normalizeList(byType.data), [byType.data]);
  const timelineData = useMemo(() => normalizeList(timeline.data), [timeline.data]);

  const interventionOutcomes = useMemo(() => {
    const list = normalizeList(cases.data);
    if (!list.length) return [];
    const counts = {};
    list.forEach((c) => {
      const key = c.currentState || c.status || "UNKNOWN";
      counts[key] = (counts[key] || 0) + 1;
    });
    return Object.entries(counts).map(([name, value]) => ({ name, value }));
  }, [cases.data]);

  const s = summary.data || {};

  return (
    <div className="page">
      <h1>Overview</h1>

      {summary.loading && <LoadingBanner label="Loading summary..." />}
      {summary.error && <ErrorBanner message={summary.error} onRetry={summary.reload} />}
      {!summary.loading && !summary.error && (
        <div className="summary-grid">
          <SummaryCard label="Revenue at Risk" value={formatCurrency(s.revenueAtRisk)} icon="₹" />
          <SummaryCard label="Recoverable Revenue" value={formatCurrency(s.recoverableRevenue)} icon="◔" />
          <SummaryCard label="Recovered Revenue" value={formatCurrency(s.recoveredRevenue)} icon="✓" />
          <SummaryCard label="Recovery Rate" value={formatPercent(s.recoveryRate)} icon="%" />
          <SummaryCard label="Active Cases" value={formatNumber(s.activeCases)} icon="●" />
          <SummaryCard label="Escalations" value={formatNumber(s.escalations)} icon="!" />
        </div>
      )}

      <div className="chart-grid">
        {!summary.loading && !summary.error && (
          <div className="card chart-card" style={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
            <h3 style={{ alignSelf: "flex-start" }}>Recovery progress</h3>
            <ProgressRing percent={s.recoveryRate} label={`${formatNumber(s.totalCases ?? 0)} cases processed`} />
          </div>
        )}
        <ChartCard
          title="Recovery by scenario"
          isEmpty={!byType.loading && !byType.error && byTypeData.length === 0}
          isError={!!byType.error}
          error={byType.error}
          onRetry={byType.reload}
        >
          {byType.loading ? (
            <LoadingBanner />
          ) : (
            <BarChart data={byTypeData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey={pickLabelKey(byTypeData)} />
              <YAxis />
              <Tooltip />
              <Bar dataKey={pickValueKey(byTypeData)} fill="#2563eb" />
            </BarChart>
          )}
        </ChartCard>

        <ChartCard
          title="Intervention outcomes"
          isEmpty={!cases.loading && !cases.error && interventionOutcomes.length === 0}
          isError={!!cases.error}
          error={cases.error}
          onRetry={cases.reload}
        >
          {cases.loading ? (
            <LoadingBanner />
          ) : (
            <PieChart>
              <Pie
                data={interventionOutcomes}
                dataKey="value"
                nameKey="name"
                cx="50%"
                cy="50%"
                outerRadius={90}
                label
              >
                {interventionOutcomes.map((_, i) => (
                  <Cell key={i} fill={COLORS[i % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip />
              <Legend />
            </PieChart>
          )}
        </ChartCard>

        <ChartCard
          title="Revenue recovered over time"
          isEmpty={!timeline.loading && !timeline.error && timelineData.length === 0}
          isError={!!timeline.error}
          error={timeline.error}
          onRetry={timeline.reload}
        >
          {timeline.loading ? (
            <LoadingBanner />
          ) : (
            <LineChart data={timelineData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey={pickLabelKey(timelineData)} />
              <YAxis />
              <Tooltip />
              <Line
                type="monotone"
                dataKey={pickValueKey(timelineData)}
                stroke="#16a34a"
                strokeWidth={2}
              />
            </LineChart>
          )}
        </ChartCard>
      </div>
    </div>
  );
}

// Backend response shapes for chart endpoints aren't pinned down by the spec
// beyond "breakdown"/"series" — normalize a few likely shapes defensively.
function normalizeList(data) {
  if (!data) return [];
  if (Array.isArray(data)) return data;
  if (Array.isArray(data.items)) return data.items;
  if (Array.isArray(data.data)) return data.data;
  // Object map like { PAYMENT: 1000, SUBSCRIPTION: 500 }
  if (typeof data === "object") {
    return Object.entries(data).map(([key, value]) => ({ name: key, value }));
  }
  return [];
}

function pickLabelKey(list) {
  if (!list.length) return "name";
  const keys = Object.keys(list[0]);
  return keys.find((k) => /name|type|scenario|label|date|period|month|day/i.test(k)) || keys[0];
}

function pickValueKey(list) {
  if (!list.length) return "value";
  const keys = Object.keys(list[0]);
  return (
    keys.find((k) => /amount|value|count|revenue|total/i.test(k)) ||
    keys[keys.length - 1]
  );
}
