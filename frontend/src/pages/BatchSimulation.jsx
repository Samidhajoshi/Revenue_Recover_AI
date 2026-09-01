import { useState } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import {
  importGateways,
  importSubscriptions,
  importTransactions,
  runSimulation,
  detectAtRiskCases,
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

  async function handleDetect() {
    setDetecting(true);
    setDetectError(null);
    setDetectResult(null);
    try {
      const res = await detectAtRiskCases();
      setDetectResult(res.data.casesCreated);
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
              {detectResult} new case{detectResult === 1 ? "" : "s"} detected — see them on Overview / Recovery
              Cases before running the batch.
            </span>
          )}
          {detectError && <span className="inline-error">{detectError}</span>}
        </div>
        <p className="muted">
          Detection alone creates cases without executing anything, so Overview shows real "revenue at risk" /
          "active cases" numbers for data you've imported but not yet run.
        </p>
        <button className="btn btn-primary" onClick={handleRun} disabled={running}>
          {running ? "Running 10,000 cases..." : "RUN 10,000 CASES"}
        </button>
        {error && <ErrorBanner message={error} onRetry={handleRun} />}
      </div>

      {result && (
        <>
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

      <DataImport />
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

function DataImport() {
  return (
    <div className="card">
      <h3>Data Import</h3>
      <p className="muted">Upload CSVs to seed transactions, subscriptions, and gateway events.</p>
      <div className="import-grid">
        <ImportRow label="Transactions" onUpload={importTransactions} />
        <ImportRow label="Subscriptions" onUpload={importSubscriptions} />
        <ImportRow label="Gateways" onUpload={importGateways} />
      </div>
    </div>
  );
}

function ImportRow({ label, onUpload }) {
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState(null); // { type: 'success'|'error', message }
  const [busy, setBusy] = useState(false);

  async function handleUpload() {
    if (!file) return;
    setBusy(true);
    setStatus(null);
    try {
      await onUpload(file);
      setStatus({ type: "success", message: "Uploaded successfully." });
    } catch (err) {
      setStatus({
        type: "error",
        message: !err?.response
          ? "Couldn't reach the backend."
          : err?.response?.data?.message || err.message,
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="import-row">
      <div className="import-label">{label}</div>
      <input
        type="file"
        accept=".csv"
        onChange={(e) => setFile(e.target.files?.[0] || null)}
      />
      <button className="btn btn-small" onClick={handleUpload} disabled={!file || busy}>
        {busy ? "Uploading..." : "Upload"}
      </button>
      {status && (
        <span className={status.type === "error" ? "inline-error" : "inline-success"}>
          {status.message}
        </span>
      )}
    </div>
  );
}
