# Pricing rules API

Đường dẫn gốc `/api/v1/pricing-rules`. Chỉ **MANAGER**. Không có API xóa quy tắc.

Giá một ghế = **giá cơ bản + phụ thu phòng + phụ thu loại ghế + phụ thu khung giờ**. Phụ thu khung giờ chỉ tính một lần dù khớp một hay cả hai điều kiện (thứ Sáu đến Chủ nhật **hoặc** giờ bắt đầu sau 18:00).

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/pricing-rules?type=` | Danh sách (không phân trang), sắp xếp theo loại rồi đối tượng |
| GET | `/pricing-rules/{id}` | Chi tiết |
| POST | `/pricing-rules` | Tạo quy tắc cho một cặp (loại, đối tượng) chưa có quy tắc. Trả 201 |
| PATCH | `/pricing-rules/{id}` | Đổi **số tiền và mô tả**. Mã, loại, đối tượng không đổi được |

## Dữ liệu đã được duyệt (seed ở V3)

| Mã | Loại | Đối tượng | Số tiền (đồng) |
|---|---|---|---|
| `BASE_STANDARD` | BASE_PRICE | STANDARD_2D | 70.000 |
| `SUR_2D` | ROOM_SURCHARGE | STANDARD_2D | 0 |
| `SUR_3D` | ROOM_SURCHARGE | 3D | 20.000 |
| `SUR_IMAX` | ROOM_SURCHARGE | IMAX | 40.000 |
| `SUR_STANDARD` | SEAT_SURCHARGE | STANDARD | 0 |
| `SUR_VIP` | SEAT_SURCHARGE | VIP | 15.000 |
| `SUR_SWEETBOX` | SEAT_SURCHARGE | SWEETBOX | 15.000 mỗi ghế vật lý của cặp |
| `SUR_TIME` | TIME_SURCHARGE | WEEKEND_OR_AFTER_18H | 10.000 |

## Quy tắc dữ liệu

- `amount`: không âm, tối đa 8 chữ số nguyên và 2 chữ số thập phân (theo `NUMERIC(10,2)`).
- Cặp (loại, đối tượng) hợp lệ chỉ gồm 8 cặp trong bảng trên. Giá trị lạ trả `PRICING_RULE_INVALID`.
- Mỗi cặp (loại, đối tượng) chỉ có một quy tắc. Muốn đổi giá thì sửa quy tắc có sẵn.
- Đổi giá chỉ ảnh hưởng suất chiếu và đơn tạo sau này, vì chúng lưu giá tại thời điểm tạo.

## Ví dụ

```json
PATCH /api/v1/pricing-rules/6   { "amount": 20000, "description": "Phụ thu ghế VIP" }
```

## Lỗi

| Mã | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Số tiền sai, trường lạ (kể cả khi cố đổi mã, loại, đối tượng), `type` sai |
| `PRICING_RULE_INVALID` | 400 | Đối tượng không thuộc danh mục của loại quy tắc |
| `ACCESS_DENIED` | 403 | Không phải Manager |
| `PRICING_RULE_NOT_FOUND` | 404 | Không có quy tắc |
| `PRICING_RULE_CODE_EXISTS`, `PRICING_RULE_EXISTS` | 409 | Trùng mã, hoặc cặp (loại, đối tượng) đã có quy tắc |