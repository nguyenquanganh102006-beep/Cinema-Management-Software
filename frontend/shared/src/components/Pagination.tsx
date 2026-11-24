import type { PageMeta } from "../types";
export function Pagination({
  meta,
  onPage,
}: {
  meta: PageMeta;
  onPage: (page: number) => void;
}) {
  return (
    <nav className="toolbar" aria-label="Phân trang">
      <button disabled={meta.page === 0} onClick={() => onPage(meta.page - 1)}>
        Trước
      </button>
      <span>
        Trang {meta.page + 1} / {Math.max(1, meta.totalPages)} ·{" "}
        {meta.totalElements} kết quả
      </span>
      <button
        disabled={meta.page + 1 >= meta.totalPages}
        onClick={() => onPage(meta.page + 1)}
      >
        Sau
      </button>
    </nav>
  );
}
