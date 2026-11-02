# F&B catalog và Inventory API

Đường dẫn gốc `/api/v1`. Món lẻ có tồn kho riêng. Combo gồm nhiều món lẻ và **không có tồn kho riêng**.

## Danh mục món và combo (`/concessions`)

| Phương thức | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| GET | `/concessions?keyword=&active=&combo=&page=0&size=20` | Mọi tài khoản đã đăng nhập | Danh sách. Chỉ Manager thấy phần ngừng bán và dùng được `active`. `combo=true` chỉ lấy combo. `size` tối đa 100 |
| GET | `/concessions/{id}` | Mọi tài khoản đã đăng nhập | Chi tiết, combo kèm thành phần. Món ngừng bán trả 404 cho người không phải Manager |
| POST | `/concessions` | MANAGER | Tạo món lẻ. Tự tạo dòng tồn kho (0 sản phẩm, ngưỡng cảnh báo 10). Trả 201 |
| PATCH | `/concessions/{id}` | MANAGER | Sửa tên và giá của món lẻ (thay toàn bộ). Id của combo trả 404 |
| PATCH | `/concessions/{id}/status` | MANAGER | Body `{"active": true|false}`, dùng cho cả món lẻ và combo |
| POST | `/concessions/combos` | MANAGER | Tạo combo. Trả 201 |
| PATCH | `/concessions/combos/{id}` | MANAGER | Sửa combo, thay toàn bộ kể cả danh sách thành phần |

```json
POST /api/v1/concessions         { "name": "Bắp rang lớn", "price": 55000 }
POST /api/v1/concessions/combos  { "name": "Combo đôi", "price": 120000,
                                   "components": [ { "itemId": 1, "quantity": 1 }, { "itemId": 2, "quantity": 2 } ] }
```

Quy tắc:
- Tên món và combo duy nhất, không phân biệt hoa thường. Giá không âm, tối đa 8 chữ số nguyên và 2 chữ số thập phân.
- Thành phần combo: ít nhất 1 món, mỗi món một lần, phải là **món lẻ đang bán**, không lồng combo.
- Ngừng bán món đang nằm trong **combo đang bán** bị chặn (409). Hãy ngừng combo trước. Bật lại combo cần mọi món thành phần đang bán.
- Không có API xóa món hay combo.

## Tồn kho (`/inventory`)

| Phương thức | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| GET | `/inventory?keyword=&status=&page=&size=` | MANAGER | Tồn kho các món lẻ. `status`: `IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK` |
| GET | `/inventory/alerts` | MANAGER, STAFF | Món đang bán có tồn kho **bằng hoặc thấp hơn** ngưỡng cảnh báo, ít hàng nhất trước |
| POST | `/inventory/{itemId}/restocks` | MANAGER | Nhập hàng. Trả 201 |
| GET | `/inventory/restocks?itemId=&managerId=&from=&to=&page=&size=` | MANAGER | Lịch sử nhập kho toàn hệ thống, mới nhất trước. `from`, `to` theo ISO-8601, ví dụ `2026-11-01T00:00:00Z` |
| GET | `/inventory/{itemId}/history` | MANAGER | Lịch sử biến động của một món |

Tình trạng: `OUT_OF_STOCK` khi tồn bằng 0, `LOW_STOCK` khi tồn lớn hơn 0 và không vượt ngưỡng, còn lại là `IN_STOCK`.

### Nhập hàng

```json
POST /api/v1/inventory/5/restocks   { "quantity": 50, "note": "Lô A" }
```

- `quantity` phải là **số nguyên dương** (số thập phân như `1.5` hoặc `5.0` bị từ chối).
- Người nhập lấy từ token, **không có trong request**.
- Trong một transaction duy nhất: khóa dòng tồn kho, cộng số lượng, ghi một dòng lịch sử gồm số lượng, tồn trước, tồn sau, người nhập, ghi chú và thời điểm. Lỗi ở bước nào thì cả hai cùng hoàn tác. Dòng lịch sử chỉ được ghi thêm, không sửa.
- Hai lần nhập đồng thời không mất cập nhật: số tồn trước của lần sau luôn bằng số tồn sau của lần trước.

## Lỗi

| Mã | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Sai trường, trường lạ, `status`, `from`, `to`, `page`, `size` sai, `from` sau `to` |
| `INVALID_STOCK_QUANTITY` | 400 | Số lượng nhập không dương, hoặc làm tồn kho vượt giới hạn số nguyên |
| `CONCESSION_COMBO_INVALID` | 400 | Thành phần combo lặp, không tồn tại, là combo hoặc đang ngừng bán |
| `ACCESS_DENIED` | 403 | Không đủ quyền |
| `CONCESSION_ITEM_NOT_FOUND` | 404 | Không có món, hoặc id là combo khi gọi endpoint món lẻ hay tồn kho |
| `CONCESSION_NAME_EXISTS` | 409 | Trùng tên |
| `CONCESSION_ITEM_IN_ACTIVE_COMBO` | 409 | Ngừng bán món đang nằm trong combo đang bán |
| `CONCESSION_COMBO_COMPONENT_INACTIVE` | 409 | Bật combo khi có món thành phần ngừng bán |