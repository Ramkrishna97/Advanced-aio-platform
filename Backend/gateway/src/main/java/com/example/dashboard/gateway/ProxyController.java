package com.example.dashboard.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ProxyController {
    private static final Set<String> HOP_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "proxy-connection", "expect");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, String> routes;
    private final String authService;

    public ProxyController(@Value("${services.auth}") String auth,
                           @Value("${services.catalog}") String catalog,
                           @Value("${services.cart}") String cart,
                           @Value("${services.classroom}") String classroom,
                           @Value("${services.feed}") String feed,
                           @Value("${services.studio}") String studio,
                           @Value("${services.quiz}") String quiz,
                           @Value("${services.delivery}") String delivery) {
        authService = auth;
        routes = Map.of("auth", auth, "products", catalog, "cart", cart, "orders", cart, "seller", cart, "classroom", classroom,
                "feed", feed, "studio", studio, "quizzes", quiz, "delivery", delivery);
    }

    @RequestMapping("/api/**")
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request) {
        String path = request.getRequestURI();
        String[] parts = path.split("/");
        if (parts.length < 3 || !routes.containsKey(parts[2])) return ResponseEntity.notFound().build();

        // Tokens carry a user ID but are otherwise stateless. Check the account still exists
        // before forwarding authenticated requests, so deletion invalidates old sessions.
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ") && !"auth".equals(parts[2])) {
            try {
                HttpResponse<Void> identity = client.send(HttpRequest.newBuilder(URI.create(authService + "/api/auth/me"))
                        .timeout(Duration.ofSeconds(5)).header(HttpHeaders.AUTHORIZATION, authorization).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (identity.statusCode() != 200) return ResponseEntity.status(401).build();
            } catch (IOException ex) { return ResponseEntity.status(503).build(); }
              catch (InterruptedException ex) { Thread.currentThread().interrupt(); return ResponseEntity.status(503).build(); }
        }

        String query = request.getQueryString();
        URI target = URI.create(routes.get(parts[2]) + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder outbound = HttpRequest.newBuilder(target).timeout(
                path.startsWith("/api/classroom") || path.startsWith("/api/feed/media") ? Duration.ofMinutes(30) : Duration.ofSeconds(12));
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!HOP_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                Collections.list(request.getHeaders(name)).forEach(value -> outbound.header(name, value));
            }
        });

        try {
            HttpRequest.BodyPublisher body = switch (request.getMethod()) {
                case "GET", "HEAD" -> HttpRequest.BodyPublishers.noBody();
                default -> {
                    var input = request.getInputStream();
                    yield HttpRequest.BodyPublishers.ofInputStream(() -> input);
                }
            };
            HttpResponse<java.io.InputStream> response = client.send(
                    outbound.method(request.getMethod(), body).build(), HttpResponse.BodyHandlers.ofInputStream());
            var result = ResponseEntity.status(response.statusCode());
            response.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(value -> result.header(HttpHeaders.CONTENT_TYPE, value));
            response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).ifPresent(value -> result.header(HttpHeaders.CONTENT_DISPOSITION, value));
            response.headers().firstValue(HttpHeaders.ACCEPT_RANGES).ifPresent(value -> result.header(HttpHeaders.ACCEPT_RANGES, value));
            response.headers().firstValue(HttpHeaders.CONTENT_RANGE).ifPresent(value -> result.header(HttpHeaders.CONTENT_RANGE, value));
            StreamingResponseBody stream = output -> {
                try (var input = response.body()) { input.transferTo(output); }
            };
            return result.body(stream);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(error("Service request interrupted"));
        } catch (IOException ex) {
            return ResponseEntity.status(503).body(error("Service temporarily unavailable"));
        }
    }

    private StreamingResponseBody error(String message) {
        return output -> output.write(message.getBytes(StandardCharsets.UTF_8));
    }
}
