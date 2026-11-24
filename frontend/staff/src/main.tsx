import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@cinema/shared";
import { bootstrap } from "@cinema/shared/bootstrap";
bootstrap()
  .then(() =>
    createRoot(document.getElementById("root")!).render(
      <StrictMode>
        <App role="STAFF" />
      </StrictMode>,
    ),
  )
  .catch((error) => {
    document.getElementById("root")!.textContent =
      "Không thể khởi động: " + error.message;
  });
