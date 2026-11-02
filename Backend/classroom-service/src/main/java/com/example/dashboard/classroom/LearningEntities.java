package com.example.dashboard.classroom;

import jakarta.persistence.*;
import java.time.Instant;

@Entity class LearningModule {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public String title; public String subject; public Long teacherId; public Instant createdAt=Instant.now();
    protected LearningModule() {}
}
@Entity class LearningTopic {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long moduleId; public Long teacherId; public String authorName; public String title; public String description; public String videoFile; public String videoType; public String videoName; public Instant createdAt=Instant.now();
    protected LearningTopic() {}
}
@Entity class TopicComment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long topicId; public Long materialId; public Long parentId; public Long studentId; public String authorName;
    public String replyToName;
    @Column(length=10000) public String text;
    public Instant createdAt=Instant.now();
    protected TopicComment() {}
}
@Entity class TopicStudentNote {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long topicId; public Long studentId; public String title;
    @Column(length=10000) public String text;
    public Instant createdAt=Instant.now();
    protected TopicStudentNote() {}
}
@Entity
@Table(uniqueConstraints=@UniqueConstraint(columnNames={"topicId","studentId"}))
class TopicLike {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long topicId; public Long studentId; public Instant createdAt=Instant.now();
    protected TopicLike() {}
}

interface ModuleRepository extends org.springframework.data.jpa.repository.JpaRepository<LearningModule,Long> {}
interface TopicRepository extends org.springframework.data.jpa.repository.JpaRepository<LearningTopic,Long> { java.util.List<LearningTopic> findByModuleId(Long moduleId); }
interface CommentRepository extends org.springframework.data.jpa.repository.JpaRepository<TopicComment,Long> {
    java.util.List<TopicComment> findByTopicId(Long topicId);
    java.util.List<TopicComment> findByTopicIdAndMaterialIdIsNullOrderByCreatedAtAsc(Long topicId);
    java.util.List<TopicComment> findByTopicIdAndMaterialIdOrderByCreatedAtAsc(Long topicId,Long materialId);
}
interface StudentNoteRepository extends org.springframework.data.jpa.repository.JpaRepository<TopicStudentNote,Long> { java.util.List<TopicStudentNote> findByTopicIdAndStudentIdOrderByCreatedAtDesc(Long topicId,Long studentId); java.util.List<TopicStudentNote> findByTopicId(Long topicId); }
interface TopicLikeRepository extends org.springframework.data.jpa.repository.JpaRepository<TopicLike,Long> {
    java.util.List<TopicLike> findByTopicId(Long topicId);
    long countByTopicId(Long topicId);
    boolean existsByTopicIdAndStudentId(Long topicId,Long studentId);
    void deleteByTopicIdAndStudentId(Long topicId,Long studentId);
}
