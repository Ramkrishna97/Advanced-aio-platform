package com.example.dashboard.cart;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "shop_orders")
class ShopOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public Instant createdAt;
    public BigDecimal total;
    public String invoiceNumber;
    public String deliveryStatus;
    public Boolean deliveryPublished;
    public Instant statusChangedAt;
    @Lob public String itemsJson;

    protected ShopOrder() {}
    ShopOrder(Long userId, BigDecimal total, String itemsJson) {
        this.userId = userId;
        this.createdAt = Instant.now();
        this.total = total;
        this.itemsJson = itemsJson;
        this.invoiceNumber = "INV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.deliveryStatus = "Ordered";
        this.deliveryPublished = false;
        this.statusChangedAt = this.createdAt;
    }
}

interface OrderRepository extends JpaRepository<ShopOrder, Long> {
    List<ShopOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<ShopOrder> findByDeliveryStatusNot(String deliveryStatus);
    List<ShopOrder> findByDeliveryPublishedFalseOrDeliveryPublishedIsNull();
}

@Entity
@Table(name = "payment_reservations")
class PaymentReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public BigDecimal total;
    @Lob public String itemsJson;
    public java.time.Instant expiresAt;
    public String state;

    protected PaymentReservation() {}
    PaymentReservation(Long userId, BigDecimal total, String itemsJson) {
        this.userId = userId;
        this.total = total;
        this.itemsJson = itemsJson;
        this.state = "PENDING";
        this.expiresAt = java.time.Instant.now().plusSeconds(120);
    }
}

interface PaymentReservationRepository extends JpaRepository<PaymentReservation, Long> {
    List<PaymentReservation> findByStateAndExpiresAtBefore(String state, java.time.Instant expiresAt);
    java.util.Optional<PaymentReservation> findByUserIdAndState(Long userId, String state);
}
