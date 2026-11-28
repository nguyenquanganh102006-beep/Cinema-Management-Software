import type {
  Movie,
  Showtime,
  Seat,
  Concession,
  Auditorium,
  PricingRule,
  Inventory,
  Staff,
} from "../types";
export function fixtures() {
  const start = new Date(Date.now() + 3600000);
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
  const movies: Movie[] = [
    {
      id: 1,
      title: "Chuyến tàu ánh sáng",
      durationMinutes: 110,
      ageRating: "T18",
      releaseDate: date,
      active: true,
      description: "Một hành trình qua những miền ký ức.",
      genre: "Phiêu lưu",
      director: "Đạo diễn mẫu",
      language: "Tiếng Việt",
    },
    {
      id: 2,
      title: "Mùa hè trong veo",
      durationMinutes: 95,
      ageRating: "P",
      releaseDate: date,
      active: true,
      description: "Câu chuyện dành cho cả gia đình.",
      genre: "Gia đình",
    },
    {
      id: 3,
      title: "Bí mật thành phố",
      durationMinutes: 100,
      ageRating: "T13",
      releaseDate: date,
      active: true,
    },
    {
      id: 4,
      title: "Sau cơn mưa",
      durationMinutes: 115,
      ageRating: "T16",
      releaseDate: date,
      active: true,
    },
  ];
  const rooms: Auditorium[] = [
    {
      id: 1,
      name: "Phòng 01",
      auditoriumType: "STANDARD_2D",
      totalSeats: 12,
      active: true,
    },
    {
      id: 2,
      name: "Phòng 3D",
      auditoriumType: "3D",
      totalSeats: 12,
      active: true,
    },
    {
      id: 3,
      name: "IMAX",
      auditoriumType: "IMAX",
      totalSeats: 12,
      active: true,
    },
  ];
  const showtimes: Showtime[] = movies.map((m, i) => ({
    id: i + 1,
    movieId: m.id,
    movieTitle: m.title,
    ageRating: m.ageRating,
    durationMinutes: m.durationMinutes,
    auditoriumId: 1,
    auditoriumName: "Phòng 01",
    auditoriumType: "STANDARD_2D",
    startTime: new Date(start.getTime() + i * 9000000).toISOString(),
    endTime: new Date(
      start.getTime() + i * 9000000 + (m.durationMinutes + 15) * 60000,
    ).toISOString(),
    basePrice: 70000,
  }));
  const seats: Seat[] = Array.from({ length: 12 }, (_, i) => ({
    seatId: i + 1,
    row: i < 6 ? "A" : "B",
    number: (i % 6) + 1,
    seatType: i >= 10 ? "SWEETBOX" : i >= 6 ? "VIP" : "STANDARD",
    pairSeatId: i === 10 ? 12 : i === 11 ? 11 : null,
    status: "AVAILABLE",
    heldByMe: false,
  }));
  const concessions: Concession[] = [
    {
      id: 1,
      name: "Bắp rang",
      price: 40000,
      active: true,
      combo: false,
      availableQuantity: 50,
      lowStock: false,
    },
    {
      id: 2,
      name: "Nước ngọt",
      price: 25000,
      active: true,
      combo: false,
      availableQuantity: 4,
      lowStock: true,
    },
    {
      id: 3,
      name: "Combo đôi",
      price: 80000,
      active: true,
      combo: true,
      availableQuantity: 2,
      lowStock: true,
      components: [
        { itemId: 1, quantity: 1 },
        { itemId: 2, quantity: 2 },
      ],
    },
    {
      id: 4,
      name: "Bánh snack",
      price: 20000,
      active: true,
      combo: false,
      availableQuantity: 0,
      lowStock: true,
    },
  ];
  const inventory: Inventory[] = concessions
    .filter((i) => !i.combo)
    .map((i) => ({
      itemId: i.id,
      itemName: i.name,
      stockQuantity: i.availableQuantity ?? 0,
      lowStockThreshold: 10,
    }));
  const pricing: PricingRule[] = [
    {
      id: 1,
      ruleCode: "BASE_STANDARD",
      ruleType: "BASE_PRICE",
      targetRef: "STANDARD_2D",
      amount: 70000,
      description: "Giá cơ bản",
    },
    {
      id: 2,
      ruleCode: "SUR_TIME",
      ruleType: "TIME_SURCHARGE",
      targetRef: "WEEKEND_OR_AFTER_18H",
      amount: 10000,
      description: "Cuối tuần HOẶC sau 18:00, áp dụng một lần",
    },
    {
      id: 3,
      ruleCode: "SUR_VIP",
      ruleType: "SEAT_SURCHARGE",
      targetRef: "VIP",
      amount: 15000,
      description: "Ghế VIP",
    },
    {
      id: 4,
      ruleCode: "SUR_SWEETBOX",
      ruleType: "SEAT_SURCHARGE",
      targetRef: "SWEETBOX",
      amount: 15000,
      description: "Mỗi ghế Sweetbox",
    },
  ];
  const staff: Staff[] = [
    {
      id: 2,
      username: "staff01",
      fullName: "Nhân viên mẫu",
      email: "staff01@mock.invalid",
      phone: "0901000002",
      status: "ACTIVE",
    },
  ];
  return {
    movies,
    rooms,
    showtimes,
    seats,
    concessions,
    inventory,
    pricing,
    staff,
  };
}
