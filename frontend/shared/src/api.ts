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

export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface Page<T> {
  data: T[];
  meta: PageMeta;
}
export interface RequestOptions {
  method?: string;
  body?: unknown;
  token?: string;
  signal?: AbortSignal;
}
const baseUrl = (
  import.meta.env.VITE_API_URL ?? "http://localhost:8081/api/v1"
).replace(/\/$/, "");
async function envelope<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort(options.signal?.reason);
  if (options.signal?.aborted) abort();
  else options.signal?.addEventListener("abort", abort, { once: true });
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 15000);
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method: options.method ?? "GET",
      headers,
      credentials: "omit",
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      if (
        response.status === 401 &&
        options.token &&
        typeof window !== "undefined"
      )
        window.dispatchEvent(new Event("cinema:unauthorized"));
      throw new ApiError(
        response.status,
        error.code ?? "REQUEST_FAILED",
        error.message ?? "Thao tác thất bại.",
        error.details ?? {},
      );
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (options.signal?.aborted)
      throw new DOMException("Request cancelled", "AbortError");
    if (timedOut)
      throw new ApiError(
        0,
        "REQUEST_TIMEOUT",
        "Máy chủ phản hồi chậm. Đọc lại trạng thái trước khi gửi lại.",
      );
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "Không kết nối được máy chủ. Kiểm tra kết nối và thử lại.",
    );
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}
export async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const result = await envelope<{ data: T } | undefined>(path, options);
  return result?.data as T;
}
export async function requestPage<T>(
  path: string,
  options: RequestOptions = {},
): Promise<Page<T>> {
  const result = await envelope<
    | Page<T>
    | {
        data:
          | T[]
          | {
              items: T[];
              page: number;
              size: number;
              totalElements: number;
              totalPages: number;
            };
        meta?: PageMeta;
      }
  >(path, options);
  if (result && "meta" in result && result.meta && Array.isArray(result.data)) {
    return result as Page<T>;
  }
  const inner = (
    result as {
      data?: {
        items?: T[];
        page?: number;
        size?: number;
        totalElements?: number;
        totalPages?: number;
      };
    }
  )?.data;
  if (inner && Array.isArray(inner.items)) {
    return {
      data: inner.items,
      meta: {
        page: inner.page ?? 0,
        size: inner.size ?? 20,
        totalElements: inner.totalElements ?? inner.items.length,
        totalPages: inner.totalPages ?? 1,
      },
    };
  }
  if (!Array.isArray((result as { data?: T[] })?.data))
    throw new Error("Máy chủ trả danh sách không đúng định dạng. Vui lòng thử lại.");
  const arr = (result as { data: T[] }).data;
  return {
    data: arr,
    meta: { page: 0, size: arr.length || 20, totalElements: arr.length, totalPages: 1 },
  };
}
export async function requestAll<T>(path: string, options: RequestOptions = {}): Promise<T[]> {
  const rows: T[] = [];
  let page = 0;
  while (true) {
    const url = new URL(path, "http://local");
    url.searchParams.set("page", String(page));
    url.searchParams.set("size", "100");
    const next = await requestPage<T>(url.pathname + url.search, options);
    rows.push(...next.data);
    if (page + 1 >= next.meta.totalPages) return rows;
    if (!next.data.length || next.meta.page !== page)
      throw new Error("Không tải được trang dữ liệu tiếp theo.");
    page++;
  }
}
export function query(
  values: Record<string, string | number | boolean | undefined>,
) {
  const result = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== "") result.set(key, String(value));
  return result.toString();
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
