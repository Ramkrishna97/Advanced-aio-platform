package com.example.dashboard.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads quiz definitions directly from the external resources directory. */
@Component
class QuizStore {
    record Question(String id, String question, List<String> options, int correctAnswer) { }
    record Quiz(String subject, String topic, Long topicId, List<Question> questions) { }

    private final ObjectMapper mapper;
    private final Path quizDirectory;

    QuizStore(ObjectMapper mapper, @Value("${app.quiz-dir}") String directory) {
        this.mapper = mapper;
        this.quizDirectory = Path.of(directory).toAbsolutePath().normalize();
    }

    List<Question> questionsFor(LearningModule module, LearningTopic topic) {
        List<Question> questions = new ArrayList<>();
        try {
            Files.createDirectories(quizDirectory);
            try (var files = Files.list(quizDirectory)) {
                files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                     .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                     .forEach(path -> read(path).filter(quiz -> matches(quiz, module, topic)).ifPresent(quiz -> questions.addAll(quiz.questions())));
            }
        } catch (IOException ignored) { }
        return questions;
    }

    void addQuestion(LearningModule module, LearningTopic topic, String question, List<String> options, int correctAnswer) {
        try {
            Files.createDirectories(quizDirectory);
            Path target = quizDirectory.resolve("teacher-topic-" + topic.id + ".json");
            Quiz current = read(target).orElse(new Quiz(module.subject, topic.title, topic.id, new ArrayList<>()));
            List<Question> updated = new ArrayList<>(current.questions());
            updated.add(new Question(UUID.randomUUID().toString(), question.trim(), List.copyOf(options), correctAnswer));
            write(target, new Quiz(module.subject, topic.title, topic.id, updated));
        } catch (IOException exception) {
            throw new IllegalStateException("Quiz could not be saved", exception);
        }
    }

    void deleteQuestions(Long topicId) {
        try { Files.deleteIfExists(quizDirectory.resolve("teacher-topic-" + topicId + ".json")); }
        catch (IOException exception) { throw new IllegalStateException("Quiz could not be deleted", exception); }
    }

    private java.util.Optional<Quiz> read(Path path) {
        if (!Files.isRegularFile(path)) return java.util.Optional.empty();
        try {
            JsonNode root = mapper.readTree(path.toFile());
            String subject = text(root, "subject");
            String topic = text(root, "topic");
            Long topicId = root.path("topicId").canConvertToLong() ? root.path("topicId").asLong() : null;
            JsonNode items = root.has("questionnaires") ? root.path("questionnaires") : root.path("questions");
            List<Question> questions = new ArrayList<>();
            if (items.isArray()) for (JsonNode item : items) {
                List<String> options = new ArrayList<>();
                item.path("options").forEach(option -> options.add(option.asText()));
                int correct = correctAnswer(item, options);
                String question = text(item, "question");
                if (question != null && options.size() == 4 && correct >= 0 && correct < 4)
                    questions.add(new Question(item.path("id").asText(UUID.randomUUID().toString()), question, List.copyOf(options), correct));
            }
            return java.util.Optional.of(new Quiz(subject, topic, topicId, questions));
        } catch (Exception ignored) { return java.util.Optional.empty(); }
    }

    private static String text(JsonNode node, String name) { return node.hasNonNull(name) ? node.path(name).asText() : null; }
    private static int correctAnswer(JsonNode item, List<String> options) {
        JsonNode value = item.path("correctAnswer");
        if (value.canConvertToInt()) return value.asInt();
        String answer = value.asText();
        return options.indexOf(answer);
    }
    private static boolean matches(Quiz quiz, LearningModule module, LearningTopic topic) {
        if (quiz.topicId() != null) return quiz.topicId().equals(topic.id);
        return same(quiz.subject(), module.subject) && same(quiz.topic(), topic.title);
    }
    private static boolean same(String first, String second) { return first != null && second != null && first.trim().equalsIgnoreCase(second.trim()); }
    private void write(Path target, Quiz quiz) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        java.util.Map<String,Object> document = new java.util.LinkedHashMap<>();
        document.put("schemaVersion", 1); document.put("subject", quiz.subject()); document.put("topicId", quiz.topicId());
        document.put("topic", quiz.topic()); document.put("questionnaires", quiz.questions());
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), document);
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
