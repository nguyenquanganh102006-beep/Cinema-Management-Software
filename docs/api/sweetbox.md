# Sweetbox API

Ghép hai ghế thành một cặp Sweetbox. Chỉ **MANAGER**. Đường dẫn gốc `/api/v1/seats/sweetbox-pairs`.

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| POST | `/seats/sweetbox-pairs` | Ghép hai ghế. Ghi cả hai chiều trong một transaction. Trả 201 |
| DELETE | `/seats/sweetbox-pairs/{seatId}` | Gỡ cặp chứa ghế này, dùng id của bất kỳ ghế nào trong cặp |

## Ghép cặp

```json
POST /api/v1/seats/sweetbox-pairs   { "seatId": 10, "pairSeatId": 11 }
```

Điều kiện: hai ghế **khác nhau**, **cùng một phòng**, cả hai thuộc loại `SWEETBOX`, và **chưa ghế nào thuộc cặp khác**. Kết quả luôn đối xứng: ghế 10 trỏ tới 11 và ghế 11 trỏ tới 10.

Phản hồi: `{"data": {"seats": [ {...ghế A...}, {...ghế B...} ]}}`, mỗi ghế có `pairSeatId` trỏ về ghế còn lại.

## Gỡ cặp

`DELETE /api/v1/seats/sweetbox-pairs/10` đặt `pairSeatId` của cả hai ghế về `null`. Nếu dữ liệu cũ bị lệch một chiều thì cả hai phía vẫn được dọn sạch.

Ghế đang ghép cặp **không đổi được vị trí hoặc loại** qua `PATCH /seats/{id}` (`SEAT_IS_PAIRED`). Hãy gỡ cặp trước.

## Lỗi

| Mã | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Thiếu hoặc sai trường, id không dương, có trường lạ |
| `SWEETBOX_PAIR_INVALID` | 400 | Hai ghế trùng nhau, khác phòng, hoặc không phải loại SWEETBOX |
| `ACCESS_DENIED` | 403 | Không phải Manager |
| `SEAT_NOT_FOUND` | 404 | Không có ghế |
| `SWEETBOX_PAIR_NOT_FOUND` | 404 | Gỡ cặp cho ghế chưa thuộc cặp nào |
| `SWEETBOX_ALREADY_PAIRED` | 409 | Một trong hai ghế đã thuộc cặp khác. Cũng áp dụng khi hai yêu cầu đồng thời tranh nhau một ghế |