package com.example.dashboard.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}

@Component
class AdminAccountSeeder implements CommandLineRunner {
    private final UserRepository users;
    AdminAccountSeeder(UserRepository users) { this.users = users; }
    @Override public void run(String... args) {
        BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
        var existing = users.findByEmail("admin@gmail.com").or(() -> users.findByEmail("admin@oneplace.local"));
        AppUser admin = existing.orElseGet(() -> new AppUser("admin", "admin@gmail.com", ""));
        boolean credentialsChanged = !"admin".equals(admin.name) || !"admin@gmail.com".equals(admin.email) || !"ADMIN".equals(admin.role)
                || admin.passwordHash == null || !passwords.matches("qwertyuiop", admin.passwordHash);
        if (credentialsChanged) {
            admin.name = "admin";
            admin.email = "admin@gmail.com";
            admin.role = "ADMIN";
            admin.passwordHash = passwords.encode("qwertyuiop");
            admin.sessionVersion = existing.isEmpty() ? 0 : (admin.sessionVersion == null ? 1 : admin.sessionVersion + 1);
            users.save(admin);
        }
    }
}
