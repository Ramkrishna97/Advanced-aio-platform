package com.example.dashboard.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableAsync
public class AuthApplication {
    @Bean CommandLineRunner adminAccount(UserRepository users) {
        return args -> {
            if (users.findByEmail("admin@oneplace.local").isEmpty()) {
                AppUser admin = new AppUser("admin", "admin@oneplace.local", new BCryptPasswordEncoder().encode("qwertyuiop@123"));
                admin.role = "ADMIN";
                users.save(admin);
            }
        };
    }
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
