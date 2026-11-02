package com.example.dashboard.classroom;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.example.dashboard.classroom.video.VideoStore;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestTemplate;

@RestController @RequestMapping("/api/classroom")
class LearningController {
    private final ModuleRepository modules; private final TopicRepository topics; private final CommentRepository comments;
    private final StudentNoteRepository notes; private final MaterialRepository materials; private final TopicLikeRepository likes; private final QuizStore quizzes; private final VideoStore videoStore; private final Algorithm jwt; private final Path resourceDir; private final String authUrl; private final RestTemplate http=new RestTemplate();
    LearningController(ModuleRepository modules,TopicRepository topics,CommentRepository comments,StudentNoteRepository notes,MaterialRepository materials,TopicLikeRepository likes,QuizStore quizzes,VideoStore videoStore,
                       @Value("${app.jwt.secret}") String secret,@Value("${app.resource-dir}") String dir,@Value("${services.auth}") String authUrl) {
        this.modules=modules;this.topics=topics;this.comments=comments;this.notes=notes;this.materials=materials;this.likes=likes;this.quizzes=quizzes;this.videoStore=videoStore;this.jwt=Algorithm.HMAC256(secret);this.resourceDir=Path.of(dir).toAbsolutePath().normalize();this.authUrl=authUrl;
    }
    record User(Long id,String role,String name) {}
    record ModuleInput(String title,String subject) {}
    record TopicInput(String title,String description) {}
    record FeedItem(Long moduleId,String moduleTitle,String subject,Long id,String title,String description,String videoFile,String videoName,String videoType,java.time.Instant createdAt,String authorName,long likeCount,boolean likedByCurrentUser) {}
    record LikeView(long likeCount,boolean likedByCurrentUser) {}
    record CommentInput(String text,Long parentId,Long materialId) {}
    record ResourceView(Long id,String title,String description,String content,String originalName,String contentType,boolean hasFile,boolean available) {}
    record NoteInput(String title,String text) {}
    record QuizQuestionInput(String question,List<String> options,Integer correctAnswer) {}
    record QuizAnswerInput(Map<String,Integer> answers) {}
    record QuizQuestionView(String id,String question,List<String> options,Integer correctAnswer) {}
    record QuizResultItem(String id,Integer selectedAnswer,int correctAnswer,boolean correct) {}
    record QuizResult(int total,int correct,List<QuizResultItem> results) {}
    private User user(String auth) {
        if(auth==null||!auth.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in required");
        try { var token=JWT.require(jwt).withIssuer("dashboard-auth").build().verify(auth.substring(7)); String role=token.getClaim("role").asString();
            if(!Set.of("TEACHER","STUDENT","ADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Classroom access required");
            return new User(Long.valueOf(token.getSubject()),role,token.getClaim("name").asString());
        } catch(JWTVerificationException|NumberFormatException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session"); }
    }
    private User teacher(String auth){User u=user(auth);if(!Set.of("TEACHER","ADMIN").contains(u.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only teachers and admins can manage modules, topics, and videos");return u;}
    private LearningTopic topic(Long id){return topics.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Topic not found"));}
    private LearningModule module(Long id){return modules.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Module not found"));}
    @GetMapping("/modules") java.util.List<LearningModule> list(@RequestHeader(value="Authorization",required=false)String auth){user(auth);return modules.findAll();}
    @GetMapping("/feed") java.util.List<FeedItem> feed(@RequestHeader(value="Authorization",required=false)String auth){
        User viewer=user(auth);
        Map<Long,LearningModule> byId=new HashMap<>(); modules.findAll().forEach(item->byId.put(item.id,item));
        Map<Long,String> authors=new HashMap<>();
        return topics.findAll().stream().filter(item->byId.containsKey(item.moduleId))
                .sorted(Comparator.comparing((LearningTopic item)->Objects.requireNonNullElse(item.createdAt,java.time.Instant.EPOCH)).reversed())
                .map(item->{
                    LearningModule module=byId.get(item.moduleId);
                    Long authorId=item.teacherId!=null?item.teacherId:module.teacherId;
                    String author=item.authorName!=null&&!item.authorName.isBlank()?item.authorName:authors.computeIfAbsent(authorId,key->lookupAuthor(key,auth));
                    return new FeedItem(module.id,module.title,module.subject,item.id,item.title,item.description,item.videoFile,item.videoName,item.videoType,item.createdAt,author,likes.countByTopicId(item.id),likes.existsByTopicIdAndStudentId(item.id,viewer.id));
                }).toList();
    }
    private String lookupAuthor(Long id,String authorization){if(id==null)return "Unknown teacher";try{HttpHeaders headers=new HttpHeaders();headers.set("Authorization",authorization);var response=http.exchange(authUrl+"/api/auth/users/"+id+"/display-name",HttpMethod.GET,new HttpEntity<>(headers),Map.class).getBody();return response==null?"Teacher #"+id:Objects.toString(response.get("name"),"Teacher #"+id);}catch(Exception ignored){return "Teacher #"+id;}}
    @PostMapping("/topics/{id}/like") @org.springframework.transaction.annotation.Transactional
    LikeView toggleLike(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id) {
        User viewer=user(auth); if(!"STUDENT".equals(viewer.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only students can like videos"); topic(id);
        boolean liked=likes.existsByTopicIdAndStudentId(id,viewer.id);
        if(liked) likes.deleteByTopicIdAndStudentId(id,viewer.id);
        else { TopicLike like=new TopicLike(); like.topicId=id; like.studentId=viewer.id; likes.save(like); }
        return new LikeView(likes.countByTopicId(id),!liked);
    }
    @PostMapping("/modules") LearningModule addModule(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ModuleInput in){
        User u=teacher(auth);
        if(in==null||in.title()==null||in.title().isBlank()||in.subject()==null||in.subject().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a module title and subject");
        LearningModule m=new LearningModule();m.title=in.title().trim();m.subject=in.subject().trim();m.teacherId=u.id();return modules.saveAndFlush(m);
    }
    @PutMapping("/modules/{id}") LearningModule editModule(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody ModuleInput in){teacher(auth);if(in.title()==null||in.title().isBlank()||in.subject()==null||in.subject().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);LearningModule m=module(id);m.title=in.title().trim();m.subject=in.subject().trim();return modules.save(m);}
    @DeleteMapping("/modules/{id}") @org.springframework.transaction.annotation.Transactional void deleteModule(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){teacher(auth);module(id);topics.findByModuleId(id).forEach(this::removeTopicData);modules.deleteById(id);}
    @GetMapping("/modules/{id}/topics") java.util.List<LearningTopic> topics(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){user(auth);return topics.findByModuleId(id);}
    @PostMapping("/modules/{id}/topics") LearningTopic addTopic(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody TopicInput in){User u=teacher(auth);if(!modules.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);LearningTopic t=new LearningTopic();t.moduleId=id;t.teacherId=u.id();t.authorName="ADMIN".equals(u.role())?"Created by admin":u.name();t.title=in.title();t.description=in.description();return topics.save(t);}
    @PutMapping("/topics/{id}") LearningTopic editTopic(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody TopicInput in){teacher(auth);if(in.title()==null||in.title().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);LearningTopic t=topic(id);t.title=in.title().trim();t.description=in.description();return topics.save(t);}
    @DeleteMapping("/topics/{id}") @org.springframework.transaction.annotation.Transactional void deleteTopic(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){teacher(auth);removeTopicData(topic(id));}
    @PostMapping("/topics/{id}/video") LearningTopic video(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        teacher(auth);
        LearningTopic topic = topic(id);
        String oldName = topic.videoFile;
        VideoStore.SavedVideo saved = videoStore.save(file);
        topic.videoFile = saved.filename();
        topic.videoName = saved.originalName();
        topic.videoType = saved.contentType();
        LearningTopic updated = topics.save(topic);
        if (oldName != null) {
            videoStore.delete(oldName);
        }
        return updated;
    }
    @DeleteMapping("/topics/{id}/video") void removeVideo(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){teacher(auth);LearningTopic t=topic(id);videoStore.delete(t.videoFile);t.videoFile=null;t.videoName=null;t.videoType=null;topics.save(t);}
    @GetMapping("/topics/{id}/quiz") List<QuizQuestionView> quiz(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id) {
        User user=user(auth); LearningTopic topic=topic(id); List<QuizStore.Question> questions=quizzes.questionsFor(module(topic.moduleId),topic);
        return questions.stream().map(question -> new QuizQuestionView(question.id(),question.question(),question.options(),Set.of("TEACHER","ADMIN").contains(user.role()) ? question.correctAnswer() : null)).toList();
    }
    @PostMapping("/topics/{id}/quiz/questions") QuizQuestionView addQuizQuestion(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody QuizQuestionInput input) {
        teacher(auth); LearningTopic topic=topic(id); List<String> options=input.options()==null?List.of():input.options().stream().map(value -> value==null?"":value.trim()).toList();
        if(input.question()==null||input.question().isBlank()||options.size()!=4||options.stream().anyMatch(String::isBlank)||input.correctAnswer()==null||input.correctAnswer()<0||input.correctAnswer()>3)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a question, four answers, and the correct answer");
        quizzes.addQuestion(module(topic.moduleId),topic,input.question(),options,input.correctAnswer());
        List<QuizStore.Question> saved=quizzes.questionsFor(module(topic.moduleId),topic);
        QuizStore.Question question=saved.get(saved.size()-1);
        return new QuizQuestionView(question.id(),question.question(),question.options(),question.correctAnswer());
    }
    @PostMapping("/topics/{id}/quiz/submit") QuizResult submitQuiz(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody QuizAnswerInput input) {
        user(auth); LearningTopic topic=topic(id); Map<String,Integer> answers=input.answers()==null?Map.of():input.answers();
        List<QuizResultItem> results=quizzes.questionsFor(module(topic.moduleId),topic).stream().map(question -> {
            Integer selected=answers.get(question.id()); boolean correct=selected!=null&&selected==question.correctAnswer();
            return new QuizResultItem(question.id(),selected,question.correctAnswer(),correct);
        }).toList();
        return new QuizResult(results.size(),(int)results.stream().filter(QuizResultItem::correct).count(),results);
    }
    private void deleteVideo(LearningTopic t){if(t.videoFile!=null)videoStore.delete(t.videoFile);}
    private void removeTopicData(LearningTopic t){
        comments.deleteAll(comments.findByTopicId(t.id));
        notes.deleteAll(notes.findByTopicId(t.id));
        likes.deleteAll(likes.findByTopicId(t.id));
        materials.findByTopicIdOrderByCreatedAtDesc(t.id).forEach(m -> {
            materials.delete(m);
            if(m.fileName!=null)try{Files.deleteIfExists(materialPath(m));}catch(Exception ignored){}
        });
        quizzes.deleteQuestions(t.id);
        deleteVideo(t);
        topics.delete(t);
    }
    private Path materialPath(Material m) { return resourceDir.resolve(m.fileName).normalize(); }
    private ResourceView view(Material m) {
        boolean hasFile=m.fileName!=null;
        boolean available=hasFile ? materialPath(m).startsWith(resourceDir)&&Files.isRegularFile(materialPath(m)) : m.content!=null&&!m.content.isBlank();
        return new ResourceView(m.id,m.title,m.description,m.content,m.originalName,m.contentType,hasFile,available);
    }
    private Material material(Long topicId,Long materialId) {
        Material m=materials.findById(materialId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found"));
        if(!topicId.equals(m.topicId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found");
        return m;
    }
    @GetMapping("/topics/{id}/resources") List<ResourceView> resources(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id) {
        user(auth);topic(id);return materials.findByTopicIdOrderByCreatedAtDesc(id).stream().map(this::view).toList();
    }
    @PostMapping(value="/topics/{id}/resources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) ResourceView addResource(
            @RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,
            @RequestParam String title,@RequestParam(required=false)String description,@RequestParam(required=false)String content,
            @RequestPart(value="file",required=false)MultipartFile file) {
        User u=teacher(auth);topic(id);
        if(title==null||title.isBlank()||((file==null||file.isEmpty())&&(content==null||content.isBlank())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a title and choose a file or add text");
        Material m=new Material();m.topicId=id;m.teacherId=u.id();m.title=title.trim();m.description=description;m.content=content;
        if(file!=null&&!file.isEmpty()) {
            m.originalName=Path.of(Objects.requireNonNullElse(file.getOriginalFilename(),"resource").replace('\\','/')).getFileName().toString();
            m.fileName=UUID.randomUUID()+"-"+m.originalName;m.contentType=file.getContentType();
            try {Files.createDirectories(resourceDir);Files.copy(file.getInputStream(),materialPath(m));}
            catch(Exception ex){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Resource upload failed",ex);}
        }
        try {return view(materials.save(m));}
        catch(Exception ex){if(m.fileName!=null)try{Files.deleteIfExists(materialPath(m));}catch(Exception ignored){}throw ex;}
    }
    @GetMapping("/topics/{id}/resources/{resourceId}/file") ResponseEntity<Resource> resourceFile(
            @RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@PathVariable Long resourceId) {
        user(auth);topic(id);Material m=material(id,resourceId);
        if(m.fileName==null||!view(m).available()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"This resource is no longer available");
        String type=Objects.requireNonNullElse(m.contentType,"application/octet-stream");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(Objects.requireNonNullElse(m.originalName,"resource")).build().toString())
                .body(new FileSystemResource(materialPath(m)));
    }
    @DeleteMapping("/topics/{id}/resources/{resourceId}") void deleteResource(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@PathVariable Long resourceId) {
        teacher(auth);topic(id);Material m=material(id,resourceId);materials.delete(m);
        if(m.fileName!=null)try{Files.deleteIfExists(materialPath(m));}catch(Exception ignored){}
    }
    @GetMapping("/topics/{id}/video-ticket") Map<String,String> videoTicket(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable Long id){
        user(auth);LearningTopic lesson=topic(id);if(lesson.videoFile==null||!videoStore.exists(lesson.videoFile))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Video not found");
        String ticket=JWT.create().withIssuer("classroom-media").withSubject(id.toString()).withExpiresAt(java.time.Instant.now().plusSeconds(3600)).sign(jwt);
        return Map.of("url","/api/classroom/topics/"+id+"/video?ticket="+ticket);
    }
    @GetMapping("/topics/{id}/video") ResponseEntity<Resource> getVideo(
            @RequestHeader(value="Authorization",required=false) String auth, @PathVariable Long id,@RequestParam(required=false) String ticket) {
        if(ticket==null)user(auth);else try{String subject=JWT.require(jwt).withIssuer("classroom-media").build().verify(ticket).getSubject();if(!id.toString().equals(subject))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Video ticket is for another lesson");}catch(JWTVerificationException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Video ticket expired");}
        LearningTopic topic = topic(id);
        if (topic.videoFile == null || !videoStore.exists(topic.videoFile))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        Path path = videoStore.resolve(topic.videoFile);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(Objects.requireNonNullElse(topic.videoType, "video/mp4")))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"video\"")
                .body(new FileSystemResource(path));
    }
    @GetMapping("/topics/{id}/comments") java.util.List<TopicComment> comments(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestParam(required=false)Long materialId){user(auth);topic(id);if(materialId!=null)material(id,materialId);return materialId==null?comments.findByTopicIdAndMaterialIdIsNullOrderByCreatedAtAsc(id):comments.findByTopicIdAndMaterialIdOrderByCreatedAtAsc(id,materialId);}
    @PostMapping("/topics/{id}/comments") TopicComment comment(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody CommentInput in){
        User u=user(auth);topic(id);if(in.materialId()!=null)material(id,in.materialId());
        if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Write a comment");
        TopicComment c=new TopicComment();c.topicId=id;c.materialId=in.materialId();c.studentId=u.id();c.authorName=Objects.requireNonNullElse(u.name(),"Member");c.text=in.text().trim();
        if(in.parentId()!=null){
            TopicComment target=comments.findById(in.parentId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Comment not found"));
            if(!id.equals(target.topicId)||!Objects.equals(in.materialId(),target.materialId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Comment belongs to another resource");
            c.replyToName=target.authorName;
            Set<Long> seen=new HashSet<>();
            while(target.parentId!=null&&seen.add(target.id))target=comments.findById(target.parentId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Original comment not found"));
            c.parentId=target.id;
        }
        return comments.save(c);
    }
    @DeleteMapping("/topics/{id}/comments/{commentId}") void deleteComment(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@PathVariable Long commentId){User actor=user(auth);if(!"ADMIN".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);TopicComment comment=comments.findById(commentId).filter(item->id.equals(item.topicId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));comments.delete(comment);}
    @GetMapping("/topics/{id}/notes") java.util.List<TopicStudentNote> listNotes(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){User u=user(auth);topic(id);return notes.findByTopicIdAndStudentIdOrderByCreatedAtDesc(id,u.id());}
    @GetMapping("/admin/notes") java.util.List<TopicStudentNote> adminNotes(@RequestHeader(value="Authorization",required=false)String auth){User u=user(auth);if(!"ADMIN".equals(u.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return notes.findAll();}
    @PostMapping("/topics/{id}/notes") TopicStudentNote addNote(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody NoteInput in){User u=user(auth);if(!u.role().equals("STUDENT"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Student notes are private to student accounts");topic(id);TopicStudentNote n=new TopicStudentNote();n.topicId=id;n.studentId=u.id();n.title=in.title();n.text=in.text();return notes.save(n);}
    @PutMapping("/notes/{id}") TopicStudentNote editNote(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@RequestBody NoteInput in){User u=user(auth);TopicStudentNote n=notes.findById(id).orElseThrow();if(!n.studentId.equals(u.id())&&!"ADMIN".equals(u.role()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(in.title()==null||in.title().isBlank()||in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);n.title=in.title().trim();n.text=in.text().trim();return notes.save(n);}
    @PutMapping("/topics/{id}/comments/{commentId}") TopicComment editComment(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id,@PathVariable Long commentId,@RequestBody CommentInput in){User actor=user(auth);if(!"ADMIN".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);TopicComment comment=comments.findById(commentId).filter(item->id.equals(item.topicId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);comment.text=in.text().trim();return comments.save(comment);}
    @DeleteMapping("/notes/{id}") void deleteNote(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable Long id){User u=user(auth);TopicStudentNote n=notes.findById(id).orElseThrow();if(!n.studentId.equals(u.id())&&!"ADMIN".equals(u.role()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);notes.delete(n);}
}
