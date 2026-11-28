import { useEffect, useRef, useState, useCallback } from "react";
export function useQuery<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
) {
  const loader = useRef(load);
  loader.current = load;
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<{
    data?: T;
    loading: boolean;
    error?: Error;
  }>({ loading: true });
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setResult({ loading: true });
    loader
      .current(controller.signal)
      .then((data) => {
        if (active) setResult({ data, loading: false });
      })
      .catch((error) => {
        if (active && !controller.signal.aborted)
          setResult({
            loading: false,
            error:
              error instanceof Error
                ? error
                : new Error("Không tải được dữ liệu"),
          });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [key, version]);
  const refetch = useCallback(() => setVersion((v) => v + 1), []);
  return { ...result, refetch };
}
