export function Feedback({
  loading,
  error,
  empty,
  retry,
}: {
  loading?: boolean;
  error?: Error | string;
  empty?: boolean;
  retry?: () => void;
}) {
  if (loading) return <div className="loading-state" role="status" aria-busy="true"><span>Đang tải…</span><div className="skeleton" aria-hidden="true" /><div className="skeleton short" aria-hidden="true" /></div>;
  if (error)
    return (
      <div className="alert error" role="alert">
        {typeof error === "string" ? error : error.message}{" "}
        {retry && <button onClick={retry}>Thử lại</button>}
      </div>
    );
  if (empty) return <div className="empty-state" role="status"><strong>Chưa có dữ liệu</strong><p>Thử thay đổi bộ lọc hoặc quay lại bước trước để tiếp tục.</p>{retry && <button onClick={retry}>Tải lại</button>}</div>;
  return null;
}
