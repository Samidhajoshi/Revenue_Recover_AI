import axios from "axios";

// Backend contract: Spring Boot REST API. Defaults to localhost:8082 for
// local dev (port 8080 is taken by a local Oracle TNS Listener on this
// machine); set VITE_API_BASE_URL at build time (e.g. on Vercel, pointed at
// the Render backend URL) to target a deployed backend instead. We use an
// explicit baseURL (rather than a Vite dev proxy) so the same build works
// identically in `npm run dev` and a static `npm run build` preview.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8082",
  // A fresh full-size run (10k transactions / ~3.7k cases) does one /risk call
  // per case sequentially and genuinely takes ~3 minutes end to end - a short
  // timeout here reads to the user as "can't reach the backend" when it's
  // actually just still working.
  timeout: 300000,
});

// Distinguishes a real connection failure from a request that's just still
// running - detect/run-batch can legitimately take well over a minute on a
// full dataset, and that should never read as "backend is down".
export function describeApiError(err) {
  if (err?.response) return err.response.data?.message || err.message;
  if (err?.code === "ECONNABORTED") {
    return "Still working - this can take a minute or more on a large dataset. It'll finish in the background; check back shortly.";
  }
  return "Couldn't reach the backend. Is the API running on http://localhost:8082?";
}

export default api;
