import { defineConfig } from "vitest/config";
export default defineConfig({
  test: {
    include: ["{shared,manager,staff,customer}/src/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    setupFiles: ["./shared/src/test/setup.ts"],
  },
});
