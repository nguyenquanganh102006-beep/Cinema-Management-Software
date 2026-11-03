# Auditoriums và Seats API

Đường dẫn gốc `/api/v1`. Mọi endpoint dưới đây chỉ dành cho **MANAGER**. Ghép cặp Sweetbox có API riêng, không nằm ở đây.

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/auditoriums?active=` | Danh sách phòng (không phân trang), sắp xếp theo tên, kèm `seatCount` |
| GET | `/auditoriums/{id}` | Chi tiết phòng |
| POST | `/auditoriums` | Tạo phòng, mặc định đang hoạt động, chưa có ghế. Trả 201 |
| PATCH | `/auditoriums/{id}` | Thay tên, sức chứa, loại phòng. Không đổi trạng thái |
| PATCH | `/auditoriums/{id}/status` | Body `{"active": true|false}` |
| GET | `/auditoriums/{id}/seats` | Ghế của phòng, sắp xếp theo hàng rồi số ghế |
| POST | `/auditoriums/{id}/seats` | Thêm 1 đến 500 ghế một lần. Tất cả hoặc không ghế nào. Trả 201 |
| PATCH | `/seats/{seatId}` | Thay hàng, số ghế, loại ghế của một ghế |

## Quy tắc

- `type` của phòng: `STANDARD_2D`, `3D` hoặc `IMAX` (đúng chữ hoa như vậy).
- `totalSeats` là **sức chứa khai báo** (> 0). Số ghế thực tế không được vượt quá nó. Không thể giảm `totalSeats` xuống dưới số ghế hiện có.
- Hàng ghế gồm 1 đến 5 chữ cái, tự chuyển thành chữ hoa. Số ghế phải dương. Mỗi cặp (hàng, số) chỉ xuất hiện một lần trong một phòng.
- `type` của ghế: `STANDARD` (mặc định), `VIP`, `SWEETBOX`.
- Ghế đang ghép cặp Sweetbox không đổi được vị trí hoặc loại.
- Không có API xóa phòng hay xóa ghế.
- Mọi thao tác ghi ghế và đổi sức chứa đều khóa dòng phòng trước, nên chạy tuần tự trong cùng một phòng. Ràng buộc UNIQUE của database vẫn là chốt chặn cuối.

## Ví dụ

```json
POST /api/v1/auditoriums            { "name": "Phòng 1", "totalSeats": 120, "type": "3D" }
POST /api/v1/auditoriums/1/seats    { "seats": [ { "row": "A", "number": 1, "type": "VIP" }, { "row": "A", "number": 2 } ] }
PATCH /api/v1/seats/10              { "row": "B", "number": 9, "type": "STANDARD" }
```

## Lỗi

| Mã | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Sai trường, trường lạ, loại không hợp lệ, ghế lặp trong yêu cầu, danh sách rỗng hoặc quá 500 |
| `ACCESS_DENIED` | 403 | Không phải Manager |
| `AUDITORIUM_NOT_FOUND`, `SEAT_NOT_FOUND` | 404 | Không tồn tại |
| `AUDITORIUM_NAME_EXISTS` | 409 | Trùng tên phòng, không phân biệt hoa thường. Được đảm bảo bằng index `uq_auditoriums_name_ci` (V5), kể cả khi hai request chạy đồng thời |
| `AUDITORIUM_CAPACITY_BELOW_SEATS` | 409 | Sức chứa mới nhỏ hơn số ghế hiện có |
| `AUDITORIUM_CAPACITY_EXCEEDED` | 409 | Thêm ghế làm vượt sức chứa |
| `SEAT_POSITION_TAKEN` | 409 | Vị trí (hàng, số) đã có trong phòng |
| `SEAT_IS_PAIRED` | 409 | Ghế đang ghép cặp Sweetbox |
 