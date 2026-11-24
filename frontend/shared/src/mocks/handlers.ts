import { http } from "msw";
import { mockApi } from "./core";
export const handlers = [
  http.all("*/api/v1/*", ({ request }) => mockApi.handle(request)),
];
