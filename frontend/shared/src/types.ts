export type { Role, User, Session, Page, PageMeta } from "./api";
export type AgeRating = "P" | "T13" | "T16" | "T18";
export interface Movie {
  id: number;
  title: string;
  durationMinutes: number;
  ageRating: AgeRating;
  releaseDate: string;
  active: boolean;
  description?: string;
  genre?: string;
  country?: string;
  language?: string;
  director?: string;
  poster?: string;
  trailer?: string;
}
export interface Auditorium {
  id: number;
  name: string;
  auditoriumType: "STANDARD_2D" | "3D" | "IMAX";
  totalSeats: number;
  active: boolean;
}
export interface Showtime {
  id: number;
  movieId: number;
  movieTitle: string;
  ageRating: AgeRating;
  durationMinutes: number;
  auditoriumId: number;
  auditoriumName: string;
  auditoriumType: string;
  startTime: string;
  endTime: string;
  basePrice: number;
}
export interface Seat {
  seatId: number;
  row: string;
  number: number;
  seatType: "STANDARD" | "VIP" | "SWEETBOX";
  pairSeatId: number | null;
  status: "AVAILABLE" | "HOLDING" | "BOOKED";
  heldByMe: boolean;
}
export interface Hold {
  holdToken: string;
  showtimeId: number;
  seatIds: number[];
  serverTime: string;
  holdUntil: string;
  extensionUsed: boolean;
}
export interface SeatMap {
  showtimeId: number;
  serverTime: string;
  seats: Seat[];
  ownHolds: Hold[];
}
export interface Quote {
  tickets: {
    seatId: number;
    basePrice: number;
    roomSurcharge: number;
    seatSurcharge: number;
    timeSurcharge: number;
    finalPrice: number;
  }[];
  total: number;
}
export interface Concession {
  id: number;
  name: string;
  price: number;
  active: boolean;
  combo: boolean;
  availableQuantity?: number;
  lowStock?: boolean;
  components?: { itemId: number; quantity: number }[];
}
export interface CartLine {
  itemId: number;
  quantity: number;
}
export interface Ticket {
  ticketId: number;
  ticketCode: string;
  seatId: number;
  seatLabel: string;
  priceSnapshot: number;
  status: "VALID" | "USED" | "CANCELLED";
  showtimeId: number;
  movieTitle: string;
  auditoriumName: string;
  startTime: string;
  ageRating: AgeRating;
  qrPayload: string;
}
export interface Payment {
  paymentStatus: "PENDING" | "PAID" | "FAILED";
  paymentMethod: "CASH" | "VIETQR" | "GATEWAY_ONLINE";
  amount: number;
  paidAt?: string;
  cashReceived?: number;
  change?: number;
  qrPayload?: string;
  bankName?: string;
  bankAccount?: string;
  transferContent?: string;
}
export interface Booking {
  bookingId: number;
  bookingCode: string;
  status: "PENDING" | "PAID" | "CANCELLED" | "EXCEPTION_PENDING_DECISION";
  channel: "POS" | "ONLINE";
  showtime: Showtime;
  holdUntil: string;
  serverTime: string;
  plannedSeats: { seatId: number; seatLabel: string; price: number }[];
  tickets: Ticket[];
  concessions: {
    itemId: number;
    name: string;
    quantity: number;
    unitPriceSnapshot: number;
  }[];
  totalAmount: number;
  payment?: Payment;
  exceptionReason?: string;
  refunds?: Refund[];
  refundStatus?: "NONE" | "PARTIAL" | "FULL";
}
export interface BookingInput {
  showtimeId: number;
  holdToken: string;
  concessions: CartLine[];
  ageConfirmed?: boolean;
}
export interface Refund {
  refundId: number;
  bookingId: number;
  refundAmount: number;
  ticketIds: number[];
  status: string;
  cashConfirmedAt: string;
  refundStatus: "NONE" | "PARTIAL" | "FULL";
}
export interface Eligibility {
  bookingId: number;
  bookingCode: string;
  channel: "POS" | "ONLINE";
  showtimeId: number;
  startsAt: string;
  serverTime: string;
  tickets: { ticketId: number; ticketCode: string; seatId: number; seatLabel: string; priceSnapshot: number; status: Ticket["status"]; eligible: boolean; reason?: string; refundableAmount: number }[];
}
export interface Inventory {
  itemId: number;
  itemName: string;
  stockQuantity: number;
  lowStockThreshold: number;
}
export interface Restock {
  movementId: number;
  itemId: number;
  itemName: string;
  quantity: number;
  beforeQuantity: number;
  afterQuantity: number;
  managerId: number;
  managerName: string;
  note: string;
  createdAt: string;
}
export interface Staff {
  id: number;
  username: string;
  fullName: string;
  email: string;
  phone: string;
  status: "ACTIVE" | "INACTIVE";
}
export interface PricingRule {
  id: number;
  ruleCode: string;
  ruleType: string;
  targetRef: string;
  amount: number;
  description: string;
}
export interface Reports {
  revenue: { period: ReportPeriod; totalRevenue: number; refundAmount: number; netRevenue: number; exceptionPaidAmount: number };
  breakdown: { period: ReportPeriod; ticketRevenue: number; concessionRevenue: number; ticketRefundAmount: number; netTicketRevenue: number; netRevenue: number };
  channels: { period: ReportPeriod; posRevenue: number; onlineRevenue: number; channels: { channel: string; grossRevenue: number; refundAmount: number; netRevenue: number }[] };
  count: { period: ReportPeriod; ticketCount: number; grossTicketCount: number; cancelledTicketCount: number };
  occupancy: { period: ReportPeriod; seatsSold: number; totalSeats: number; occupancyRate: number; showtimes: { showtimeId: number; movieId: number; startsAt: string; seatsSold: number; totalSeats: number; occupancyRate: number }[] };
  alerts: (Inventory & { status: "LOW_STOCK" | "OUT_OF_STOCK" })[];
  ranking: { period: ReportPeriod; direction: "ASC" | "DESC"; movies: {
    movieId: number;
    title: string;
    ticketCount: number;
    revenue: number;
    totalRevenue: number;
    refundAmount: number;
  }[] };
  rankingLow: { period: ReportPeriod; direction: "ASC" | "DESC"; movies: Reports["ranking"]["movies"] };
}
export interface ReportPeriod {
  from: string;
  to: string;
  timezone: string;
  basis: string;
  asOf: string;
}
