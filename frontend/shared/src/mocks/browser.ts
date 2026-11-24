import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";
import { mockApi } from "./core";
const scenario = new URLSearchParams(location.search).get("scenario");
if (scenario) mockApi.setScenario(scenario);
window.addEventListener("message", (event) => {
  if (
    event.origin === location.origin &&
    event.data?.type === "cinema:mock-ipn"
  )
    mockApi.completeOnline(Number(event.data.id));
});
export const worker = setupWorker(...handlers);

const payments = new BroadcastChannel("cinema-reference-payments");
payments.addEventListener("message", (event) => {
  if (event.data?.type === "cinema:mock-ipn")
    mockApi.completeOnline(Number(event.data.id));
});
