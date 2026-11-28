package com.cinema.cms.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Builds an EMVCo Merchant-Presented Mode payload for a NAPAS VietQR account transfer. */
public final class VietQrPayloadBuilder {
    private static final String VIETQR_GUID = "A000000727";
    private static final String ACCOUNT_TRANSFER_SERVICE = "QRIBFTTA";

    private VietQrPayloadBuilder() {}

    public static String build(String bankBin, String account, BigDecimal amount, String content) {
        if (bankBin == null || !bankBin.matches("[0-9]{6}")
                || account == null || !account.matches("[0-9]{1,19}")
                || amount == null || amount.signum() <= 0
                || content == null || content.isBlank() || content.length() > 25
                || !isAscii(content)) {
            throw new ApiException(503, "PAYMENT_CONFIGURATION_MISSING",
                    "Cấu hình VietQR hoặc nội dung chuyển khoản không hợp lệ");
        }

        String merchantAccount = tlv("00", VIETQR_GUID)
                + tlv("01", bankBin)
                + tlv("02", account)
                + tlv("03", ACCOUNT_TRANSFER_SERVICE);
        String amountText = amount.stripTrailingZeros().toPlainString();
        String data = "000201"
                + "010211"
                + tlv("38", merchantAccount)
                + tlv("53", "704")
                + tlv("54", amountText)
                + tlv("58", "VN")
                + tlv("62", tlv("08", content));
        String crcInput = data + "6304";
        return crcInput + String.format(java.util.Locale.ROOT, "%04X", crc16(crcInput));
    }

    private static String tlv(String tag, String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > 99) throw new ApiException(503, "PAYMENT_CONFIGURATION_MISSING", "Dữ liệu VietQR vượt giới hạn");
        return tag + String.format(java.util.Locale.ROOT, "%02d", length) + value;
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
    }

    private static int crc16(String value) {
        int crc = 0xFFFF;
        for (byte raw : value.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (raw & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
