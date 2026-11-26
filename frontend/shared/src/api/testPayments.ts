import { request } from "../api";

export interface TestPaymentMode {
  enabled: boolean;
}

export interface TestPaymentResult {
  bookingId: number;
  paymentStatus: "PAID";
  ticketCount: number;
}

export const testPaymentsApi = {
  mode: (token: string, signal?: AbortSignal) =>
    request<TestPaymentMode>("/payments/test-mode", { token, signal }),
  complete: (bookingId: number, token: string) =>
    request<TestPaymentResult>(`/bookings/${bookingId}/payments/test-complete`, {
      method: "POST",
      token,
    }),
};
