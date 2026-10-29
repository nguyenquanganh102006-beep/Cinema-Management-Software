import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@cinema/shared";
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App role="STAFF" />
  </StrictMode>,
);
