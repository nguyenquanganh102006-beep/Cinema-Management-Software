# Movies API

Đường dẫn gốc `/api/v1/movies`. Cần token `Authorization: Bearer ...`.

| Phương thức | Đường dẫn                                 | Quyền                      | Mô tả                                                                                                          |
| ----------- | ----------------------------------------- | -------------------------- | -------------------------------------------------------------------------------------------------------------- | ------- |
| GET         | `/movies?keyword=&active=&page=0&size=20` | Mọi tài khoản đã đăng nhập | Danh sách. Chỉ Manager thấy phim ngừng chiếu và dùng được `active`. `size` tối đa 100. Sắp xếp theo tên rồi id |
| GET         | `/movies/{id}`                            | Mọi tài khoản đã đăng nhập | Chi tiết. Phim ngừng chiếu trả 404 cho người không phải Manager                                                |
| POST        | `/movies`                                 | MANAGER                    | Tạo phim, mặc định đang hoạt động. Trả 201                                                                     |
| PATCH       | `/movies/{id}`                            | MANAGER                    | Thay toàn bộ các trường chỉnh sửa được. Trường tùy chọn để trống sẽ bị xóa. Không đổi trạng thái               |
| PATCH       | `/movies/{id}/status`                     | MANAGER                    | Body `{"active": true                                                                                          | false}` |

## Request (POST và PATCH)

```json
{
  "title": "Mưa Đỏ",
  "durationMinutes": 124,
  "ageRating": "T13",
  "releaseDate": "2026-11-14",
  "description": "…",
  "genre": "Chiến tranh",
  "country": "Việt Nam",
  "language": "Tiếng Việt",
  "director": "…",
  "poster": "https://cdn.example.test/posters/mua-do.jpg",
  "trailer": "https://www.youtube.com/watch?v=abc123"
}
```

Bắt buộc: `title` (≤ 200), `durationMinutes` (> 0), `ageRating` (`P`, `T13`, `T16`, `T18`), `releaseDate`. `poster` và `trailer` phải là địa chỉ `http` hoặc `https`. Trường lạ bị từ chối.

## Phản hồi

Một phim: `{"data": {"id", "title", "durationMinutes", "ageRating", "releaseDate", "active", "description", "genre", "country", "language", "director", "poster", "trailer"}}`.

Danh sách: `{"data": {"items": [...], "page", "size", "totalElements", "totalPages"}}`.

## Lỗi

| Mã                 | HTTP | Khi nào                                                          |
| ------------------ | ---- | ---------------------------------------------------------------- |
| `VALIDATION_ERROR` | 400  | Thiếu hoặc sai trường, có trường lạ, `page` hoặc `size` sai      |
| `ACCESS_DENIED`    | 403  | Không phải Manager khi ghi                                       |
| `MOVIE_NOT_FOUND`  | 404  | Không có phim hoặc phim ngừng chiếu với người không phải Manager |
