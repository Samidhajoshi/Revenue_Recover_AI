import { useState } from "react";
import { Link } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import {
  importCustomers,
  importGateways,
  importSubscriptions,
  importTransactions,
  runSimulation,
  detectAtRiskCases,
  getDashboardSummary,
  getRecoveryByType,
} from "../api/endpoints";
import { ErrorBanner } from "../components/StatusBanner";
import { formatCurrency, formatNumber, formatPercent } from "../utils/format";

export default function BatchSimulation() {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  const [detecting, setDetecting] = useState(false);
  const [detectResult, setDetectResult] = useState(null);
  const [detectError, setDetectError] = useState(null);

  const [detectionSummary, setDetectionSummary] = useState(null);

  async function refreshDetectionSummary() {
    try {
      const [summaryRes, byTypeRes] = await Promise.all([getDashboardSummary(), getRecoveryByType()]);
      setDetectionSummary({ summary: summaryRes.data, byType: normalizeList(byTypeRes.data) });
    } catch {
      // Non-fatal — the detect/evaluate action itself already succeeded; just skip the visual summary.
    }
  }

  async function handleDetect() {
    setDetecting(true);
    setDetectError(null);
    setDetectResult(null);
    try {
      const res = await detectAtRiskCases();
      setDetectResult(res.data.casesCreated);
      await refreshDetectionSummary();
    } catch (err) {
      setDetectError(!err?.response ? "Couldn't reach the backend." : err?.response?.data?.message || err.message);
    } finally {
      setDetecting(false);
    }
  }

  async function handleRun() {
    setRunning(true);
    setError(null);
    try {
      const res = await runSimulation();
      setResult(res.data);
    } catch (err) {
      setError(
        !err?.response
          ? "Couldn't reach the backend. Is the API running on http://localhost:8082?"
          : err?.response?.data?.message || err.message
      );
    } finally {
      setRunning(false);
    }
  }

  const baseline = result?.baseline || {};
  const counterfactuals = result?.counterfactuals || {};

  const chartData = result
    ? [
        { name: "Baseline", amount: baseline.baselineRecoveredAmount || 0 },
        { name: "RecoverAI", amount: result.recoveredAmount || 0 },
      ]
    : [];

  return (
    <div className="page">
      <h1>Batch Simulation</h1>

      <div className="card">
        <div className="actions-row" style={{ marginTop: 0 }}>
          <button className="btn" onClick={handleDetect} disabled={detecting}>
            {detecting ? "Detecting..." : "Detect at-risk cases"}
          </button>
          {detectResult != null && (
            <span className="inline-success">
              {detectResult > 0 ? (
                <>
                  {detectResult} new case{detectResult === 1 ? "" : "s"} detected — see them on{" "}
                  <Link to="/cases">Recovery Cases</Link>.
                </>
              ) : (
                <>
                  No <em>new</em> at-risk cases — everything currently imported already has a case (check{" "}
                  <Link to="/cases">Recovery Cases</Link> to see them), or the data has no failures to detect.
                </>
              )}
            </span>
          )}
          {detectError && <span className="inline-error">{detectError}</span>}
        </div>
        <p className="muted">
          Detection only creates cases for at-risk data that doesn't already have one — running it twice on the
          same data is expected to report 0 the second time. Detection alone doesn't execute anything, so
          Overview shows real "revenue at risk" / "active cases" numbers for data you've imported but not yet run.
        </p>
        <button className="btn btn-primary" onClick={handleRun} disabled={running}>
          {running ? "Running 10,000 cases..." : "RUN 10,000 CASES"}
        </button>
        {error && <ErrorBanner message={error} onRetry={handleRun} />}
      </div>

      {detectionSummary && <DetectionSummary data={detectionSummary} />}

      {result && (
        <>
          <div className="card">
            <h3>What happened</h3>
            <p>
              RecoverAI processed <strong>{formatNumber(result.casesProcessed)}</strong> cases and recovered{" "}
              <strong>{formatCurrency(result.recoveredAmount)}</strong> — a{" "}
              <strong>{formatPercent(result.recoveryRate)}</strong> recovery rate. {formatNumber(result.escalations)}{" "}
              case{result.escalations === 1 ? "" : "s"} needed human escalation and{" "}
              {formatNumber(result.safelyStoppedCases)} were safely stopped after exhausting policy-allowed
              retries. Against the naive baseline (retry every failure once, no diagnosis, no policy), that's{" "}
              <strong>
                {baseline.incrementalRecovery >= 0 ? "+" : ""}
                {formatCurrency(baseline.incrementalRecovery)}
              </strong>{" "}
              more recovered — a <strong>{formatNumber(baseline.improvementPercent)}%</strong> improvement.
            </p>
          </div>

          <div className="summary-grid">
            <Stat label="Cases processed" value={formatNumber(result.casesProcessed)} />
            <Stat label="Interventions" value={formatNumber(result.interventions)} />
            <Stat label="Recovered amount" value={formatCurrency(result.recoveredAmount)} />
            <Stat label="Recovery rate" value={formatPercent(result.recoveryRate)} />
            <Stat label="Escalations" value={formatNumber(result.escalations)} />
            <Stat label="Safely stopped cases" value={formatNumber(result.safelyStoppedCases)} />
            <Stat label="Baseline recovered" value={formatCurrency(baseline.baselineRecoveredAmount)} />
            <Stat label="Incremental recovery" value={formatCurrency(baseline.incrementalRecovery)} />
            <Stat label="Improvement vs baseline" value={`${formatNumber(baseline.improvementPercent)}%`} />
          </div>

          <div className="card chart-card">
            <h3>Baseline vs RecoverAI — recovered amount</h3>
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="name" />
                <YAxis />
                <Tooltip formatter={(v) => formatCurrency(v)} />
                <Bar dataKey="amount" fill="#16171b" />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="card">
            <h3>Counterfactual evaluation</h3>
            <p className="muted">Every candidate action considered across the batch, not just the ones executed.</p>
            <div className="summary-grid">
              <Stat label="Actions evaluated" value={formatNumber(counterfactuals.candidateActionsEvaluated)} />
              <Stat label="Policy-blocked" value={formatNumber(counterfactuals.policyBlockedActions)} />
              <Stat label="Policy-compliant" value={formatNumber(counterfactuals.policyCompliantActions)} />
              <Stat label="Avg actions / case" value={formatNumber(counterfactuals.averageActionsEvaluatedPerCase)} />
              <Stat label="Avg interventions / recovered case" value={formatNumber(counterfactuals.averageInterventionsPerRecoveredCase)} />
              <Stat label="Expected recovery (selected actions)" value={formatCurrency(counterfactuals.totalExpectedRecovery)} />
              <Stat label="Actual recovery" value={formatCurrency(counterfactuals.totalActualRecovery)} />
              <Stat
                label="Expected vs actual"
                value={
                  (counterfactuals.expectedVsActualDifference >= 0 ? "+" : "") +
                  formatCurrency(counterfactuals.expectedVsActualDifference)
                }
              />
            </div>
            {counterfactuals.selectedActionsByType && (
              <>
                <h3>Selected actions by type</h3>
                <ul className="plain-list">
                  {Object.entries(counterfactuals.selectedActionsByType).map(([action, count]) => (
                    <li key={action}>
                      {action}: {formatNumber(count)}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </>
      )}

      <DataImport onDetected={refreshDetectionSummary} />
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div className="card summary-card">
      <div className="summary-label">{label}</div>
      <div className="summary-value">{value}</div>
    </div>
  );
}

const PIE_COLORS = ["#16171b", "#6b6d76", "#a5690a", "#1a8a4a"];

function DetectionSummary({ data }) {
  const { summary, byType } = data;
  const labelKey = byType.length ? Object.keys(byType[0]).find((k) => /type|name/i.test(k)) || "entityType" : "entityType";
  const amountKey = byType.length ? Object.keys(byType[0]).find((k) => /amountAtRisk|amount/i.test(k)) : "amountAtRisk";

  return (
    <div className="card">
      <h3>What's currently at risk</h3>
      <p>
        <strong>{formatNumber(summary.activeCases)}</strong> case{summary.activeCases === 1 ? "" : "s"} currently
        need attention, totaling <strong>{formatCurrency(summary.revenueAtRisk)}</strong> at risk (an estimated{" "}
        <strong>{formatCurrency(summary.recoverableRevenue)}</strong> of that is realistically recoverable).
        {summary.escalations > 0 && (
          <>
            {" "}
            {formatNumber(summary.escalations)} case{summary.escalations === 1 ? "" : "s"} already sit escalated.
          </>
        )}
      </p>
      <div className="summary-grid">
        <Stat label="Active cases" value={formatNumber(summary.activeCases)} />
        <Stat label="Revenue at risk" value={formatCurrency(summary.revenueAtRisk)} />
        <Stat label="Recoverable (est.)" value={formatCurrency(summary.recoverableRevenue)} />
        <Stat label="Total cases on record" value={formatNumber(summary.totalCases)} />
      </div>
      {byType.length > 0 && (
        <ResponsiveContainer width="100%" height={220}>
          <PieChart>
            <Pie data={byType} dataKey={amountKey} nameKey={labelKey} cx="50%" cy="50%" outerRadius={80} label>
              {byType.map((_, i) => (
                <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
              ))}
            </Pie>
            <Tooltip formatter={(v) => formatCurrency(v)} />
          </PieChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}

function normalizeList(data) {
  if (!data) return [];
  if (Array.isArray(data)) return data;
  if (Array.isArray(data.items)) return data.items;
  if (Array.isArray(data.data)) return data.data;
  return [];
}

const CSV_SPECS = [
  {
    entity: "Customers",
    header: "id,name,email,phone,ltv,total_payments,successful_payments,failed_payments,segment,opted_out",
    fields: [
      ["id", "required", "unique string, e.g. CUST0001"],
      ["name", "optional", "defaults to id"],
      ["email", "optional", "defaults to <id>@example.com"],
      ["phone", "optional", ""],
      ["ltv", "optional", "number, defaults to 0"],
      ["total_payments / successful_payments / failed_payments", "optional", "integers, default 0"],
      ["segment", "optional", "free text, e.g. STANDARD, defaults to STANDARD"],
      ["opted_out", "optional", "true or false, defaults to false"],
    ],
  },
  {
    entity: "Transactions",
    header: "id,customer_id,amount,currency,payment_method,gateway,bank,region,status,failure_reason,attempt_number,created_at",
    fields: [
      ["id", "required", "unique string, e.g. TXN0001"],
      ["customer_id", "required", "auto-creates the customer if it doesn't exist yet"],
      ["amount", "required", "number"],
      ["currency", "optional", "defaults to INR"],
      ["payment_method", "optional", "e.g. UPI, CARD, NETBANKING, WALLET"],
      ["gateway", "optional", "must match a gateway's name (e.g. Gateway A) for root-cause diagnosis"],
      ["bank / region", "optional", ""],
      ["status", "optional", "SUCCESS or FAILED, defaults to FAILED"],
      ["failure_reason", "optional", "TEMPORARY_DECLINE, INSUFFICIENT_FUNDS, EXPIRED_CARD, GATEWAY_FAILURE, ABANDONED_CHECKOUT, REPEATED_FAILURE, AMBIGUOUS, DISPUTED"],
      ["attempt_number", "optional", "integer, defaults to 1"],
      ["created_at", "optional", "ISO datetime or date"],
    ],
  },
  {
    entity: "Subscriptions",
    header: "id,customer_id,amount,billing_cycle,next_payment_date,status,payment_method,failure_reason,retry_count",
    fields: [
      ["id", "required", "unique string, e.g. SUB0001"],
      ["customer_id", "required", "auto-creates the customer if it doesn't exist yet"],
      ["amount", "required", "number"],
      ["billing_cycle", "optional", "e.g. MONTHLY, QUARTERLY, ANNUAL"],
      ["next_payment_date", "optional", "ISO date, YYYY-MM-DD"],
      ["status", "optional", "ACTIVE or FAILED, defaults to FAILED"],
      ["payment_method", "optional", ""],
      ["failure_reason", "optional", "same set as transactions"],
      ["retry_count", "optional", "integer, defaults to 0"],
    ],
  },
  {
    entity: "Gateways",
    header: "id,name,success_rate,failure_rate,baseline_failure_rate,status,cost_per_transaction",
    fields: [
      ["id", "required", "e.g. GW1"],
      ["name", "optional", "defaults to id — must match the gateway column in your transactions CSV"],
      ["success_rate / failure_rate / baseline_failure_rate", "optional", "0-1 fractions or 0-100 percentages, auto-normalized"],
      ["status", "optional", "HEALTHY or DEGRADED"],
      ["cost_per_transaction", "optional", ""],
    ],
  },
];

function DataImport({ onDetected }) {
  return (
    <div className="card">
      <h3>Data Import</h3>
      <p className="muted">
        Upload CSVs to seed customers, transactions, subscriptions, and gateway events. After a row uploads,
        click "Evaluate" to detect at-risk cases from it — upload alone only stores the data, it doesn't
        create cases on its own.
      </p>
      <div className="import-grid">
        <ImportRow label="Customers" onUpload={importCustomers} onDetected={onDetected} />
        <ImportRow label="Transactions" onUpload={importTransactions} onDetected={onDetected} />
        <ImportRow label="Subscriptions" onUpload={importSubscriptions} onDetected={onDetected} />
        <ImportRow label="Gateways" onUpload={importGateways} onDetected={onDetected} />
      </div>

      <h3 style={{ marginTop: "1.5rem" }}>File format</h3>
      <p className="muted">
        Each file needs a header row with these column names (order doesn't matter, and any extra columns are
        ignored). Column order below matches the header row exactly.
      </p>
      {CSV_SPECS.map((spec) => (
        <details key={spec.entity} style={{ marginBottom: "0.75rem" }}>
          <summary style={{ cursor: "pointer", fontWeight: 600 }}>{spec.entity}</summary>
          <pre
            style={{
              background: "var(--surface-sunken)",
              padding: "0.5rem 0.75rem",
              borderRadius: "8px",
              fontSize: "0.78rem",
              overflowX: "auto",
              margin: "0.5rem 0",
            }}
          >
            {spec.header}
          </pre>
          <table className="data-table">
            <thead>
              <tr>
                <th>Field</th>
                <th>Required</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              {spec.fields.map(([field, required, notes]) => (
                <tr key={field}>
                  <td>{field}</td>
                  <td>{required === "required" ? <strong>required</strong> : <span className="muted">optional</span>}</td>
                  <td className="muted">{notes}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      ))}
    </div>
  );
}

function ImportRow({ label, onUpload, onDetected }) {
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState(null); // { type: 'success'|'error', message }
  const [busy, setBusy] = useState(false);
  const [uploaded, setUploaded] = useState(false);
  const [evaluating, setEvaluating] = useState(false);
  const [evalResult, setEvalResult] = useState(null);
  // Bumping this remounts the <input>, clearing its native value - without this, re-selecting
  // the exact same file (e.g. re-testing the same CSV) fires no change event at all, since the
  // browser only fires onChange when the picked path actually differs from the current value.
  const [inputKey, setInputKey] = useState(0);

  async function handleUpload() {
    if (!file) return;
    setBusy(true);
    setStatus(null);
    setEvalResult(null);
    try {
      const res = await onUpload(file);
      const count = res?.data?.imported;
      setStatus({
        type: "success",
        message: count != null ? `Uploaded ${count} row${count === 1 ? "" : "s"}.` : "Uploaded successfully.",
      });
      setUploaded(true);
    } catch (err) {
      setStatus({
        type: "error",
        message: !err?.response
          ? "Couldn't reach the backend."
          : err?.response?.data?.message || err.message,
      });
    } finally {
      setBusy(false);
      setFile(null);
      setInputKey((k) => k + 1);
    }
  }

  async function handleEvaluate() {
    setEvaluating(true);
    setEvalResult(null);
    try {
      const res = await detectAtRiskCases();
      setEvalResult(res.data.casesCreated);
      await onDetected?.();
    } catch (err) {
      setEvalResult(null);
      setStatus({
        type: "error",
        message: !err?.response ? "Couldn't reach the backend." : err?.response?.data?.message || err.message,
      });
    } finally {
      setEvaluating(false);
    }
  }

  return (
    <div className="import-row">
      <div className="import-label">{label}</div>
      <input
        key={inputKey}
        type="file"
        accept=".csv"
        onChange={(e) => {
          setFile(e.target.files?.[0] || null);
          setUploaded(false);
          setEvalResult(null);
        }}
      />
      <button className="btn btn-small" onClick={handleUpload} disabled={!file || busy}>
        {busy ? "Uploading..." : "Upload"}
      </button>
      {uploaded && (
        <button className="btn btn-small" onClick={handleEvaluate} disabled={evaluating}>
          {evaluating ? "Evaluating..." : "Evaluate"}
        </button>
      )}
      {status && (
        <span className={status.type === "error" ? "inline-error" : "inline-success"}>{status.message}</span>
      )}
      {evalResult != null && (
        <span className="inline-success">
          {evalResult > 0 ? (
            <>
              {evalResult} new case{evalResult === 1 ? "" : "s"} detected — see{" "}
              <Link to="/cases">Recovery Cases</Link>.
            </>
          ) : (
            "No new at-risk cases from this data."
          )}
        </span>
      )}
    </div>
  );
}
