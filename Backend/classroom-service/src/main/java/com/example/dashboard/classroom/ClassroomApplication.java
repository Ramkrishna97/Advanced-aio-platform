package com.example.dashboard.classroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClassroomApplication {
    public static void main(String[] args) { SpringApplication.run(ClassroomApplication.class, args); }
    @Bean CommandLineRunner resources(@Value("${app.resource-dir}") String dir) {
        return args -> Files.createDirectories(Path.of(dir));
    }
}
