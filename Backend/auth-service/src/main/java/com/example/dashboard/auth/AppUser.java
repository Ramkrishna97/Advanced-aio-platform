package com.example.dashboard.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

@Entity
@Table(name = "app_users")
@org.hibernate.annotations.DynamicUpdate
class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    @Column(unique = true, nullable = false)
    String email;
    String passwordHash;
    boolean seller;
    String role = "STUDENT";
    Integer sessionVersion;
    String photoFile;
    @jakarta.persistence.Lob
    byte[] photoData;
    Instant photoUpdatedAt;
    String resetTokenHash;
    Instant resetExpiresAt;

    protected AppUser() {}

    AppUser(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
    }
}

interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<AppUser> findByResetTokenHash(String resetTokenHash);
}

@Entity
@Table(name = "user_activities")
class UserActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    Long userId;
    String action;
    java.time.Instant createdAt = java.time.Instant.now();
    protected UserActivity() {}
    UserActivity(Long userId, String action) { this.userId=userId; this.action=action; }
}

interface UserActivityRepository extends JpaRepository<UserActivity, Long> {
    java.util.List<UserActivity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    java.util.List<UserActivity> findByUserId(Long userId);
}
