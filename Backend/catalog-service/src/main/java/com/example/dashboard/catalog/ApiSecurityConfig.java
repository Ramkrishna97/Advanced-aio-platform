package com.example.dashboard.catalog;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration("catalogApiSecurityConfig") @EnableWebSecurity
class ApiSecurityConfig {
    @Bean SecurityFilterChain catalogApiSecurity(HttpSecurity http, @Value("${app.jwt.secret}") String secret) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> cors.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable()).formLogin(login -> login.disable()).logout(logout -> logout.disable())
                .headers(headers -> headers.contentTypeOptions(options -> {}).frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request,response,error) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)).accessDeniedHandler((request,response,error) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/info", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/internal/inventory/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/products", "/api/products/*", "/api/products/*/image").permitAll().dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().anyRequest().authenticated())
                .addFilterBefore(new JwtFilter(secret), UsernamePasswordAuthenticationFilter.class).build();
    }
    private static final class JwtFilter extends OncePerRequestFilter {
        private final Algorithm algorithm; JwtFilter(String secret) { algorithm=Algorithm.HMAC256(secret); }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            String header=request.getHeader("Authorization"); if(header==null||!header.startsWith("Bearer ")) {chain.doFilter(request,response);return;}
            try {var token=JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(header.substring(7));String role=token.getClaim("role").asString();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(token.getSubject(),null,List.of(new SimpleGrantedAuthority("ROLE_"+(role==null?"USER":role)))));chain.doFilter(request,response);}
            catch(com.auth0.jwt.exceptions.JWTVerificationException ex){SecurityContextHolder.clearContext();response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"Invalid session");}
        }
    }
}
