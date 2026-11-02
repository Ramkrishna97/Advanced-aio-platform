package com.example.dashboard.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DataSnapshot {
    private final ProductRepository products; private final Path target;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    DataSnapshot(ProductRepository products,@Value("${app.snapshot-dir}") String dir) { this.products=products; target=Path.of(dir).toAbsolutePath().normalize().resolve("catalog.json"); }
    @EventListener(ApplicationReadyEvent.class) void initialSnapshot() { write(); }
    @Scheduled(initialDelay=30_000,fixedDelay=30_000) void scheduledSnapshot() { write(); }
    private void write() { try { Files.createDirectories(target.getParent());Path temp=target.resolveSibling(target.getFileName()+".tmp");mapper.writeValue(temp.toFile(),Map.of("generatedAt",Instant.now(),"products",products.findAll()));move(temp);}catch(Exception ignored){} }
    private void move(Path temp) throws java.io.IOException { try {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException ex){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);} }
}
