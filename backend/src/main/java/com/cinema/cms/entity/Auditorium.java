package com.cinema.cms.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "auditoriums")
public class Auditorium {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auditorium_id") private Integer id;
    @Column(nullable = false, length = 50) private String name;
    @Column(name = "total_seats", nullable = false) private int totalSeats;
    @Column(name = "auditorium_type", nullable = false, columnDefinition = "auditorium_type_enum")
    @ColumnTransformer(write = "cast(? as auditorium_type_enum)") private String auditoriumType;
    @Column(name = "is_active", nullable = false) private boolean active;

    protected Auditorium() {}

    public Auditorium(String name, int totalSeats, AuditoriumType type) {
        update(name, totalSeats, type);
        this.active = true;
    }

    public void update(String name, int totalSeats, AuditoriumType type) {
        this.name = name; this.totalSeats = totalSeats; this.auditoriumType = type.value();
    }
    public void changeActive(boolean active) { this.active = active; }

    public Integer getId() { return id; }
    public String getName() { return name; }
    public int getTotalSeats() { return totalSeats; }
    public AuditoriumType getType() { return AuditoriumType.fromValue(auditoriumType); }
    public boolean isActive() { return active; }
}