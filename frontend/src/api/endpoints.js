import api from "./client";

// Dashboard
export const getDashboardSummary = () => api.get("/api/dashboard/summary");
export const getRecoveryByType = () => api.get("/api/dashboard/recovery-by-type");
export const getTimeline = () => api.get("/api/dashboard/timeline");

// Recovery cases
export const getRecoveryCases = (params) => api.get("/api/recovery", { params });
export const getRecoveryCase = (id) => api.get(`/api/recovery/${id}`);
export const runRecoveryCase = (id) => api.post(`/api/recovery/${id}/run`);

// Audit
export const getAuditTrail = (recoveryCaseId) => api.get(`/api/audit/${recoveryCaseId}`);

// Counterfactual evaluation ("what-if" recovery analysis)
export const getCounterfactuals = (id) => api.get(`/api/recovery/${id}/counterfactuals`);
export const reEvaluateCounterfactuals = (id) => api.post(`/api/recovery/${id}/evaluate`);

// LLM (recovery messages, promise-to-pay extraction)
export const generateRecoveryMessage = (id, language) =>
  api.post(`/api/agent/${id}/message`, { language });
export const submitPromiseToPay = (id, message) =>
  api.post(`/api/agent/${id}/promise-to-pay`, { message });
export const getPromisesToPay = (id) => api.get(`/api/agent/${id}/promise-to-pay`);

// Simulation
export const runSimulation = () => api.post("/api/simulation/run");
export const detectAtRiskCases = () => api.post("/api/agent/detect");

// Data import
export const importTransactions = (file) => uploadCsv("/api/import/transactions", file);
export const importSubscriptions = (file) => uploadCsv("/api/import/subscriptions", file);
export const importGateways = (file) => uploadCsv("/api/import/gateways", file);

function uploadCsv(url, file) {
  const formData = new FormData();
  formData.append("file", file);
  return api.post(url, formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
}
