import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  use: {
    baseURL: "http://localhost:3003",
    trace: "retain-on-failure",
    serviceWorkers: "block",
  },
  projects: [
    ...["chromium", "firefox", "webkit"].map((browser) => ({
      name: browser + "-mock",
      testMatch: /.*\.mock\.spec\.ts/,
      use: {
        ...devices[
          browser === "chromium"
            ? "Desktop Chrome"
            : browser === "firefox"
              ? "Desktop Firefox"
              : "Desktop Safari"
        ],
      },
    })),
    {
      name: "edge-mock",
      testMatch: /.*\.mock\.spec\.ts/,
      use: {
        ...devices["Desktop Edge"],
        channel: process.env.CINEMA_EDGE_EXECUTABLE ? undefined : "msedge",
        launchOptions: { executablePath: process.env.CINEMA_EDGE_EXECUTABLE },
      },
    },
    {
      name: "chromium-live",
      testMatch: /(auth|.*\.live)\.spec\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: ["manager", "staff", "customer"].map((portal, i) => ({
    command: "npm run dev:" + portal,
    url: "http://localhost:" + (3001 + i),
    reuseExistingServer: !process.env.CI,
    env: { VITE_USE_MOCKS: "false" },
  })),
});
