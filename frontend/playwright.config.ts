import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  timeout: 30000,
  use: { baseURL: "http://localhost:3003", trace: "retain-on-failure" },
  webServer: [
    {
      command: "npm run dev:manager",
      url: "http://localhost:3001",
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npm run dev:staff",
      url: "http://localhost:3002",
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npm run dev:customer",
      url: "http://localhost:3003",
      reuseExistingServer: !process.env.CI,
    },
  ],
});
