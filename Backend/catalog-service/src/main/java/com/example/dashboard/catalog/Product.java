package com.example.dashboard.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "products")
class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long sellerId;
    public Boolean createdByAdmin;
    public String name;
    public String description;
    public String category;
    public BigDecimal price;
    public String currency = "INR";
    public int stock;
    public String imageFile;
    public String imageFile2;
    public String imageFile3;

    public List<String> getImageFiles() {
        return java.util.stream.Stream.of(imageFile, imageFile2, imageFile3)
                .filter(file -> file != null && !file.isBlank()).toList();
    }

    protected Product() {}

    Product(Long sellerId, String name, String description, String category, BigDecimal price, String currency, int stock) {
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.currency = currency;
        this.stock = stock;
    }
}

interface ProductRepository extends JpaRepository<Product, Long> {
    java.util.List<Product> findBySellerIdOrderByIdDesc(Long sellerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> locked(@Param("id") Long id);
}
