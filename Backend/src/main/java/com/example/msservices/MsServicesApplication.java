package com.example.msservices;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.example.dashboard", "com.example.msservices"})
public class MsServicesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsServicesApplication.class, args);
    }

}
