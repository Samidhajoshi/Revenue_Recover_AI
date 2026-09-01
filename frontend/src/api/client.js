import axios from "axios";

// Backend contract: Spring Boot REST API served on http://localhost:8082.
// (Port 8080 is taken by a local Oracle TNS Listener on this machine.)
// We use an explicit baseURL (rather than a Vite dev proxy) so the same
// build works identically in `npm run dev` and a static `npm run build`
// preview without needing a proxy config duplicated in two places.
const api = axios.create({
  baseURL: "http://localhost:8082",
  timeout: 15000,
});

export default api;
