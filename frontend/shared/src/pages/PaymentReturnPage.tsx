import { useState } from "react";
import { Link } from "react-router-dom";
import { request } from "../api";
import { useQuery } from "../hooks/useQuery";
import { Feedback } from "../components/Feedback";
type ReturnStatus = { bookingId: number; bookingCode: string; paymentStatus: string; bookingStatus: string };
export function PaymentReturnPage() {
  const search = new URLSearchParams(location.search), mockId = search.get("mockBooking");
  const [done, setDone] = useState(false);
  const result = useQuery("payment-return:" + location.search, async (signal) => {
    if (!search.has("vnp_TxnRef")) return null;
    const value = await request<ReturnStatus>("/payments/vnpay/return" + location.search, { signal });
    if (!value || !value.bookingId || !value.bookingStatus || !value.paymentStatus) throw new Error("Kết quả thanh toán không hợp lệ. Đọc lại đơn trong lịch sử để xác minh.");
    return value;
  });
  const status = result.data;
  return <main className="payment-return auth-panel"><section className="card"><h1>Kết quả thanh toán</h1><Feedback {...result} retry={result.refetch} />
    {status && <><p>Đơn {status.bookingCode}</p><p role="status">{status.bookingStatus === "PAID" ? "Máy chủ đã xác nhận thanh toán. Đăng nhập để xem vé." : status.bookingStatus === "EXCEPTION_PENDING_DECISION" ? "Đã ghi nhận tiền nhưng đơn cần quầy vé hỗ trợ xử lý." : status.paymentStatus === "FAILED" ? "Thanh toán chưa thành công. Quay lại đơn để kiểm tra hoặc thử lại." : "Đang chờ máy chủ xác nhận thanh toán. Quay lại tab đặt vé để theo dõi."}</p><Link className="button primary" to={`/bookings/${status.bookingId}`}>Đăng nhập / xem đơn {status.bookingCode}</Link></>}
    {!result.loading && !status && !mockId && !result.error && <p role="alert">Liên kết không có dữ liệu thanh toán hợp lệ. Kiểm tra đơn trong lịch sử đặt vé.</p>}
    {import.meta.env.DEV && mockId && <><p>Dữ liệu mô phỏng · Không phát sinh thanh toán thật.</p><button disabled={done} onClick={() => { const channel = new BroadcastChannel("cinema-reference-payments"); channel.postMessage({ type: "cinema:mock-ipn", id: Number(mockId) }); channel.close(); setDone(true); }}>Mô phỏng IPN thành công</button></>}
    <p>Vé chỉ được phát hành sau khi máy chủ xác nhận. Bạn có thể quay lại tab đặt vé hoặc xem lịch sử.</p><Link to="/bookings">Xem lịch sử đặt vé</Link></section></main>;
}
