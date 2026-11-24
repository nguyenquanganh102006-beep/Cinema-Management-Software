import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { AuthScreen } from "./AuthScreen";
import {
  ApiError,
  authApi,
  type RegisterInput,
  type Role,
  type Session,
} from "../api";

function message(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Có lỗi xảy ra, vui lòng thử lại.";
}

interface AuthState {
  session: Session;
  busy: boolean;
  error: string;
  notice: string;
  logout: () => Promise<void>;
  refreshProfile: () => Promise<void>;
}
const Context = createContext<AuthState | null>(null);
export function useAuth() {
  const value = useContext(Context);
  if (!value) throw new Error("Authentication required");
  return value;
}
export function AuthProvider({
  role,
  children,
}: {
  role: Role;
  children: ReactNode;
}) {
  const [session, setSession] = useState<
    (Session & { expiresAt: number }) | null
  >(null);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [transferring, setTransferring] = useState(() =>
    window.location.hash.startsWith("#auth-transfer="),
  );
  const transferStarted = useRef(false);

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
        setSession({
          ...result,
          expiresAt: Date.now() + result.expiresIn * 1000,
        });
      })
      .catch((e) => setError(message(e)))
      .finally(() => setTransferring(false));
  }, [role]);

  useEffect(() => {
    const expired = () => {
      operation.current++;
      setSession(null);
      setNotice("Phiên không còn hiệu lực. Vui lòng đăng nhập lại.");
    };
    window.addEventListener("cinema:unauthorized", expired);
    return () => window.removeEventListener("cinema:unauthorized", expired);
  }, []);
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
    setFieldErrors({});
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

        setSession({
          ...result,
          expiresAt: Date.now() + result.expiresIn * 1000,
        });
      }
    } catch (e) {
      if (e instanceof ApiError) setFieldErrors(e.details);
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
    return <p role="status">Đang chuyển đến không gian của bạn…</p>;
  if (!session)
    return (
      <AuthScreen
        {...{
          role,
          mode,
          busy,
          error,
          fieldErrors,
          notice,
          submit,
          setMode,
          setError,
          setNotice,
        }}
      />
    );
  if (session.user.role !== role)
    return (
      <p role="alert">Quyền tài khoản đã thay đổi. Vui lòng đăng nhập lại.</p>
    );
  return (
    <Context.Provider
      value={{ session, busy, error, notice, logout, refreshProfile }}
    >
      {children}
    </Context.Provider>
  );
}
