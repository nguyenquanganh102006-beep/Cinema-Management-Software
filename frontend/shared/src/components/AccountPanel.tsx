import { useAuth } from "../auth/AuthProvider";
export function AccountPanel() {
  const { session } = useAuth();
  const sameName = session.user.fullName === session.user.username;
  return (
    <section className="account-card">
      <dl>
        {Object.entries({
          [sameName ? "Tên tài khoản" : "Họ tên"]: session.user.fullName,
          ...(!sameName ? { "Tên đăng nhập": session.user.username } : {}),
          Email: session.user.email,
          "Số điện thoại": session.user.phone,
        }).map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
