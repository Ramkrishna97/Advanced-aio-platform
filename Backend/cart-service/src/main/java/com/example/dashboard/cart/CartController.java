package com.example.dashboard.cart;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CartController {
    private final CartRepository cart;
    private final OrderRepository orders;
    private final PaymentReservationRepository payments;
    private final Algorithm algorithm;
    private final String catalogUrl;
    private final String deliveryUrl;
    private final String serviceKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http = new RestTemplate();

    CartController(CartRepository cart, OrderRepository orders, PaymentReservationRepository payments,
                   @Value("${app.jwt.secret}") String secret,
                   @Value("${app.service.key}") String serviceKey,
                   @Value("${services.catalog}") String catalogUrl,
                   @Value("${services.delivery}") String deliveryUrl) {
        this.cart = cart;
        this.orders = orders;
        this.payments = payments;
        this.algorithm = Algorithm.HMAC256(secret);
        this.serviceKey = serviceKey;
        this.catalogUrl = catalogUrl;
        this.deliveryUrl = deliveryUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(5000);
        http.setRequestFactory(requestFactory);
    }

    record Line(@NotNull Long productId, @Min(1) int quantity) {}
    record Snapshot(Long productId, Long sellerId, String name, BigDecimal price, int quantity) {}
    record OrderView(Long id, BigDecimal total, java.time.Instant createdAt, String invoiceNumber, String deliveryStatus, List<Snapshot> items) {}
    record AdminOrderView(Long id, Long userId, BigDecimal total, java.time.Instant createdAt, String invoiceNumber, String deliveryStatus, List<Snapshot> items) {}
    record AdminOrderUpdate(Long userId, BigDecimal total, String invoiceNumber, String deliveryStatus, List<Snapshot> items) {}
    record AdminCartUpdate(Long userId, Long productId, int quantity) {}
    record PaymentView(Long id, BigDecimal total, java.time.Instant expiresAt) {}

    private Long user(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        try {
            var token = JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7));
            return Long.valueOf(token.getSubject());
        } catch (JWTVerificationException | NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
    }

    private void admin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { if (!"ADMIN".equals(JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7)).getClaim("role").asString())) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (JWTVerificationException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }

    private Long seller(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        try {
            var token = JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7));
            if (!Boolean.TRUE.equals(token.getClaim("seller").asBoolean()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seller account required");
            return Long.valueOf(token.getSubject());
        } catch (JWTVerificationException | NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
    }

    private HttpEntity<?> internalBody(Object value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Key", serviceKey);
        return new HttpEntity<>(value, headers);
    }

    private List<CartItem> add(Long userId, Line line) {
        CartItem item = cart.findByUserIdAndProductId(userId, line.productId())
                .orElseGet(() -> new CartItem(userId, line.productId(), 0));
        item.quantity += line.quantity();
        cart.save(item);
        return cart.findByUserId(userId);
    }

    @GetMapping("/api/cart")
    public List<CartItem> cart(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return cart.findByUserId(user(authorization));
    }

    @GetMapping("/api/cart/admin") public List<CartItem> adminCart(@RequestHeader(value="Authorization",required=false)String authorization){admin(authorization);return cart.findAll();}
    @PutMapping("/api/cart/admin/{id}") public CartItem editCart(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable Long id,@RequestBody AdminCartUpdate in){admin(authorization);if(in.userId()==null||in.productId()==null||in.quantity()<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);CartItem item=cart.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));item.userId=in.userId();item.productId=in.productId();item.quantity=in.quantity();return cart.save(item);}
    @DeleteMapping("/api/cart/admin/{id}") public void deleteCart(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable Long id){admin(authorization);if(!cart.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);cart.deleteById(id);}

    @PostMapping("/api/cart/items")
    @Transactional
    public List<CartItem> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @Valid @RequestBody Line line) {
        return add(user(authorization), line);
    }

    @PostMapping("/api/cart/merge")
    @Transactional
    public List<CartItem> merge(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @Valid @RequestBody List<@Valid Line> lines) {
        Long id = user(authorization);
        for (Line line : lines) add(id, line);
        return cart.findByUserId(id);
    }

    @DeleteMapping("/api/cart/items/{productId}")
    @Transactional
    public List<CartItem> remove(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable Long productId) {
        Long id = user(authorization);
        cart.findByUserIdAndProductId(id, productId).ifPresent(cart::delete);
        return cart.findByUserId(id);
    }

    @GetMapping("/api/orders")
    public List<OrderView> history(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return orders.findByUserIdOrderByCreatedAtDesc(user(authorization)).stream().map(this::view).toList();
    }

    @GetMapping("/api/orders/admin") public List<AdminOrderView> adminOrders(@RequestHeader(value="Authorization",required=false)String authorization){admin(authorization);return orders.findAll().stream().map(order->new AdminOrderView(order.id,order.userId,order.total,order.createdAt,order.invoiceNumber,order.deliveryStatus,items(order))).toList();}
    @PutMapping("/api/orders/admin/{id}") public AdminOrderView editOrder(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable Long id,@RequestBody AdminOrderUpdate in){
        admin(authorization);if(in.userId()==null||in.total()==null||in.total().signum()<0||in.invoiceNumber()==null||in.invoiceNumber().isBlank()||in.deliveryStatus()==null||in.deliveryStatus().isBlank()||in.items()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        ShopOrder order=orders.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        try{order.itemsJson=mapper.writeValueAsString(in.items());}catch(JsonProcessingException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid order items",e);}
        order.userId=in.userId();order.total=in.total();order.invoiceNumber=in.invoiceNumber().trim();order.deliveryStatus=in.deliveryStatus().trim();order.statusChangedAt=java.time.Instant.now();
        ShopOrder saved=orders.save(order);return new AdminOrderView(saved.id,saved.userId,saved.total,saved.createdAt,saved.invoiceNumber,saved.deliveryStatus,items(saved));
    }

    @GetMapping("/api/seller/orders")
    public List<OrderView> sellerOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long sellerId = seller(authorization);
        return orders.findAll().stream()
                .filter(order -> items(order).stream().anyMatch(item -> sellerId.equals(item.sellerId())))
                .sorted((left, right) -> right.createdAt.compareTo(left.createdAt))
                .map(order -> viewForSeller(order, sellerId)).toList();
    }

    private OrderView view(ShopOrder order) {
        return new OrderView(order.id, order.total, order.createdAt, order.invoiceNumber, order.deliveryStatus, items(order));
    }

    private OrderView viewForSeller(ShopOrder order, Long sellerId) {
        List<Snapshot> sellerItems = items(order).stream().filter(item -> sellerId.equals(item.sellerId())).toList();
        BigDecimal sellerTotal = sellerItems.stream().map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderView(order.id, sellerTotal, order.createdAt, order.invoiceNumber, order.deliveryStatus, sellerItems);
    }

    private List<Snapshot> items(ShopOrder order) {
        try {
            return mapper.readerForListOf(Snapshot.class).readValue(order.itemsJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored order is invalid", ex);
        }
    }

    @PostMapping("/api/orders/prepare-payment")
    @Transactional
    public PaymentView preparePayment(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long id = user(authorization);
        PaymentReservation existing = payments.findByUserIdAndState(id, "PENDING").orElse(null);
        if (existing != null && existing.expiresAt.isAfter(java.time.Instant.now()))
            return new PaymentView(existing.id, existing.total, existing.expiresAt);
        if (existing != null) { release(existing); existing.state = "EXPIRED"; }
        List<Line> lines = cart.findByUserId(id).stream()
                .map(item -> new Line(item.productId, item.quantity)).toList();
        if (lines.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty");
        Snapshot[] snapshots;
        try {
            snapshots = http.postForObject(catalogUrl + "/internal/inventory/reserve",
                    internalBody(lines), Snapshot[].class);
        } catch (HttpStatusCodeException ex) {
            throw new ResponseStatusException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog unavailable", ex);
        }
        if (snapshots == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog response");
        try {
            List<Snapshot> items = List.of(snapshots);
            BigDecimal total = items.stream().map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            PaymentReservation payment = payments.saveAndFlush(new PaymentReservation(id, total, mapper.writeValueAsString(items)));
            return new PaymentView(payment.id, payment.total, payment.expiresAt);
        } catch (Exception ex) {
            try { http.postForEntity(catalogUrl + "/internal/inventory/release", internalBody(lines), Void.class); }
            catch (Exception ignored) { /* Keep the original database failure visible. */ }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Checkout failed", ex);
        }
    }

    @PostMapping("/api/orders/payments/{paymentId}/complete")
    @Transactional
    public OrderView completePayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable Long paymentId) {
        Long userId = user(authorization);
        PaymentReservation payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment session not found"));
        if (!userId.equals(payment.userId) || !"PENDING".equals(payment.state))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment session not found");
        if (payment.expiresAt.isBefore(java.time.Instant.now())) {
            release(payment);
            payment.state = "EXPIRED";
            throw new ResponseStatusException(HttpStatus.GONE, "Payment session expired. Please try checkout again.");
        }
        try {
            ShopOrder order = orders.saveAndFlush(new ShopOrder(userId, payment.total, payment.itemsJson));
            publishDelivery(order, "Payment successful");
            payment.state = "COMPLETED";
            cart.deleteByUserId(userId);
            cart.flush();
            return view(order);
        } catch (Exception ex) {
            release(payment);
            payment.state = "FAILED";
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Payment could not be completed", ex);
        }
    }

    private void publishDelivery(ShopOrder order, String paymentStatus) {
        try {
            Map<String, Object> event = Map.of("orderId", order.id, "userId", order.userId, "itemsJson", order.itemsJson, "paymentStatus", paymentStatus);
            http.postForEntity(deliveryUrl + "/api/delivery/orders", internalBody(event), Void.class);
            order.deliveryPublished = true;
            orders.save(order);
        } catch (Exception ignored) {
            // Keep the paid order durable and retry its idempotent delivery notification.
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 15_000)
    @Transactional
    public void retryDeliveryPublications() {
        for (ShopOrder order : orders.findByDeliveryPublishedFalseOrDeliveryPublishedIsNull()) {
            publishDelivery(order, "Payment successful");
        }
    }

    @PostMapping("/api/orders/payments/{paymentId}/cancel")
    @Transactional
    public void cancelPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable Long paymentId) {
        Long userId = user(authorization);
        PaymentReservation payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment session not found"));
        if (!userId.equals(payment.userId) || !"PENDING".equals(payment.state))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment session not found");
        release(payment);
        payment.state = "CANCELLED";
    }

    private void release(PaymentReservation payment) {
        try {
            List<Snapshot> held = mapper.readerForListOf(Snapshot.class).readValue(payment.itemsJson);
            http.postForEntity(catalogUrl + "/internal/inventory/release", internalBody(held.stream()
                    .map(item -> new Line(item.productId(), item.quantity())).toList()), Void.class);
        } catch (Exception ignored) { }
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    @Transactional
    public void advanceDeliveryStatuses() {
        List<String> statuses = List.of("Ordered", "Processed", "Ready for shipment", "Out of inventory", "Reached nearby location", "Out for delivery", "Delivered");
        for (ShopOrder order : orders.findByDeliveryStatusNot("Delivered")) {
            int current = statuses.indexOf(order.deliveryStatus);
            if (current < 0) current = 0;
            if (current < statuses.size() - 1) {
                order.deliveryStatus = statuses.get(current + 1);
                order.statusChangedAt = java.time.Instant.now();
            }
        }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    @Transactional
    public void expirePaymentReservations() {
        for (PaymentReservation payment : payments.findByStateAndExpiresAtBefore("PENDING", java.time.Instant.now())) {
            release(payment);
            payment.state = "EXPIRED";
        }
    }
}
