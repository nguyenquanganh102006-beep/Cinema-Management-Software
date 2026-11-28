import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import type { Role } from "./api";
import { AuthProvider } from "./auth/AuthProvider";
import { BookingFlowProvider } from "./booking/BookingFlowProvider";
import { PortalLayout } from "./layout/PortalLayout";
import { AccountPanel } from "./components/AccountPanel";
import {
  MovieListPage,
  MovieDetailPage,
  ShowtimeSearchPage,
  BookingPage,
  CheckoutPage,
  PaymentReturnPage,
  BookingHistoryPage,
  BookingDetailPage,
} from "./pages/BookingPages";
import {
  DashboardPage,
  MoviesPage,
  AuditoriumsPage,
  SeatLayoutPage,
  ShowtimesPage,
  PricingPage,
  ConcessionsPage,
  InventoryPage,
  RestockHistoryPage,
  StaffPage,
  ReportsPage,
} from "./pages/ManagementPages";
import { RefundPage, TicketValidationPage } from "./pages/StaffPages";
import "./styles.css";
import "./print.css";
function Portal({ role }: { role: Role }) {
  const location = useLocation();
  if (
    location.pathname === "/payment-return" ||
    location.pathname === "/payments/vnpay/return"
  )
    return <PaymentReturnPage />;
  return (
    <AuthProvider role={role}>
      <BookingFlowProvider>
        <PortalLayout>
          <Routes>
            <Route path="/account" element={<AccountPanel />} />
            {role === "CUSTOMER" && (
              <>
                <Route path="/movies" element={<MovieListPage />} />
                <Route path="/movies/:id" element={<MovieDetailPage />} />
                <Route path="/showtimes/:id/book" element={<BookingPage />} />
                <Route path="/checkout" element={<CheckoutPage />} />
                <Route path="/bookings" element={<BookingHistoryPage />} />
                <Route path="/bookings/:id" element={<BookingDetailPage />} />
              </>
            )}
            {role === "STAFF" && (
              <>
                <Route path="/pos" element={<ShowtimeSearchPage />} />
                <Route path="/pos/showtimes/:id" element={<BookingPage />} />
                <Route path="/pos/checkout" element={<CheckoutPage />} />
                <Route
                  path="/pos/bookings/:id/receipt"
                  element={<BookingDetailPage />}
                />
                <Route path="/transactions" element={<BookingHistoryPage />} />
                <Route
                  path="/transactions/:id"
                  element={<BookingDetailPage />}
                />
                <Route path="/refunds" element={<RefundPage />} />
                <Route path="/gate" element={<TicketValidationPage />} />
              </>
            )}
            {role === "MANAGER" && (
              <>
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/movies" element={<MoviesPage />} />
                <Route path="/auditoriums" element={<AuditoriumsPage />} />
                <Route
                  path="/auditoriums/:id/seats"
                  element={<SeatLayoutPage />}
                />
                <Route path="/showtimes" element={<ShowtimesPage />} />
                <Route path="/pricing" element={<PricingPage />} />
                <Route path="/concessions" element={<ConcessionsPage />} />
                <Route path="/inventory" element={<InventoryPage />} />
                <Route
                  path="/inventory/history"
                  element={<RestockHistoryPage />}
                />
                <Route path="/staff" element={<StaffPage />} />
                <Route path="/reports" element={<ReportsPage />} />
              </>
            )}
            <Route
              path="*"
              element={
                <Navigate
                  replace
                  to={
                    role === "MANAGER"
                      ? "/dashboard"
                      : role === "STAFF"
                        ? "/pos"
                        : "/movies"
                  }
                />
              }
            />
          </Routes>
        </PortalLayout>
      </BookingFlowProvider>
    </AuthProvider>
  );
}
export function App({ role }: { role: Role }) {
  return (
    <BrowserRouter>
      <Portal role={role} />
    </BrowserRouter>
  );
}
