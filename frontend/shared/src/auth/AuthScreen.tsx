import type { FormEvent } from "react";
import type { Role } from "../api";
const roles = {
  MANAGER: { label: "Quản lý rạp", name: "Manager" },
  STAFF: { label: "Nhân viên", name: "Staff POS" },
  CUSTOMER: { label: "Khách hàng", name: "Customer" },
};
export function AuthScreen({
  role,
  mode,
  busy,
  error,
  fieldErrors = {},
  notice,
  submit,
  setMode,
  setError,
  setNotice,
}: {
  role: Role;
  mode: "login" | "register";
  busy: boolean;
  error: string;
  fieldErrors?: Record<string, string>;
  notice: string;
  submit: (e: FormEvent<HTMLFormElement>) => void;
  setMode: (mode: "login" | "register") => void;
  setError: (s: string) => void;
  setNotice: (s: string) => void;
}) {
  return (
    <main className="auth-layout">
      <section className="intro">
        <a className="brand" href="/" aria-label="Cinema trang chủ">
          <span className="brand-mark">C</span> CINEMA
          <span className="brand-dot">.</span>
        </a>
        <div>
          <span className="eyebrow">
            {role === "CUSTOMER" ? "ĐẶT VÉ TRỰC TUYẾN" : "CINEMA MANAGEMENT SYSTEM"}
          </span>
          <h1>
            Mỗi suất chiếu.
            <br />
            Một trải nghiệm.
          </h1>
          <p>
            {role === "CUSTOMER"
              ? "Khám phá phim, chọn suất chiếu và đặt vé chỉ trong vài bước."
              : "Một nơi kết nối hoạt động rạp chiếu phim, nhân viên và khách hàng."}
          </p>
        </div>
        {role !== "CUSTOMER" && (
          <p className="intro-bottom">
            {roles[role].name} Portal <span>●</span> Foundation
          </p>
        )}
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <span className="pill">{roles[role].label}</span>
          <h2>{mode === "register" ? "Tạo tài khoản" : "Chào mừng trở lại"}</h2>
          <p className="muted">
            {mode === "register"
              ? "Đăng ký tài khoản khách hàng của bạn."
              : "Đăng nhập để truy cập không gian của bạn."}
          </p>
          {error && (
            <p role="alert" className="alert error">
              {error}
            </p>
          )}
          {Object.keys(fieldErrors).length > 0 && <ul className="field-errors">{Object.entries(fieldErrors).map(([field, error]) => <li key={field}>{({ username: "Tên đăng nhập", password: "Mật khẩu", email: "Email", phone: "Số điện thoại", fullName: "Họ và tên" } as Record<string, string>)[field] ?? field}: {error}</li>)}</ul>}
          {notice && (
            <p role="status" className="alert success">
              {notice}
            </p>
          )}
          <form onSubmit={submit} key={mode}>
            <label>
              {mode === "register"
                ? "Tên đăng nhập"
                : "Tên đăng nhập hoặc email"}
              <input
                name="username"
                required
                maxLength={mode === "register" ? 50 : 150}
                minLength={mode === "register" ? 3 : undefined}
                pattern={
                  mode === "register" ? "[a-zA-Z0-9._\\-]{3,50}" : undefined
                }
                autoComplete="username"
                autoCapitalize="none"
                placeholder={mode === "register" ? "Nhập tên đăng nhập" : "Nhập tên đăng nhập hoặc email"}
                disabled={busy}
              />
            </label>
            {mode === "register" && (
              <>
                <label>
                  Họ và tên
                  <input
                    name="fullName"
                    required
                    maxLength={100}
                    autoComplete="name"
                    disabled={busy}
                  />
                </label>
                <label>
                  Email
                  <input
                    name="email"
                    type="email"
                    required
                    maxLength={150}
                    autoComplete="email"
                    disabled={busy}
                  />
                </label>
                <label>
                  Số điện thoại
                  <input
                    name="phone"
                    type="tel"
                    required
                    pattern="\+?[0-9]{9,15}"
                    autoComplete="tel"
                    placeholder="0901234567"
                    disabled={busy}
                  />
                </label>
              </>
            )}
            <label>
              Mật khẩu
              <input
                name="password"
                type="password"
                required
                minLength={mode === "register" ? 8 : undefined}
                maxLength={72}
                autoComplete={
                  mode === "register" ? "new-password" : "current-password"
                }
                placeholder={
                  mode === "register" ? "Tối thiểu 8 ký tự" : "Nhập mật khẩu"
                }
                disabled={busy}
              />
            </label>
            <button className="primary" disabled={busy} type="submit">
              {busy
                ? "Đang xử lý…"
                : mode === "register"
                  ? "Tạo tài khoản"
                  : "Đăng nhập"}{" "}
              <span aria-hidden="true">→</span>
            </button>
          </form>
          {role === "CUSTOMER" && (
            <p className="switch-mode">
              {mode === "login" ? "Chưa có tài khoản?" : "Đã có tài khoản?"}{" "}
              <button
                type="button"
                className="text-button"
                disabled={busy}
                onClick={() => {
                  setMode(mode === "login" ? "register" : "login");
                  setError("");
                  setNotice("");
                }}
              >
                {mode === "login" ? "Đăng ký" : "Đăng nhập"}
              </button>
            </p>
          )}
          <p className="auth-note">
            Phiên có thời hạn 30 phút theo cấu hình mặc định.
            <br />
            Tải lại trang sẽ yêu cầu đăng nhập lại.
          </p>
        </div>
      </section>
    </main>
  );
}
