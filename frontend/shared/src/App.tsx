import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ApiError,
  authApi,
  type RegisterInput,
  type Role,
  type Session,
} from "./api";
import "./styles.css";

const roles: Record<
  Role,
  { label: string; name: string; items: string[] }
> = {
  MANAGER: {
    label: "Quản lý rạp",
    name: "Manager",
    items: [
      "Tổng quan",
      "Phim",
      "Phòng & ghế",
      "Lịch chiếu",
      "Bảng giá",
      "F&B & tồn kho",
      "Nhân viên",
      "Báo cáo",
    ],
  },
  STAFF: {
    label: "Nhân viên",
    name: "Staff POS",
    items: ["Bán vé tại quầy", "Giao dịch POS", "Soát vé", "Yêu cầu hoàn vé"],
  },
  CUSTOMER: {
    label: "Khách hàng",
    name: "Customer",
    items: ["Khám phá phim", "Lịch sử đặt vé", "Tài khoản"],
  },
};

function message(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Có lỗi xảy ra, vui lòng thử lại.";
}

export function App({ role }: { role: Role }) {
  const [session, setSession] = useState<
    (Session & { expiresAt: number }) | null
  >(null);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [transferring, setTransferring] = useState(() =>
    window.location.hash.startsWith("#auth-transfer="),
  );
  const transferStarted = useRef(false);
  const [page, setPage] = useState(roles[role].items[0]);
  const operation = useRef(0);
  const submission = useRef(false);

  useEffect(() => {
    if (
      transferStarted.current ||
      !window.location.hash.startsWith("#auth-transfer=")
    )
      return;
    transferStarted.current = true;
    const code = window.location.hash.slice("#auth-transfer=".length);
    window.history.replaceState(
      null,
      "",
      window.location.pathname + window.location.search,
    );
    authApi
      .consumeTransfer(code, role)
      .then((result) => {
        setPage(roles[result.user.role].items[0]);
        setSession({
          ...result,
          expiresAt: Date.now() + result.expiresIn * 1000,
        });
      })
      .catch((e) => setError(message(e)))
      .finally(() => setTransferring(false));
  }, [role]);

  useEffect(() => {
    if (!session) return;
    const timer = setTimeout(
      () => {
        operation.current++;
        setSession(null);
        setNotice("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
      },
      Math.max(0, session.expiresAt - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [session]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submission.current) return;
    submission.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    const data = new FormData(event.currentTarget);
    const input = Object.fromEntries(data) as unknown as RegisterInput;
    try {
      if (mode === "register") {
        if (new TextEncoder().encode(input.password).length > 72)
          throw new Error("Mật khẩu tối đa 72 byte UTF-8.");
        await authApi.register(input);
        setMode("login");
        setNotice("Tạo tài khoản thành công. Bạn có thể đăng nhập.");
      } else {
        const result = await authApi.login(input.username, input.password);
        if (result.user.role !== role) {
          const transfer = await authApi.transfer(result.accessToken);
          window.location.replace(transfer.redirectUrl);
          return;
        }
        setPage(roles[role].items[0]);
        setSession({
          ...result,
          expiresAt: Date.now() + result.expiresIn * 1000,
        });
      }
    } catch (e) {
      setError(message(e));
    } finally {
      submission.current = false;
      setBusy(false);
    }
  }

  async function logout() {
    if (!session || submission.current) return;
    submission.current = true;
    setBusy(true);
    setError("");
    const active = session;
    try {
      await authApi.logout(active.accessToken);
      operation.current++;
      setSession(null);
      setNotice("Đã đăng xuất và thu hồi phiên hiện tại.");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setSession(null);
        setNotice("Phiên đăng nhập không còn hiệu lực.");
      } else
        setError(
          `${message(e)} Chưa xác nhận thu hồi phiên. Vui lòng thử đăng xuất lại.`,
        );
    } finally {
      submission.current = false;
      setBusy(false);
    }
  }

  async function refreshProfile() {
    if (!session || busy) return;
    const id = ++operation.current;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const user = await authApi.me(session.accessToken);
      if (id !== operation.current) return;
      if (user.role !== role) {
        setSession(null);
        setNotice("Quyền tài khoản đã thay đổi. Vui lòng đăng nhập lại.");
      } else {
        setSession((s) => (s ? { ...s, user } : null));
        setNotice("Thông tin tài khoản đã được cập nhật.");
      }
    } catch (e) {
      if (id !== operation.current) return;
      if (e instanceof ApiError && e.status === 401) {
        setSession(null);
        setNotice("Phiên không còn hiệu lực. Vui lòng đăng nhập lại.");
      } else
        setError(
          e instanceof ApiError && e.status === 403
            ? "Bạn không có quyền thực hiện thao tác này."
            : message(e),
        );
    } finally {
      setBusy(false);
    }
  }

  if (transferring)
    return (
      <main className="auth-panel" style={{ minHeight: "100dvh" }}>
        <p role="status">Đang chuyển đến không gian của bạn…</p>
      </main>
    );
  if (!session)
    return (
      <main className="auth-layout">
        <section className="intro">
          <a className="brand" href="/" aria-label="Cinema trang chủ">
            <span className="brand-mark">C</span> CINEMA
            <span className="brand-dot">.</span>
          </a>
          <div>
            <span className="eyebrow">CINEMA MANAGEMENT SYSTEM</span>
            <h1>
              Mỗi suất chiếu.
              <br />
              Một trải nghiệm.
            </h1>
            <p>
              Một nơi kết nối hoạt động rạp chiếu phim, nhân viên và khách hàng.
            </p>
          </div>
          <p className="intro-bottom">
            {roles[role].name} Portal <span>●</span> Foundation
          </p>
        </section>
        <section className="auth-panel">
          <div className="auth-card">
            <span className="pill">{roles[role].label}</span>
            <h2>
              {mode === "register" ? "Tạo tài khoản" : "Chào mừng trở lại"}
            </h2>
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
                  placeholder="Nhập tên đăng nhập"
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

  return (
    <div className="workspace">
      <aside className="sidebar">
        <a className="brand" href="/">
          CINEMA<span className="brand-dot">.</span>
        </a>
        <p className="eyebrow">{roles[role].name} Portal</p>
        <nav aria-label="Chức năng">
          {roles[role].items.map((item, index) => (
            <button
              key={item}
              className={page === item ? "nav-item active" : "nav-item"}
              onClick={() => setPage(item)}
            >
              <span className="nav-index">
                {String(index + 1).padStart(2, "0")}
              </span>
              {item}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <strong>{session.user.fullName}</strong>
          <span>{roles[role].label}</span>
          <button onClick={logout} disabled={busy}>
            Đăng xuất ↗
          </button>
        </div>
      </aside>
      <main className="workspace-main">
        <header>
          <div>
            <p className="eyebrow">KHÔNG GIAN CỦA BẠN</p>
            <h1>{page}</h1>
          </div>
          <span className="pill">Foundation · Module 01</span>
        </header>
        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}
        {notice && (
          <p className="alert success" role="status">
            {notice}
          </p>
        )}
        <section className="welcome-card">
          <span className="eyebrow">XÁC THỰC THÀNH CÔNG</span>
          <h2>Xin chào, {session.user.fullName}.</h2>
          <p>
            Bạn đã truy cập đúng không gian {roles[role].label.toLowerCase()}.
            Tài khoản và phiên đăng nhập đang được bảo vệ bởi hệ thống phân
            quyền.
          </p>
          <button
            className="secondary"
            onClick={refreshProfile}
            disabled={busy}
          >
            {busy ? "Đang kiểm tra…" : "Kiểm tra phiên đăng nhập"} →
          </button>
        </section>
        <section className="account-grid">
          <div className="account-card">
            <h3>Thông tin tài khoản</h3>
            <dl>
              <dt>Tên đăng nhập</dt>
              <dd>{session.user.username}</dd>
              <dt>Email</dt>
              <dd>{session.user.email}</dd>
              <dt>Số điện thoại</dt>
              <dd>{session.user.phone}</dd>
              <dt>Vai trò</dt>
              <dd>{session.user.role}</dd>
            </dl>
          </div>
          <div className="placeholder-card">
            <span className="placeholder-icon" aria-hidden="true">
              <svg
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.3"
              >
                <rect x="3" y="4" width="18" height="16" rx="2" />
                <path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" />
              </svg>
            </span>
            <h3>{page}</h3>
            <p>
              Khung giao diện đã sẵn sàng. Chức năng nghiệp vụ này sẽ được triển
              khai trong các module tiếp theo.
            </p>
            <span className="pill">Chưa triển khai nghiệp vụ</span>
          </div>
        </section>
      </main>
    </div>
  );
}
