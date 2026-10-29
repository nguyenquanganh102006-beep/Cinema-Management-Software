export type Role = "MANAGER" | "STAFF" | "CUSTOMER";
export interface User {
  id: number;
  username: string;
  fullName: string;
  email: string;
  phone: string;
  role: Role;
}
export interface Session {
  accessToken: string;
  expiresIn: number;
  user: User;
}
export interface RegisterInput {
  username: string;
  fullName: string;
  email: string;
  phone: string;
  password: string;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: Record<string, string> = {},
  ) {
    super(message);
  }
}

const baseUrl = (
  import.meta.env.VITE_API_URL ?? "http://localhost:8080/api/v1"
).replace(/\/$/, "");
export async function request<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    token?: string;
    signal?: AbortSignal;
  } = {},
): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method: options.method ?? "GET",
      headers,
      credentials: "omit",
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? controller.signal,
    });
  } catch {
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "Không kết nối được máy chủ. Kiểm tra kết nối và thử lại.",
    );
  } finally {
    clearTimeout(timeout);
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(
      response.status,
      error.code ?? "REQUEST_FAILED",
      error.message ?? "Thao tác thất bại.",
      error.details ?? {},
    );
  }
  if (response.status === 204) return undefined as T;
  const payload: { data: T } = await response.json();
  return payload.data;
}
export const authApi = {
  login: (username: string, password: string) =>
    request<Session>("/auth/login", {
      method: "POST",
      body: { username, password },
    }),
  register: (body: RegisterInput) =>
    request<User>("/auth/register", { method: "POST", body }),
  me: (token: string) => request<User>("/auth/me", { token }),
  logout: (token: string) =>
    request<void>("/auth/logout", { method: "POST", token }),
  transfer: (token: string) =>
    request<{ redirectUrl: string }>("/auth/transfers", {
      method: "POST",
      token,
    }),
  consumeTransfer: (code: string, role: Role) =>
    request<Session>("/auth/transfers/consume", {
      method: "POST",
      body: { code, role },
    }),
};
