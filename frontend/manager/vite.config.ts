import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
export default defineConfig(({ command }) => ({
  publicDir:
    command === "serve"
      ? fileURLToPath(new URL("../shared/public", import.meta.url))
      : false,
}));
