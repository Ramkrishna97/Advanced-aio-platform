package com.example.msservices;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.dashboard", "com.example.msservices"},
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = {"com\\.example\\.dashboard\\..*Application", "com\\.example\\.dashboard\\..*ApiSecurityConfig"}))
@EntityScan("com.example.dashboard")
@EnableJpaRepositories("com.example.dashboard")
@EnableScheduling
@EnableAsync
public class MsServicesApplication {

    @Bean
    SecurityFilterChain unifiedApiSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(MsServicesApplication.class, args);
    }

}
