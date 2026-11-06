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
        if (users.findByEmail("admin@oneplace.local").isEmpty()) {
            AppUser admin = new AppUser("admin", "admin@oneplace.local", new BCryptPasswordEncoder().encode("qwertyuiop@123"));
            admin.role = "ADMIN";
            users.save(admin);
        }
    }
}
