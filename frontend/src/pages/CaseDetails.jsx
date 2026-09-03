import { Link, useNavigate, useParams } from "react-router-dom";
import useApi from "../hooks/useApi";
import {
  getAuditTrail,
  getRecoveryCase,
  runRecoveryCase,
  generateRecoveryMessage,
  submitPromiseToPay,
  getCounterfactuals,
  reEvaluateCounterfactuals,
} from "../api/endpoints";
import { EmptyBanner, ErrorBanner, LoadingBanner } from "../components/StatusBanner";
import { formatCurrency, formatDate, formatPercent } from "../utils/format";
import { describeApiError } from "../api/client";
import { useState } from "react";

export default function CaseDetails() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState(null);

  const detail = useApi(() => getRecoveryCase(id), [id]);
  const audit = useApi(() => getAuditTrail(id), [id]);
  const cf = useApi(() => getCounterfactuals(id), [id]);
  const [whatIf, setWhatIf] = useState(null);
  const [cfReevaluating, setCfReevaluating] = useState(false);

  const [language, setLanguage] = useState("hinglish");
  const [messageResult, setMessageResult] = useState(null);
  const [messageLoading, setMessageLoading] = useState(false);
  const [messageError, setMessageError] = useState(null);

  const [ptpText, setPtpText] = useState("");
  const [ptpResult, setPtpResult] = useState(null);
  const [ptpLoading, setPtpLoading] = useState(false);
  const [ptpError, setPtpError] = useState(null);

  const d = detail.data || {};

  async function handleGenerateMessage() {
    setMessageLoading(true);
    setMessageError(null);
    try {
      const res = await generateRecoveryMessage(id, language);
      setMessageResult(res.data);
    } catch (err) {
      setMessageError(describeApiError(err));
    } finally {
      setMessageLoading(false);
    }
  }

  async function handleSubmitPromise() {
    if (!ptpText.trim()) return;
    setPtpLoading(true);
    setPtpError(null);
    try {
      const res = await submitPromiseToPay(id, ptpText);
      setPtpResult(res.data);
      audit.reload();
    } catch (err) {
      setPtpError(describeApiError(err));
    } finally {
      setPtpLoading(false);
    }
  }

  const auditEvents = asList(audit.data);

  async function handleRun() {
    setRunning(true);
    setRunError(null);
    try {
      await runRecoveryCase(id);
      detail.reload();
      audit.reload();
      cf.reload();
    } catch (err) {
      setRunError(describeApiError(err));
    } finally {
      setRunning(false);
    }
  }

  async function handleReevaluate() {
    setCfReevaluating(true);
    setWhatIf(null);
    try {
      await reEvaluateCounterfactuals(id);
      cf.reload();
      audit.reload();
    } finally {
      setCfReevaluating(false);
    }
  }

  const cfEvaluations = asList(cf.data?.evaluations);
  const selectedEval = cfEvaluations.find((e) => e.selected);
  const shownEval = whatIf || selectedEval;

  const escalationEvent = [...auditEvents].reverse().find((e) => e.newState === "ESCALATED");
  const blockedActions = cfEvaluations.filter((e) => !e.allowed);
  const bestBlockedByRecovery = [...blockedActions].sort(
    (a, b) => (b.expectedRecovery ?? 0) - (a.expectedRecovery ?? 0)
  )[0];

  return (
    <div className="page">
      <button className="btn btn-small" onClick={() => navigate(-1)}>
        &larr; Back
      </button>
      <h1>Case {id}</h1>

      {detail.loading && <LoadingBanner label="Loading case..." />}
      {detail.error && <ErrorBanner message={detail.error} onRetry={detail.reload} />}

      {!detail.loading && !detail.error && (
        <>
          <div className="card">
            <div className="detail-grid">
              <Detail label="Customer" value={d.customerId} />
              <Detail label="Amount at risk" value={formatCurrency(d.amountAtRisk)} />
              <Detail label="Risk score" value={d.riskScore} />
              <Detail label="Risk tier" value={d.riskTier} />
              <Detail label="Recovery probability" value={formatPercent(d.recoveryProbability)} />
              <Detail label="Diagnosis" value={d.diagnosis} />
              <Detail label="Agent decision" value={d.selectedAction} />
              <Detail label="Current state" value={d.currentState} />
              <Detail
                label="Final outcome"
                value={
                  d.currentState === "RECOVERED"
                    ? `Recovered ${formatCurrency(d.recoveredAmount)}`
                    : d.currentState
                }
              />
            </div>
            <div className="actions-row">
              <button className="btn" onClick={handleRun} disabled={running}>
                {running ? "Running..." : "Run this case"}
              </button>
              {runError && <span className="inline-error">{runError}</span>}
            </div>
          </div>

          {d.currentState === "ESCALATED" && (
            <div className="card" style={{ borderLeft: "3px solid var(--risk-high, #c0362c)" }}>
              <h3 style={{ marginTop: 0 }}>Escalation summary</h3>
              <p>
                <strong>{d.customerId}</strong>'s {(d.entityType || "case").toLowerCase()} case, worth{" "}
                <strong>{formatCurrency(d.amountAtRisk)}</strong>, was handed to a human because{" "}
                {escalationEvent?.reason
                  ? <>{escalationEvent.reason.charAt(0).toLowerCase() + escalationEvent.reason.slice(1)}</>
                  : "no policy-compliant automated action was left to try"}
                .
              </p>
              {d.diagnosis && (
                <p>
                  <strong>Likely root cause:</strong> {d.diagnosis}
                </p>
              )}
              {bestBlockedByRecovery && (
                <p>
                  <strong>Best blocked alternative:</strong> {bestBlockedByRecovery.action} was estimated to
                  recover {formatCurrency(bestBlockedByRecovery.expectedRecovery)} (
                  {formatPercent(bestBlockedByRecovery.probability)} probability) but policy blocked it —{" "}
                  {bestBlockedByRecovery.policyReason}. A human may still be able to take this action manually
                  if the situation warrants it.
                </p>
              )}
              <p className="muted">
                Risk tier <strong>{d.riskTier || "-"}</strong> · risk score{" "}
                <strong>{d.riskScore ?? "-"}</strong> · model recovery probability{" "}
                <strong>{formatPercent(d.recoveryProbability)}</strong>.
              </p>
            </div>
          )}

          <div className="card">
            <div className="actions-row" style={{ marginTop: 0, justifyContent: "space-between" }}>
              <h3 style={{ margin: 0 }}>What-if recovery analysis</h3>
              <button className="btn btn-small" onClick={handleReevaluate} disabled={cfReevaluating}>
                {cfReevaluating ? "Re-evaluating..." : "Re-evaluate"}
              </button>
            </div>
            <p className="muted">
              Every candidate action is estimated and policy-checked before any of them execute. Only the
              selected action ever runs — clicking a row below shows the hypothetical outcome, it never executes it.
            </p>

            {cf.loading && <LoadingBanner label="Evaluating candidate actions..." />}
            {cf.error && <ErrorBanner message={cf.error} onRetry={cf.reload} />}

            {!cf.loading && !cf.error && cfEvaluations.length === 0 && (
              <EmptyBanner label="No candidate actions evaluated yet." />
            )}

            {!cf.loading && !cf.error && cfEvaluations.length > 0 && (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Action</th>
                        <th>Probability</th>
                        <th>Expected recovery</th>
                        <th>Policy</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      {cfEvaluations.map((e) => (
                        <tr
                          key={e.action}
                          className="clickable-row"
                          onClick={() => setWhatIf(e)}
                          style={e.selected ? { background: "var(--surface-sunk)" } : undefined}
                        >
                          <td>
                            {e.action}
                            {e.selected && <span className="risk-badge risk-low" style={{ marginLeft: "0.5rem" }}>SELECTED</span>}
                          </td>
                          <td>{formatPercent(e.probability)}</td>
                          <td>{formatCurrency(e.expectedRecovery)}</td>
                          <td>
                            {e.allowed ? (
                              <span className="risk-badge risk-low">Allowed</span>
                            ) : (
                              <span className="risk-badge risk-high" title={e.policyReason}>Blocked</span>
                            )}
                          </td>
                          <td className="muted">{e.allowed ? "" : e.policyReason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {shownEval && (
                  <div style={{ marginTop: "1.25rem" }}>
                    <h3>{whatIf && !whatIf.selected ? `If ${whatIf.action} had been selected` : "Why this action?"}</h3>
                    <div className="detail-grid">
                      <Detail label="Action" value={shownEval.action} />
                      <Detail label="Recovery probability" value={formatPercent(shownEval.probability)} />
                      <Detail label="Expected recovery" value={formatCurrency(shownEval.expectedRecovery)} />
                      <Detail label="Policy" value={shownEval.allowed ? "Allowed" : `Blocked — ${shownEval.policyReason}`} />
                      {selectedEval && whatIf && whatIf.action !== selectedEval.action && (
                        <Detail
                          label={`Difference from ${selectedEval.action}`}
                          value={formatCurrency((shownEval.expectedRecovery ?? 0) - (selectedEval.expectedRecovery ?? 0))}
                        />
                      )}
                      {shownEval.selected && shownEval.actualRecovery != null && (
                        <>
                          <Detail label="Actual recovery" value={formatCurrency(shownEval.actualRecovery)} />
                          <Detail
                            label="Prediction error"
                            value={
                              (shownEval.predictionError >= 0 ? "+" : "") + formatCurrency(shownEval.predictionError)
                            }
                          />
                        </>
                      )}
                    </div>
                    {whatIf && (
                      <button className="btn btn-small" style={{ marginTop: "0.75rem" }} onClick={() => setWhatIf(null)}>
                        Back to selected action
                      </button>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

        </>
      )}

      <div className="card">
        <h3>Recovery message (LLM)</h3>
        <div className="actions-row">
          <select value={language} onChange={(e) => setLanguage(e.target.value)}>
            <option value="hinglish">Hinglish</option>
            <option value="english">English</option>
          </select>
          <button className="btn" onClick={handleGenerateMessage} disabled={messageLoading}>
            {messageLoading ? "Generating..." : "Generate message"}
          </button>
          {messageError && <span className="inline-error">{messageError}</span>}
        </div>
        {messageResult && (
          <div className="detail-item">
            <div className="detail-label">
              {messageResult.llmUsed ? "Generated by LLM" : "Fallback template (LLM unavailable)"}
            </div>
            <div className="detail-value">{messageResult.message}</div>
          </div>
        )}
      </div>

      <div className="card">
        <h3>Promise-to-pay extraction (LLM)</h3>
        <p>Paste a customer reply to extract a structured commitment. This only records the intent — it never triggers a recovery action.</p>
        <div className="actions-row">
          <input
            type="text"
            style={{ flex: 1 }}
            placeholder='e.g. "I will pay Rs 5000 by next Friday"'
            value={ptpText}
            onChange={(e) => setPtpText(e.target.value)}
          />
          <button className="btn" onClick={handleSubmitPromise} disabled={ptpLoading}>
            {ptpLoading ? "Extracting..." : "Extract"}
          </button>
          {ptpError && <span className="inline-error">{ptpError}</span>}
        </div>
        {ptpResult && (
          <div className="detail-grid">
            <Detail label="Intent" value={ptpResult.intent} />
            <Detail label="Amount" value={ptpResult.amount != null ? formatCurrency(ptpResult.amount) : "-"} />
            <Detail label="Promised date" value={ptpResult.promisedDate} />
            <Detail label="Valid" value={ptpResult.valid ? "Yes" : "No"} />
            <Detail label="Reason" value={ptpResult.validationReason} />
          </div>
        )}
      </div>

      <div className="card">
        <h3>Audit timeline</h3>
        {audit.loading && <LoadingBanner label="Loading audit trail..." />}
        {audit.error && <ErrorBanner message={audit.error} onRetry={audit.reload} />}
        {!audit.loading && !audit.error && auditEvents.length === 0 && (
          <EmptyBanner label="No audit events yet." />
        )}
        {!audit.loading && !audit.error && auditEvents.length > 0 && (
          <ol className="timeline">
            {auditEvents.map((e, i) => (
              <li key={i} className="timeline-item">
                <div className="timeline-marker" />
                <div className="timeline-content">
                  <div className="timeline-header">
                    <strong>{e.eventType}</strong>
                    <span className="timeline-time">{formatDate(e.timestamp)}</span>
                  </div>
                  <div className="timeline-transition">
                    {e.previousState || "—"} &rarr; {e.newState || "—"}
                  </div>
                  {e.actor && <div>Actor: {e.actor}</div>}
                  {e.reason && <div>Reason: {e.reason}</div>}
                  {e.metadata && (
                    <pre className="timeline-metadata">
                      {typeof e.metadata === "string" ? e.metadata : JSON.stringify(e.metadata)}
                    </pre>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>

      <Link to="/cases" className="btn btn-small">
        Back to all cases
      </Link>
    </div>
  );
}

function Detail({ label, value }) {
  return (
    <div className="detail-item">
      <div className="detail-label">{label}</div>
      <div className="detail-value">{value ?? "-"}</div>
    </div>
  );
}

function asList(v) {
  if (!v) return [];
  if (Array.isArray(v)) return v;
  if (Array.isArray(v.items)) return v.items;
  if (Array.isArray(v.data)) return v.data;
  return [];
}
