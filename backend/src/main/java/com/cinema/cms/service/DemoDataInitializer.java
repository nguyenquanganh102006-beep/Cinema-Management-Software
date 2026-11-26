package com.cinema.cms.service;

import com.cinema.cms.entity.*;
import com.cinema.cms.dto.PricingQuoteResponse;
import com.cinema.cms.dto.TicketPrice;
import com.cinema.cms.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class DemoDataInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String password;
    private final String pin;
    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final Clock clock;
    public DemoDataInitializer(UserRepository users, PasswordEncoder passwords,
            @Value("${app.demo.password}") String password,
            @Value("${app.demo.manager-pin}") String pin,
            JdbcTemplate jdbc, PricingService pricing, Clock clock) {
        this.users = users; this.passwords = passwords; this.password = password; this.pin = pin;
        this.jdbc = jdbc; this.pricing = pricing; this.clock = clock;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (password.length() < 8) throw new IllegalArgumentException("DEMO_PASSWORD must be at least 8 characters");
        AuthService.checkPasswordLength(password);
        if (!pin.matches("[0-9]{6,12}")) throw new IllegalArgumentException("DEMO_MANAGER_PIN must be 6..12 digits");
        seed("manager01", "0901000001", UserRole.MANAGER);
        seed("staff01", "0901000002", UserRole.STAFF);
        seed("customer01", "0901000003", UserRole.CUSTOMER);
        seed("staff02", "0901000004", UserRole.STAFF);
        seed("staff03", "0901000005", UserRole.STAFF);
        seed("staff04", "0901000006", UserRole.STAFF);
        users.flush();
        seedShowcaseSales();
        seedHistoricalSales();
        expandAuditoriumLayouts();
        seedMovieCatalogAndShowtimes();
        seedConcessionImages();
    }
    private void seed(String username, String phone, UserRole role) {
        if (users.existsByUsername(username)) return;
        String fullName = username.startsWith("staff") ? "Nhân viên " + username.substring("staff".length()) : username;
        User user = new User(username, fullName, username + "@cinema.local", phone, passwords.encode(password), role);
        if (role == UserRole.MANAGER) user.setManagerPinHash(passwords.encode(pin));
        users.save(user);
    }

    /** Expands local demo rooms to twenty seats, with two adjacent Sweetbox pairs in the last row. */
    private void expandAuditoriumLayouts() {
        List<DemoRoom> rooms = jdbc.query("""
                select auditorium_id,name,auditorium_type::text auditorium_type
                from auditoriums where is_active=true order by auditorium_id
                """, (rs, row) -> new DemoRoom(rs.getInt("auditorium_id"),rs.getString("name"),rs.getString("auditorium_type")));
        for (DemoRoom room : rooms) {
            Integer count = jdbc.queryForObject("select count(*) from seats where auditorium_id=?",Integer.class,room.id());
            int current = count == null ? 0 : count;
            List<Long> added = new ArrayList<>();
            Long pairFirst = null;
            Long pairSecond = null;
            if (current < 20) {
                List<String> rows = List.of("D","E","F","G","H","J","K","L","M","N").stream()
                        .filter(label -> Boolean.FALSE.equals(jdbc.queryForObject(
                                "select exists(select 1 from seats where auditorium_id=? and seat_row=?)",
                                Boolean.class,room.id(),label)))
                        .limit(4).toList();
                int sweetboxCount = jdbc.queryForObject(
                        "select count(*) from seats where auditorium_id=? and seat_type='SWEETBOX'",Integer.class,room.id());
                boolean addPair = sweetboxCount < 4;
                int toAdd = 20-current;
                int next = 0;
                for (int rowIndex=0; rowIndex<rows.size() && next<toAdd; rowIndex++) {
                    for (int number=1; number<=5 && next<toAdd; number++) {
                        boolean sweet = addPair && rowIndex==0 && (number==2 || number==3);
                        Long seatId = jdbc.queryForObject("""
                                insert into seats (auditorium_id,seat_row,seat_number,seat_type)
                                values (?,?,?,?::seat_type_enum) returning seat_id
                                """,Long.class,room.id(),rows.get(rowIndex),number,sweet ? "SWEETBOX" : "STANDARD");
                        added.add(seatId);
                        if (sweet && number==2) pairFirst=seatId;
                        if (sweet && number==3) pairSecond=seatId;
                        next++;
                    }
                }
                if (pairFirst!=null && pairSecond!=null) {
                    jdbc.update("update seats set pair_seat_id=? where seat_id=?",pairSecond,pairFirst);
                    jdbc.update("update seats set pair_seat_id=? where seat_id=?",pairFirst,pairSecond);
                }
                int actual = jdbc.queryForObject("select count(*) from seats where auditorium_id=?",Integer.class,room.id());
                jdbc.update("update auditoriums set total_seats=? where auditorium_id=?",actual,room.id());
            }
            if (!added.isEmpty()) {
                jdbc.update("""
                        insert into showtime_seats (showtime_id,seat_id,status,updated_at)
                        select s.showtime_id,?, 'AVAILABLE', now() from showtimes s
                        where s.auditorium_id=? on conflict (showtime_id,seat_id) do nothing
                        """,added.getFirst(),room.id());
                for (int i=1;i<added.size();i++) {
                    jdbc.update("""
                            insert into showtime_seats (showtime_id,seat_id,status,updated_at)
                            select s.showtime_id,?, 'AVAILABLE', now() from showtimes s
                            where s.auditorium_id=? on conflict (showtime_id,seat_id) do nothing
                            """,added.get(i),room.id());
                }
            }
            normalizeAuditoriumSeatRows(room);
        }
    }

    /** Keeps Sweetbox seats together in the last row and normal seats in four clean rows. */
    private void normalizeAuditoriumSeatRows(DemoRoom room) {
        List<DemoSeatLayout> regular = jdbc.query("""
                select seat_id,pair_seat_id from seats
                where auditorium_id=? and seat_type<>'SWEETBOX' order by seat_row,seat_number
                """,(rs,row)->new DemoSeatLayout(rs.getLong("seat_id"),rs.getObject("pair_seat_id",Long.class)),room.id());
        List<DemoSeatLayout> sweetbox = jdbc.query("""
                select seat_id,pair_seat_id from seats
                where auditorium_id=? and seat_type='SWEETBOX'
                order by least(seat_id,coalesce(pair_seat_id,seat_id)),seat_id
                """,(rs,row)->new DemoSeatLayout(rs.getLong("seat_id"),rs.getObject("pair_seat_id",Long.class)),room.id());
        if (regular.size()!=16 || sweetbox.size()!=4)
            throw new IllegalStateException("Each local demo auditorium must have sixteen regular seats and four Sweetbox seats");

        // Move all rows to unique temporary labels first to avoid the per-room row/number unique constraint.
        jdbc.update("update seats set seat_row='T'||left(seat_row,1)||lpad(seat_number::text,2,'0') where auditorium_id=?",room.id());
        for (int index=0;index<regular.size();index++) {
            String row=String.valueOf((char)('A'+index/4));
            jdbc.update("update seats set seat_row=?,seat_number=? where seat_id=?",row,index%4+1,regular.get(index).id());
        }
        for (int index=0;index<sweetbox.size();index++)
            jdbc.update("update seats set seat_row='E',seat_number=? where seat_id=?",index+1,sweetbox.get(index).id());
    }

    /** Adds 35 database-backed titles and at least five future showings for each of 20 current titles. */
    private void seedMovieCatalogAndShowtimes() {
        List<DemoMovieSeed> catalog = List.of(
                new DemoMovieSeed("Interstellar",169,"T13","2014-11-07","Khoa học viễn tưởng"),
                new DemoMovieSeed("Inception",148,"T13","2010-07-16","Khoa học viễn tưởng"),
                new DemoMovieSeed("The Dark Knight",152,"T16","2008-07-18","Hành động"),
                new DemoMovieSeed("Oppenheimer",180,"T16","2023-07-21","Tiểu sử"),
                new DemoMovieSeed("Barbie",114,"P","2023-07-21","Hài"),
                new DemoMovieSeed("Wicked",160,"P","2024-11-22","Nhạc kịch"),
                new DemoMovieSeed("The Wild Robot",102,"P","2024-09-27","Hoạt hình"),
                new DemoMovieSeed("A Quiet Place: Day One",99,"T16","2024-06-28","Kinh dị"),
                new DemoMovieSeed("Beetlejuice Beetlejuice",105,"T13","2024-09-06","Hài giả tưởng"),
                new DemoMovieSeed("Twisters",122,"T13","2024-07-19","Hành động"),
                new DemoMovieSeed("Moana 2",100,"P","2024-11-27","Hoạt hình"),
                new DemoMovieSeed("Mufasa: The Lion King",118,"P","2024-12-20","Gia đình"),
                new DemoMovieSeed("Sonic the Hedgehog 3",110,"P","2024-12-20","Phiêu lưu"),
                new DemoMovieSeed("Despicable Me 4",94,"P","2024-07-03","Hoạt hình"),
                new DemoMovieSeed("The Garfield Movie",101,"P","2024-05-24","Hoạt hình"),
                new DemoMovieSeed("The Boy and the Heron",124,"T13","2023-12-08","Hoạt hình"),
                new DemoMovieSeed("Godzilla Minus One",125,"T13","2023-12-01","Khoa học viễn tưởng"),
                new DemoMovieSeed("Mission: Impossible – Dead Reckoning",163,"T13","2023-07-12","Hành động"),
                new DemoMovieSeed("Top Gun: Maverick",130,"T13","2022-05-27","Hành động"),
                new DemoMovieSeed("The Batman",176,"T13","2022-03-04","Hành động"),
                new DemoMovieSeed("Coco",105,"P","2017-11-22","Hoạt hình"),
                new DemoMovieSeed("The Lion King",118,"P","2019-07-19","Gia đình"),
                new DemoMovieSeed("Frozen II",103,"P","2019-11-22","Hoạt hình"),
                new DemoMovieSeed("Dungeons & Dragons: Honor Among Thieves",134,"T13","2023-03-31","Phiêu lưu"),
                new DemoMovieSeed("The Super Mario Bros. Movie",92,"P","2023-04-05","Hoạt hình"),
                new DemoMovieSeed("The Creator",133,"T13","2023-09-29","Khoa học viễn tưởng"),
                new DemoMovieSeed("A Minecraft Movie",101,"P","2025-04-04","Phiêu lưu"),
                new DemoMovieSeed("Superman",129,"T13","2025-07-11","Hành động"),
                new DemoMovieSeed("The Fantastic Four: First Steps",115,"T13","2025-07-25","Khoa học viễn tưởng"),
                new DemoMovieSeed("How to Train Your Dragon",125,"P","2025-06-13","Phiêu lưu"),
                new DemoMovieSeed("Jurassic World Rebirth",134,"T13","2025-07-02","Phiêu lưu"),
                new DemoMovieSeed("The Bad Guys 2",104,"P","2025-08-01","Hoạt hình"),
                new DemoMovieSeed("Zootopia 2",108,"P","2025-11-26","Hoạt hình"),
                new DemoMovieSeed("John Wick: Chapter 4",169,"T18","2023-03-24","Hành động"),
                new DemoMovieSeed("Tenet",150,"T13","2020-09-03","Khoa học viễn tưởng"));
        for (int index=0;index<catalog.size();index++) {
            DemoMovieSeed item = catalog.get(index);
            Integer exists = jdbc.queryForObject("select count(*) from movies where lower(title)=lower(?)",Integer.class,item.title());
            if (exists==null || exists==0) {
                jdbc.update("""
                        insert into movies (title,duration_minutes,age_rating,release_date,is_active,description,genre,country,language)
                        values (?,?,?::age_rating_enum,?,?,?,?,?,?)
                        """,item.title(),item.duration(),item.rating(),LocalDate.parse(item.releaseDate()),index<7,
                        "Phim trong danh mục dùng chung của rạp. Lịch chiếu và giá vé được đọc trực tiếp từ cơ sở dữ liệu.",
                        item.genre(),"Quốc tế","Phụ đề tiếng Việt");
            }
            String poster=String.format(Locale.ROOT,"/posters/movie-new-%02d.%s",index+1,index==5?"png":"jpg");
            jdbc.update("update movies set poster=? where lower(title)=lower(?)",poster,item.title());
        }
        jdbc.update("""
                update movies set is_active=true
                where title in ('Dune: Part Two','Mai','Doraemon: Nobita va Ban Giao Huong Dia Cau',
                  'Godzilla x Kong: The New Empire','Kung Fu Panda 4','Inside Out 2','Deadpool & Wolverine',
                  'Furiosa: A Mad Max Saga','Joker: Folie a Deux','Venom: The Last Dance','Gladiator 2',
                  'Lat Mat 7: Mot Dieu Uoc','Spider-Man: Beyond the Spider-Verse','Avatar 3: The Seed Bearer',
                  'Interstellar','Inception','The Dark Knight','Oppenheimer','Barbie','Wicked','The Wild Robot')
                """);
        jdbc.update("update movies set is_active=false where title='Mickey 17'");
        for (int i=7;i<catalog.size();i++)
            jdbc.update("update movies set is_active=false where lower(title)=lower(?)",catalog.get(i).title());

        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        List<DemoRoom> rooms = jdbc.query("""
                select auditorium_id,name,auditorium_type::text auditorium_type from auditoriums
                where is_active=true order by auditorium_id
                """,(rs,row)->new DemoRoom(rs.getInt("auditorium_id"),rs.getString("name"),rs.getString("auditorium_type")));
        List<DemoMovie> currentMovies = jdbc.query("""
                select movie_id,title,age_rating::text age_rating,duration_minutes from movies
                where is_active=true and release_date<=? order by movie_id
                """,(rs,row)->new DemoMovie(rs.getLong("movie_id"),rs.getString("title"),rs.getString("age_rating"),rs.getInt("duration_minutes")),today);
        LocalTime[] slots={LocalTime.of(10,0),LocalTime.of(14,0),LocalTime.of(18,0)};
        int movieIndex=0;
        for (DemoMovie movie:currentMovies) {
            Integer count=jdbc.queryForObject("select count(*) from showtimes where movie_id=? and start_time>? and start_time<?",
                    Integer.class,movie.id(),Timestamp.from(now),Timestamp.from(now.plus(Duration.ofDays(22))));
            int added=0;
            for (int day=1;day<=21 && (count==null?0:count)+added<5;day++) {
                LocalDate date=today.plusDays(day);
                for (int slot=0;slot<slots.length && (count==null?0:count)+added<5;slot++) {
                    for (int shift=0;shift<rooms.size() && (count==null?0:count)+added<5;shift++) {
                        DemoRoom room=rooms.get((movieIndex+day+slot+shift)%rooms.size());
                        Instant start=date.atTime(slots[(slot+movieIndex)%slots.length]).atZone(zone).toInstant();
                        Instant end=start.plus(Duration.ofMinutes(movie.durationMinutes()+15L));
                        Integer conflicts=jdbc.queryForObject("select count(*) from showtimes where auditorium_id=? and start_time<? and end_time>?",
                                Integer.class,room.id(),Timestamp.from(end),Timestamp.from(start));
                        if (conflicts!=null && conflicts>0) continue;
                        Long showtimeId=jdbc.queryForObject("""
                                insert into showtimes (movie_id,movie_title_snapshot,age_rating_snapshot,duration_minutes_snapshot,
                                    auditorium_id,auditorium_name_snapshot,auditorium_type_snapshot,start_time,end_time,base_price)
                                values (?,?,?::age_rating_enum,?,?,?,?,?,?,70000) returning showtime_id
                                """,Long.class,movie.id(),movie.title(),movie.ageRating(),movie.durationMinutes(),room.id(),room.name(),room.type(),
                                Timestamp.from(start),Timestamp.from(end));
                        jdbc.update("""
                                insert into showtime_seats (showtime_id,seat_id,status,updated_at)
                                select ?,seat_id,'AVAILABLE',? from seats where auditorium_id=?
                                on conflict (showtime_id,seat_id) do nothing
                                """,showtimeId,Timestamp.from(now),room.id());
                        added++;
                        break;
                    }
                }
            }
            movieIndex++;
        }
    }

    private void seedConcessionImages() {
        String[] imageByItem={
                "/concessions/popcorn-cinema.jpg","/concessions/popcorn-cinema.jpg",
                "/concessions/nachos-cheese.jpg","/concessions/nachos-cheese.jpg",
                "/concessions/popcorn-cinema.jpg","/concessions/popcorn-cinema.jpg",
                "/concessions/soda-cups.jpg","/concessions/soda-cups.jpg",
                "/concessions/bubble-tea.jpg","/concessions/soda-cups.jpg",
                "/concessions/hotdog.jpg","/concessions/popcorn-cinema.jpg",
                "/concessions/nachos-cheese.jpg","/concessions/popcorn-cinema.jpg","/concessions/hotdog.jpg"};
        for (int index=0;index<imageByItem.length;index++)
            jdbc.update("update concession_items set image_url=? where item_id=?",imageByItem[index],index+1);
    }

    /** Adds a small, idempotent set of local-only paid sales for manager reports and POS history. */
    private void seedShowcaseSales() {
        Integer existing = jdbc.queryForObject(
                "select count(*) from bookings where booking_code like 'DEMO-%'", Integer.class);
        if (existing != null && existing > 0) return;

        Long customerId = jdbc.queryForObject(
                "select user_id from users where username='customer01'", Long.class);
        Long staffId = jdbc.queryForObject(
                "select user_id from users where username='staff01'", Long.class);
        Instant now = clock.instant();
        List<Long> showtimeIds = jdbc.query(
                "select s.showtime_id from showtimes s "
                        + "join movies m on m.movie_id=s.movie_id "
                        + "join auditoriums a on a.auditorium_id=s.auditorium_id "
                        + "where m.is_active=true and a.is_active=true and s.start_time>? "
                        + "and s.start_time<? and exists (select 1 from showtime_seats ss "
                        + "where ss.showtime_id=s.showtime_id and ss.status='AVAILABLE') "
                        + "order by s.start_time,s.showtime_id limit 8",
                (rs, row) -> rs.getLong(1), Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(14))));
        if (showtimeIds.isEmpty()) return;

        DemoFood food = jdbc.query(
                "select ci.item_id,ci.price from concession_items ci "
                        + "join concession_inventory i on i.item_id=ci.item_id "
                        + "where ci.is_active=true and ci.is_combo=false and i.stock_quantity>0 "
                        + "order by ci.item_id limit 1",
                rs -> rs.next() ? new DemoFood(rs.getInt(1), rs.getBigDecimal(2)) : null);
        Integer initialFoodStock = food == null ? 0 : jdbc.queryForObject(
                "select stock_quantity from concession_inventory where item_id=?", Integer.class, food.itemId());
        int remainingFood = initialFoodStock == null ? 0 : initialFoodStock;
        DateTimeFormatter codeDate = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

        for (int index = 0; index < showtimeIds.size(); index++) {
            Long showtimeId = showtimeIds.get(index);
            int seatCount = index % 3 == 2 ? 2 : 1;
            List<Long> seatIds = jdbc.query(
                    "select ss.seat_id from showtime_seats ss join seats s on s.seat_id=ss.seat_id "
                            + "where ss.showtime_id=? and ss.status='AVAILABLE' "
                            + "order by case when s.seat_type='STANDARD' then 0 else 1 end,s.seat_row,s.seat_number limit ?",
                    (rs, row) -> rs.getLong(1), showtimeId, seatCount);
            if (seatIds.isEmpty()) continue;

            PricingQuoteResponse quote = pricing.quote(showtimeId, seatIds);
            boolean includeFood = food != null && remainingFood > 0 && index % 2 == 0;
            if (includeFood) remainingFood--;
            BigDecimal foodAmount = includeFood ? food.price() : BigDecimal.ZERO;
            BigDecimal total = quote.ticketTotal().add(foodAmount);
            boolean pos = index % 2 == 0;
            String bookingCode = "DEMO-" + codeDate.format(now) + "-" + String.format(Locale.ROOT, "%02d", index + 1);
            String channel = pos ? "POS" : "ONLINE";
            Long bookingUserId = pos ? null : customerId;
            Long bookingStaffId = pos ? staffId : null;
            Instant createdAt = now.minus(Duration.ofMinutes((showtimeIds.size() - index) * 4L));
            Long bookingId = jdbc.queryForObject(
                    "insert into bookings (booking_code,showtime_id,user_id,staff_id,channel,total_amount,status,"
                            + "created_at,age_confirmed_at,ticket_total,concession_total,paid_at,version) "
                            + "values (?,?,?,?,?::booking_channel_enum,?,'PAID',?,?,?,?,?,0) returning booking_id",
                    Long.class, bookingCode, showtimeId, bookingUserId, bookingStaffId, channel, total,
                    Timestamp.from(createdAt), Timestamp.from(createdAt), quote.ticketTotal(), foodAmount,
                    Timestamp.from(createdAt));

            for (TicketPrice ticketPrice : quote.seats()) {
                int booked = jdbc.update(
                        "update showtime_seats set status='BOOKED',owner_id=null,hold_token=null,hold_until=null,"
                                + "extension_used=false,updated_at=? where showtime_id=? and seat_id=? and status='AVAILABLE'",
                        Timestamp.from(createdAt), showtimeId, ticketPrice.seatId());
                if (booked != 1) throw new IllegalStateException("Could not reserve a local demo seat");
                jdbc.update(
                        "insert into booking_seats (booking_id,seat_id,quoted_price) values (?,?,?)",
                        bookingId, ticketPrice.seatId(), ticketPrice.finalPrice());
                Long showtimeSeatId = jdbc.queryForObject(
                        "select showtime_seat_id from showtime_seats where showtime_id=? and seat_id=?",
                        Long.class, showtimeId, ticketPrice.seatId());
                jdbc.update(
                        "insert into tickets (ticket_code,booking_id,showtime_seat_id,price_snapshot,status,issued_at) "
                                + "values (?,?,?,?,'VALID',?)",
                        bookingCode + "-T" + ticketPrice.seatId(), bookingId, showtimeSeatId,
                        ticketPrice.finalPrice(), Timestamp.from(createdAt));
            }

            if (includeFood) seedFoodLine(bookingId, food, createdAt);
            seedPaidPayment(bookingId, bookingCode, total, pos, staffId, createdAt);
        }
    }

    private void seedFoodLine(Long bookingId, DemoFood food, Instant at) {
        Integer stock = jdbc.queryForObject(
                "select stock_quantity from concession_inventory where item_id=? for update",
                Integer.class, food.itemId());
        if (stock == null || stock < 1) throw new IllegalStateException("No stock for a local demo sale");
        jdbc.update(
                "insert into booking_concessions (booking_id,item_id,quantity,unit_price_snapshot) values (?,?,1,?)",
                bookingId, food.itemId(), food.price());
        jdbc.update("update concession_inventory set stock_quantity=? where item_id=?", stock - 1, food.itemId());
        jdbc.update(
                "insert into inventory_transactions (item_id,movement_type,quantity,before_quantity,after_quantity,manager_id,note,created_at) "
                        + "values (?,'SALE',-1,?,?,null,'Local demo sale',?)",
                food.itemId(), stock, stock - 1, Timestamp.from(at));
    }

    private void seedPaidPayment(Long bookingId, String bookingCode, BigDecimal total,
            boolean pos, Long staffId, Instant at) {
        if (pos) {
            jdbc.update(
                    "insert into payments (booking_id,payment_method,amount,status,confirmed_by_staff_id,cash_received,paid_at) "
                            + "values (?,'CASH',?,'PAID',?,?,?)",
                    bookingId, total, staffId, total, Timestamp.from(at));
        } else {
            jdbc.update(
                    "insert into payments (booking_id,payment_method,amount,status,gateway_transaction_code,webhook_signature,paid_at,vnp_txn_ref,expires_at) "
                            + "values (?,'GATEWAY_ONLINE',?,'PAID',?,'LOCAL_DEMO',?,?,?)",
                    bookingId, total, bookingCode, Timestamp.from(at), bookingCode,
                    Timestamp.from(at.plus(Duration.ofHours(2))));
        }
    }

    /** Creates a local-only, reproducible sales history: about 1,000 sold tickets plus 50 refunded tickets. */
    private void seedHistoricalSales() {
        Integer currentTickets = jdbc.queryForObject("""
                select count(*) from tickets t join bookings b on b.booking_id=t.booking_id
                join payments p on p.booking_id=b.booking_id
                where b.status='PAID' and p.status='PAID' and t.status in ('VALID','USED')
                """, Integer.class);
        Integer currentRefunded = jdbc.queryForObject("select count(*) from refund_ticket_links", Integer.class);
        int ticketsToCreate = Math.max(0, 1000 - (currentTickets == null ? 0 : currentTickets));
        int refundsToCreate = Math.min(ticketsToCreate, Math.max(0, 50 - (currentRefunded == null ? 0 : currentRefunded)));
        if (ticketsToCreate == 0) return;

        Long customerId = jdbc.queryForObject(
                "select user_id from users where username='customer01'", Long.class);
        Long managerId = jdbc.queryForObject(
                "select user_id from users where username='manager01'", Long.class);
        List<Long> staffIds = jdbc.query(
                "select user_id from users where username in ('staff01','staff02','staff03','staff04') order by username",
                (rs, row) -> rs.getLong(1));
        List<DemoRoom> rooms = jdbc.query("""
                select auditorium_id,name,auditorium_type::text auditorium_type
                from auditoriums where is_active=true order by auditorium_id
                """, (rs, row) -> new DemoRoom(rs.getInt("auditorium_id"),rs.getString("name"),rs.getString("auditorium_type")));
        if (rooms.isEmpty() || staffIds.isEmpty()) throw new IllegalStateException("Local demo seed requires active rooms and staff");

        Map<Integer,List<DemoSeat>> seatsByRoom = new HashMap<>();
        for (DemoRoom room : rooms) {
            List<DemoSeat> roomSeats = jdbc.query("""
                    select seat_id,seat_row,seat_number,seat_type::text seat_type
                    from seats where auditorium_id=? order by seat_row,seat_number
                    """, (rs, row) -> new DemoSeat(rs.getLong("seat_id"),rs.getString("seat_row"),
                    rs.getInt("seat_number"),rs.getString("seat_type")),room.id());
            if (!roomSeats.isEmpty()) seatsByRoom.put(room.id(),roomSeats);
        }

        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        DateTimeFormatter codeDate = DateTimeFormatter.ofPattern("yyyyMMdd",Locale.ROOT);
        int createdTickets = 0;
        int createdRefunds = 0;
        Integer existingHistoryBookings = jdbc.queryForObject(
                "select count(*) from bookings where booking_code like 'HIST-%'", Integer.class);
        int sequence = (existingHistoryBookings == null ? 0 : existingHistoryBookings) + 1;
        int showtimeSequence = 0;

        for (int daysAgo=29; daysAgo>=0 && createdTickets<ticketsToCreate; daysAgo--) {
            LocalDate date = today.minusDays(daysAgo);
            List<DemoMovie> movies = jdbc.query("""
                    select movie_id,title,age_rating::text age_rating,duration_minutes
                    from movies where is_active=true and release_date<=? order by movie_id
                    """, (rs, row) -> new DemoMovie(rs.getLong("movie_id"),rs.getString("title"),
                    rs.getString("age_rating"),rs.getInt("duration_minutes")),date);
            if (movies.isEmpty()) continue;

            for (DemoRoom room : rooms) {
                if (createdTickets>=ticketsToCreate) break;
                List<DemoSeat> physicalSeats = seatsByRoom.getOrDefault(room.id(),List.of());
                if (physicalSeats.isEmpty()) continue;
                DemoMovie movie = movies.get(showtimeSequence % movies.size());
                LocalTime[] slots = {LocalTime.of(10,0),LocalTime.of(14,0),LocalTime.of(18,0)};
                Instant startsAt = null;
                Instant endsAt = null;
                for (int slot=0; slot<slots.length; slot++) {
                    Instant candidate = date.atTime(slots[(slot+showtimeSequence)%slots.length]).atZone(zone).toInstant();
                    Instant candidateEnd = candidate.plus(Duration.ofMinutes(movie.durationMinutes()+15L));
                    Integer conflicts = jdbc.queryForObject("""
                            select count(*) from showtimes where auditorium_id=? and start_time<? and end_time>?
                            """,Integer.class,room.id(),Timestamp.from(candidateEnd),Timestamp.from(candidate));
                    if (conflicts==null || conflicts==0) { startsAt=candidate; endsAt=candidateEnd; break; }
                }
                if (startsAt==null) continue;

                Long showtimeId = jdbc.queryForObject("""
                        insert into showtimes (movie_id,movie_title_snapshot,age_rating_snapshot,duration_minutes_snapshot,
                            auditorium_id,auditorium_name_snapshot,auditorium_type_snapshot,start_time,end_time,base_price)
                        values (?,?,?::age_rating_enum,?,?,?,?,?,?,70000) returning showtime_id
                        """,Long.class,movie.id(),movie.title(),movie.ageRating(),movie.durationMinutes(),room.id(),
                        room.name(),room.type(),Timestamp.from(startsAt),Timestamp.from(endsAt));
                Instant initialAt = startsAt.minus(Duration.ofMinutes(90));
                for (DemoSeat seat : physicalSeats) {
                    jdbc.update("insert into showtime_seats (showtime_id,seat_id,status,updated_at) values (?,?,'AVAILABLE',?)",
                            showtimeId,seat.id(),Timestamp.from(initialAt));
                }

                int sellHere = Math.min(physicalSeats.size(),ticketsToCreate-createdTickets);
                List<Long> seatIds = physicalSeats.stream().limit(sellHere).map(DemoSeat::id).toList();
                PricingQuoteResponse quote = pricing.quote(showtimeId,seatIds);
                Map<Long,TicketPrice> prices = new HashMap<>();
                quote.seats().forEach(price -> prices.put(price.seatId(),price));
                int seatIndex=0;
                while (seatIndex<sellHere) {
                    boolean refundThisBooking = createdRefunds<refundsToCreate;
                    int groupSize = refundThisBooking ? 1 : Math.min(1+(sequence%4),sellHere-seatIndex);
                    List<DemoSeat> group = physicalSeats.subList(seatIndex,seatIndex+groupSize);
                    List<TicketPrice> groupPrices = group.stream().map(seat -> prices.get(seat.id())).toList();
                    BigDecimal total = groupPrices.stream().map(TicketPrice::finalPrice).reduce(BigDecimal.ZERO,BigDecimal::add);
                    int channelBucket=sequence%5;
                    boolean pos=channelBucket<3;
                    String paymentMethod=pos ? (channelBucket==1 ? "VIETQR" : "CASH") : "GATEWAY_ONLINE";
                    Long staffId=staffIds.get(sequence%staffIds.size());
                    Long bookingStaffId=pos ? staffId : null;
                    String channel=pos ? "POS" : "ONLINE";
                    String bookingCode="HIST-"+codeDate.format(date)+"-"+String.format(Locale.ROOT,"%04d",sequence);
                    Instant paidAt=startsAt.minus(Duration.ofMinutes(30L+(sequence%45)));
                    Instant createdAt=paidAt.minus(Duration.ofMinutes(2));
                    Long bookingId=jdbc.queryForObject("""
                            insert into bookings (booking_code,showtime_id,user_id,staff_id,channel,total_amount,status,
                                created_at,age_confirmed_at,ticket_total,concession_total,paid_at,version)
                            values (?,?,?,?,?::booking_channel_enum,?,'PAID',?,?,?,?,?,0) returning booking_id
                            """,Long.class,bookingCode,showtimeId,pos?null:customerId,bookingStaffId,channel,total,
                            Timestamp.from(createdAt),Timestamp.from(createdAt),total,BigDecimal.ZERO.setScale(2),Timestamp.from(paidAt));
                    List<Long> bookingTicketIds = new ArrayList<>();
                    for (int i=0;i<group.size();i++) {
                        DemoSeat seat=group.get(i);
                        TicketPrice ticketPrice=groupPrices.get(i);
                        jdbc.update("update showtime_seats set status='BOOKED',updated_at=? where showtime_id=? and seat_id=?",
                                Timestamp.from(paidAt),showtimeId,seat.id());
                        jdbc.update("insert into booking_seats (booking_id,seat_id,quoted_price) values (?,?,?)",
                                bookingId,seat.id(),ticketPrice.finalPrice());
                        Long showtimeSeatId=jdbc.queryForObject(
                                "select showtime_seat_id from showtime_seats where showtime_id=? and seat_id=?",
                                Long.class,showtimeId,seat.id());
                        String ticketStatus=refundThisBooking ? "CANCELLED" : "USED";
                        Long ticketId=jdbc.queryForObject("""
                                insert into tickets (ticket_code,booking_id,showtime_seat_id,price_snapshot,status,issued_at)
                                values (?,?,?, ?,?::ticket_status_enum,?) returning ticket_id
                                """,Long.class,bookingCode+"-T"+seat.row()+seat.number(),bookingId,showtimeSeatId,
                                ticketPrice.finalPrice(),ticketStatus,Timestamp.from(paidAt));
                        bookingTicketIds.add(ticketId);
                    }
                    seedHistoricalPayment(bookingId,bookingCode,total,paymentMethod,staffId,paidAt);
                    if (refundThisBooking) {
                        Long ticketId=bookingTicketIds.getFirst();
                        BigDecimal refundAmount=groupPrices.getFirst().finalPrice();
                        Instant refundedAt=paidAt.plus(Duration.ofMinutes(5));
                        jdbc.update("""
                                insert into refund_records (booking_id,ticket_ids_json,manager_id,refund_amount,reason,
                                    cash_confirmed_at,created_at,staff_id)
                                values (?,jsonb_build_array(?::bigint),?,?,?, ?,?,?)
                                """,bookingId,ticketId,managerId,refundAmount,"LOCAL_DEMO_REFUND",
                                Timestamp.from(refundedAt),Timestamp.from(refundedAt),staffId);
                        Long refundId=jdbc.queryForObject("select max(refund_id) from refund_records where booking_id=?",Long.class,bookingId);
                        jdbc.update("insert into refund_ticket_links (ticket_id,refund_id,price_snapshot) values (?,?,?)",
                                ticketId,refundId,refundAmount);
                        jdbc.update("update showtime_seats set status='AVAILABLE',updated_at=? where showtime_id=? and seat_id=?",
                                Timestamp.from(refundedAt),showtimeId,group.getFirst().id());
                        createdRefunds++;
                    }
                    createdTickets+=group.size();
                    seatIndex+=groupSize;
                    sequence++;
                }
                showtimeSequence++;
            }
        }
        if (createdTickets!=ticketsToCreate || createdRefunds!=refundsToCreate) {
            throw new IllegalStateException("Could not create the requested local demo sales and refund history");
        }
    }

    private void seedHistoricalPayment(Long bookingId,String bookingCode,BigDecimal total,
            String method,Long staffId,Instant paidAt) {
        if ("CASH".equals(method)) {
            jdbc.update("""
                    insert into payments (booking_id,payment_method,amount,status,confirmed_by_staff_id,cash_received,paid_at)
                    values (?,?::payment_method_enum,?,'PAID',?,?,?)
                    """,bookingId,method,total,staffId,total,Timestamp.from(paidAt));
        } else if ("VIETQR".equals(method)) {
            jdbc.update("""
                    insert into payments (booking_id,payment_method,amount,status,confirmed_by_staff_id,paid_at)
                    values (?,?::payment_method_enum,?,'PAID',?,?)
                    """,bookingId,method,total,staffId,Timestamp.from(paidAt));
        } else {
            jdbc.update("""
                    insert into payments (booking_id,payment_method,amount,status,gateway_transaction_code,
                        webhook_signature,paid_at,vnp_txn_ref,expires_at)
                    values (?,?::payment_method_enum,?,'PAID',?,'LOCAL_HIST_DEMO',?,?,?)
                    """,bookingId,method,total,"HIST-GW-"+bookingCode,Timestamp.from(paidAt),bookingCode,
                    Timestamp.from(paidAt.plus(Duration.ofHours(2))));
        }
    }

    private record DemoMovie(Long id,String title,String ageRating,int durationMinutes) {}
    private record DemoMovieSeed(String title,int duration,String rating,String releaseDate,String genre) {}
    private record DemoRoom(Integer id,String name,String type) {}
    private record DemoSeat(Long id,String row,int number,String type) {}
    private record DemoSeatLayout(Long id,Long pairSeatId) {}

    private record DemoFood(Integer itemId, BigDecimal price) {}
}
