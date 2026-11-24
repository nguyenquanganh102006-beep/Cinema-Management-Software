import { useAuth } from "../auth/AuthProvider";
export function AccountPanel() {
  const { session, busy, refreshProfile } = useAuth();
  return (
    <section className="account-card">
      <h2>Xin chào, {session.user.fullName}.</h2>
      <dl>
        {Object.entries({
          "Tên đăng nhập": session.user.username,
          Email: session.user.email,
          "Số điện thoại": session.user.phone,
          "Vai trò": session.user.role,
        }).map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <button disabled={busy} onClick={refreshProfile}>
        Kiểm tra phiên đăng nhập →
      </button>
    </section>
  );
}
