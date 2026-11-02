package com.example.dashboard.classroom;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
class Material {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public String title;
    @Column(length=1000000) public String content;
    public String fileName;
    public String originalName;
    public String contentType;
    public String description;
    public Long topicId;
    public Long teacherId;
    public Instant createdAt = Instant.now();
    protected Material() {}
}

interface MaterialRepository extends org.springframework.data.jpa.repository.JpaRepository<Material, Long> {
    java.util.List<Material> findAllByOrderByCreatedAtDesc();
    java.util.List<Material> findByTopicIdOrderByCreatedAtDesc(Long topicId);
}
