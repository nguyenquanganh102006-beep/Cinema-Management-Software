import { NavLink, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "../auth/AuthProvider";
const menus = {
  MANAGER: [
    ["/dashboard", "Tổng quan"],
    ["/movies", "Phim"],
    ["/auditoriums", "Phòng & ghế"],
    ["/showtimes", "Lịch chiếu"],
    ["/pricing", "Bảng giá"],
    ["/concessions", "F&B"],
    ["/inventory", "Tồn kho"],
    ["/inventory/history", "Lịch sử nhập kho"],
    ["/staff", "Nhân viên"],
    ["/reports", "Báo cáo"],
  ],
  STAFF: [
    ["/pos", "Bán vé tại quầy"],
    ["/transactions", "Giao dịch POS"],
    ["/gate", "Soát vé"],
    ["/refunds", "Yêu cầu hoàn vé"],
  ],
  CUSTOMER: [
    ["/movies", "Khám phá phim"],
    ["/bookings", "Lịch sử đặt vé"],
  ],
};
export function PortalLayout({ children }: { children: ReactNode }) {
  const { session, error, notice, busy, logout } = useAuth();
  const { pathname } = useLocation();
  const bookingTitle = /^\/(?:pos\/)?showtimes\/\d+(?:\/book)?$/.test(pathname) ? "Chọn ghế và F&B"
    : /^(?:\/pos)?\/checkout$/.test(pathname) ? "Thanh toán"
    : /^\/pos\/bookings\/\d+\/receipt$/.test(pathname) ? "Vé và hóa đơn"
    : /^\/(?:bookings|transactions)\/\d+$/.test(pathname) ? "Chi tiết giao dịch" : undefined;
  const pageTitle = bookingTitle ?? [...menus[session.user.role]]
    .sort((a, b) => b[0].length - a[0].length)
    .find(([path]) => pathname === path || (path !== "/" && pathname.startsWith(path + "/")))?.[1]
    ?? (pathname === "/account" ? "Tài khoản" : "Không gian làm việc");
  return (
    <div className={`workspace portal-${session.user.role.toLowerCase()}`}>
      <a className="skip-link" href="#main-content">Bỏ qua điều hướng</a>
      <aside className="sidebar">
        <NavLink className="brand" to="/">
          CINEMA<span className="brand-dot">.</span>
        </NavLink>
        <p>{session.user.role} Portal</p>
        <nav aria-label="Chức năng">
          {[...menus[session.user.role], ["/account", "Tài khoản"]].map(
            ([path, label]) => (
              <NavLink
                end={path === "/inventory"}
                key={path}
                to={path}
                className={({ isActive }) =>
                  "nav-item " + (isActive ? "active" : "")
                }
              >
                {label}
              </NavLink>
            ),
          )}
        </nav>
        <div className="sidebar-footer">
          <strong>{session.user.fullName}</strong>
          <button disabled={busy} onClick={logout}>
            Đăng xuất ↗
          </button>
        </div>
      </aside>
      <main className="workspace-main" id="main-content" tabIndex={-1}>
        <header>
          <div>
            <p className="eyebrow">{session.user.role} Portal</p>
            <h1>{pageTitle}</h1>
          </div>
          <span className="pill">
            {import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS === "true"
              ? "Dữ liệu mô phỏng"
              : "API thật"}
          </span>
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
        {children}
      </main>
    </div>
  );
}
