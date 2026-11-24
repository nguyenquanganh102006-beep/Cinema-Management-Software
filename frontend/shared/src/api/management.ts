import { request, requestPage, query } from "../api";
import type { Restock } from "../types";

type AnyRecord = Record<string, unknown>;
export class PartialSaveError extends Error {
  constructor(public retryStatus: () => Promise<unknown>, message: string) {
    super(`Thông tin đã được lưu, đổi trạng thái chưa thành công: ${message}`);
  }
}
async function saveStatus(path: string, active: boolean, token: string) {
  const retry = () => request<AnyRecord>(path, { method: "PATCH", body: { active }, token });
  try { return await retry(); }
  catch (error) { throw new PartialSaveError(retry, (error as Error).message); }
}

function normalizeItem<T>(resource: string, row: AnyRecord): T {
  const out: AnyRecord = { ...row };
  if (resource === "auditoriums") {
    out.auditoriumType = row.auditoriumType ?? row.type ?? "STANDARD_2D";
    out.type = row.type ?? row.auditoriumType ?? "STANDARD_2D";
  } else if (resource.endsWith("/seats") || resource === "seats") {
    out.seatId = Number(row.seatId ?? row.id ?? 0);
    out.id = Number(row.id ?? row.seatId ?? 0);
    out.seatType = row.seatType ?? row.type ?? "STANDARD";
    out.type = row.type ?? row.seatType ?? "STANDARD";
  } else if (resource === "pricing-rules") {
    out.ruleType = row.ruleType ?? row.type ?? "";
    out.type = row.type ?? row.ruleType ?? "";
    out.amount = Number(row.amount ?? 0);
  } else if (resource === "inventory") {
    out.itemName = row.itemName ?? row.name ?? `Món #${row.itemId ?? ""}`;
    out.name = row.name ?? row.itemName ?? "";
  } else if (resource === "concessions") {
    out.price = Number(row.price ?? 0);
  }
  return out as T;
}

