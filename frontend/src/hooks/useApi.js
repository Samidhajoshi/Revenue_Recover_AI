import { useCallback, useEffect, useState } from "react";

// Generic fetch hook: runs `fetcher` (a function returning an axios promise),
// tracks loading/error/data, and never lets a network failure crash the page.
// `deps` re-triggers the fetch (e.g. when filters change).
export default function useApi(fetcher, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const reload = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcher()
      .then((res) => {
        if (!cancelled) setData(res.data);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(
            err?.code === "ERR_NETWORK" || !err?.response
              ? "Couldn't reach the backend. Is the API running on http://localhost:8082?"
              : err?.response?.data?.message || err.message || "Something went wrong."
          );
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => reload(), [reload]);

  return { data, loading, error, reload };
}
