package com.example.dashboard.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/inventory")
public class InventoryController {
    private final ProductRepository products;
    private final String key;

    InventoryController(ProductRepository products, @Value("${app.service.key}") String key) {
        this.products = products;
        this.key = key;
    }

    record Line(@NotNull Long productId, @Min(1) int quantity) {}
    record Snapshot(Long productId, Long sellerId, String name, BigDecimal price, int quantity) {}

    private void requireKey(String supplied) {
        if (!key.equals(supplied)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @PostMapping("/reserve")
    @Transactional
    public List<Snapshot> reserve(@RequestHeader("X-Service-Key") String supplied,
                                  @Valid @RequestBody List<@Valid Line> lines) {
        requireKey(supplied);
        List<Snapshot> snapshots = new ArrayList<>();
        for (Line line : lines) {
            Product product = products.locked(line.productId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            if (product.stock < line.quantity())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough stock for " + product.name);
            product.stock -= line.quantity();
            snapshots.add(new Snapshot(product.id, product.sellerId, product.name, product.price, line.quantity()));
        }
        return snapshots;
    }

    @PostMapping("/release")
    @Transactional
    public void release(@RequestHeader("X-Service-Key") String supplied,
                        @Valid @RequestBody List<@Valid Line> lines) {
        requireKey(supplied);
        for (Line line : lines) {
            Product product = products.locked(line.productId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            product.stock += line.quantity();
        }
    }
}