export const managementApi = {
  page: async <T>(
    resource: string,
    token: string,
    filters: Record<string, string | number | boolean | undefined>,
    signal?: AbortSignal,
  ) => {
    const page = await requestPage<AnyRecord>(`/${resource}?${query(filters)}`, { token, signal });
    return {
      data: page.data.map((row) => normalizeItem<T>(resource, row)),
      meta: page.meta,
    };
  },
  list: async <T>(
    resource: string,
    token: string,
    signal?: AbortSignal,
  ): Promise<T[]> => {
    const raw = await request<AnyRecord[] | { items?: AnyRecord[] }>(
      `/${resource}`,
      { token, signal },
    );
    const rows = Array.isArray(raw) ? raw : raw?.items;
    if (!Array.isArray(rows)) throw new Error("Máy chủ trả danh sách quản trị không đúng định dạng.");
    return rows.map((r) => normalizeItem<T>(resource, r));
  },
  save: async <T>(
    resource: string,
    body: unknown,
    token: string,
    id?: number,
  ): Promise<T> => {
    const data = { ...((body ?? {}) as AnyRecord) };

    if (resource === "movies") {
      const active = data.active;
      delete data.active;
      for (const k of [
        "description",
        "genre",
        "country",
        "language",
        "director",
        "poster",
        "trailer",
      ]) {
        if (data[k] === "") delete data[k];
      }
      const saved = await request<AnyRecord & { id: number }>(
        `/movies${id ? "/" + id : ""}`,
        {
          method: id ? "PATCH" : "POST",
          body: data,
          token,
        },
      );
      if (typeof active === "boolean" && saved.active !== undefined && saved.active !== active) {
        const toggled = await saveStatus(`/movies/${saved.id}/status`, active, token);
        return normalizeItem<T>(resource, toggled);
      }
      return normalizeItem<T>(resource, { ...saved, active: active ?? saved.active });
    }

    if (resource === "auditoriums") {
      const active = data.active;
      const type = data.auditoriumType ?? data.type ?? "STANDARD_2D";
      const payload = {
        name: data.name,
        totalSeats: Number(data.totalSeats),
        type,
        auditoriumType: type,
      };
      const saved = await request<AnyRecord & { id?: number }>(
        `/auditoriums${id ? "/" + id : ""}`,
        {
          method: id ? "PATCH" : "POST",
          body: { name: payload.name, totalSeats: payload.totalSeats, type: payload.type },
          token,
        },
      );
      if (typeof active === "boolean" && saved?.id && saved.active !== active) {
        const updated = await saveStatus(`/auditoriums/${saved.id}/status`, active, token);
        return normalizeItem<T>(resource, updated);
      }
      return normalizeItem<T>(resource, { ...saved, active: active ?? saved?.active });
    }

    if (resource.endsWith("/seats") && !id) {
      const seatPayload = {
        row: String(data.row ?? "").toUpperCase(),
        number: Number(data.number),
        type: data.seatType ?? data.type ?? "STANDARD",
      };
      const created = await request<AnyRecord[] | AnyRecord>(`/${resource}`, {
        method: "POST",
        body: { seats: [seatPayload] },
        token,
      });
      const first = Array.isArray(created) ? created[0] : created;
      if (!first) throw new Error("Máy chủ không trả ghế vừa tạo.");
      return normalizeItem<T>(resource, first);
    }

    if ((resource.endsWith("/seats") || resource === "seats") && id) {
      const seatPayload = {
        row: String(data.row ?? "").toUpperCase(),
        number: Number(data.number),
        type: data.seatType ?? data.type ?? "STANDARD",
      };
      const updated = await request<AnyRecord>(`/seats/${id}`, {
        method: "PATCH",
        body: seatPayload,
        token,
      });
      return normalizeItem<T>(resource, updated);
    }

    if (resource === "showtimes") {
      const payload: AnyRecord = {
        movieId: Number(data.movieId),
        startTime: data.startTime,
      };
      if (!id) {
        payload.auditoriumId = Number(data.auditoriumId);
      }
      const saved = await request<AnyRecord>(
        `/showtimes${id ? "/" + id : ""}`,
        {
          method: id ? "PATCH" : "POST",
          body: payload,
          token,
        },
      );
      return normalizeItem<T>(resource, saved);
    }

    if (resource === "concessions") {
      const isCombo = Boolean(data.combo);
      const active = data.active;
      const payload: AnyRecord = {
        name: data.name,
        price: Number(data.price),
      };
      if (isCombo) {
        payload.components = data.components ?? [];
      }
      const endpoint = isCombo
        ? `/concessions/combos${id ? "/" + id : ""}`
        : `/concessions${id ? "/" + id : ""}`;
      const saved = await request<AnyRecord & { id?: number }>(endpoint, {
        method: id ? "PATCH" : "POST",
        body: payload,
        token,
      });
      if (typeof active === "boolean" && saved?.id && saved.active !== active) {
        const updated = await saveStatus(`/concessions/${saved.id}/status`, active, token);
        return normalizeItem<T>(resource, updated);
      }
      return normalizeItem<T>(resource, { ...saved, active: active ?? saved?.active });
    }

    if (resource === "staff") {
      const payload: AnyRecord = {
        fullName: data.fullName,
        email: data.email,
        phone: data.phone,
      };
      if (!id) {
        payload.username = data.username;
        payload.password = data.password;
      }
      const saved = await request<AnyRecord>(`/staff${id ? "/" + id : ""}`, {
        method: id ? "PATCH" : "POST",
        body: payload,
        token,
      });
      return normalizeItem<T>(resource, saved);
    }

    const saved = await request<AnyRecord>(
      `/${resource}${id ? "/" + id : ""}`,
      {
        method: id ? "PATCH" : "POST",
        body: data,
        token,
      },
    );
    return normalizeItem<T>(resource, saved ?? {});
  },
  action: async <T>(
    path: string,
    body: unknown,
    token: string,
    method = "POST",
  ): Promise<T> => {
    if (path === "/seats/sweetbox-pairs" && method === "POST" && body && typeof body === "object") {
      const raw = body as { seatId?: number; pairSeatId?: number };
      const seatId = Number(raw.seatId);
      const pairSeatId = Number(raw.pairSeatId);
      return request<T>(path, {
        method,
        body: { seatId, pairSeatId },
        token,
      });
    }
    if (path.endsWith("/status") && path.startsWith("/staff/") && body && typeof body === "object") {
      const raw = body as { status?: string; active?: boolean };
      const active = raw.active !== undefined ? raw.active : raw.status === "ACTIVE";
      return request<T>(path, { method, body: { active }, token });
    }
    return request<T>(path, { method, body, token });
  },
  history: (id: number, token: string, page: number, signal?: AbortSignal) => requestPage<Restock>(`/inventory/${id}/history?page=${page}`, { token, signal }),
  restocks: async (
    token: string,
    filters: Record<string, string | number | undefined>,
    signal?: AbortSignal,
  ) => {
    if (filters.from && filters.to && filters.from > filters.to) throw new Error("Ngày bắt đầu không được sau ngày kết thúc.");
    const queryFilters: Record<string, string | number | undefined> = {
      ...filters,
    };
    if (
      typeof queryFilters.from === "string" &&
      /^\d{4}-\d{2}-\d{2}$/.test(queryFilters.from)
    ) {
      queryFilters.from = `${queryFilters.from}T00:00:00+07:00`;
    }
    if (
      typeof queryFilters.to === "string" &&
      /^\d{4}-\d{2}-\d{2}$/.test(queryFilters.to)
    ) {
      queryFilters.to = `${queryFilters.to}T23:59:59.999+07:00`;
    }
    const page = await requestPage<
      Restock & { id?: number }
    >("/inventory/restocks?" + query(queryFilters), {
      token,
      signal,
    });
    return {
      data: page.data.map((r) => ({
        ...r,
        movementId: Number(r.movementId ?? r.id ?? 0),
      })),
      meta: page.meta,
    };
  },
};
