# Staff API

Đường dẫn gốc `/api/v1/staff`. Cần token Manager: `Authorization: Bearer ...`. API chỉ làm việc với tài khoản có vai trò **STAFF**. Tài khoản Manager và khách hàng không xuất hiện, id của họ trả 404.

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/staff?keyword=&status=&page=0&size=20` | Danh sách. `keyword` tìm theo họ tên hoặc tên đăng nhập. `status`: `ACTIVE`, `INACTIVE`, `LOCKED`. `size` tối đa 100 |
| GET | `/staff/{id}` | Chi tiết |
| POST | `/staff` | Tạo nhân viên. Vai trò luôn là STAFF, trạng thái ACTIVE. Trả 201 |
| PATCH | `/staff/{id}` | Cập nhật họ tên, email, số điện thoại. Không đổi username, vai trò, mật khẩu, trạng thái |
| PATCH | `/staff/{id}/status` | Body `{"active": true|false}`. `false` đặt INACTIVE và vô hiệu mọi token đã phát hành |

## Tạo nhân viên

```json
{ "username": "nguyen.van.a", "fullName": "Nguyễn Văn A", "email": "a.nguyen@cinema.test",
  "phone": "0912345678", "password": "ChangeMe#2026" }
```

Username và email được chuẩn hóa về chữ thường. Mật khẩu 8 đến 72 byte, được băm bằng BCrypt và không bao giờ có trong phản hồi. Mọi trường không có trong ví dụ trên, kể cả `role`, bị từ chối.

## Phản hồi

`{"data": {"id", "username", "fullName", "email", "phone", "status", "createdAt"}}`. Danh sách: `{"data": {"items": [...], "page", "size", "totalElements", "totalPages"}}`.

## Lỗi

| Mã | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Sai trường, trường lạ, `status`, `page` hoặc `size` sai |
| `ACCESS_DENIED` | 403 | Không phải Manager |
| `STAFF_NOT_FOUND` | 404 | Không có nhân viên, hoặc id thuộc Manager hay khách |
| `USERNAME_ALREADY_EXISTS`, `EMAIL_ALREADY_EXISTS`, `PHONE_ALREADY_EXISTS` | 409 | Trùng dữ liệu |