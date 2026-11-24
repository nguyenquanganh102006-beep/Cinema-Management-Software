import { useEffect, useState } from "react";
export function useServerCountdown(serverTime?: string, until?: string) {
  const [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (!serverTime || !until) {
      setRemaining(0);
      return;
    }
    const start = performance.now();
    const initial = new Date(until).getTime() - new Date(serverTime).getTime();
    const tick = () =>
      setRemaining(
        Math.max(0, Math.ceil((initial - (performance.now() - start)) / 1000)),
      );
    tick();
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  }, [serverTime, until]);
  return remaining;
}
