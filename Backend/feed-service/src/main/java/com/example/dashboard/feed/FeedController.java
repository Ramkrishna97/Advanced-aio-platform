package com.example.dashboard.feed;

import com.example.dashboard.media.ImageOptimizer; import com.example.dashboard.media.MediaLimits; import com.example.dashboard.media.StorageQuota;
import com.auth0.jwt.JWT; import com.auth0.jwt.algorithms.Algorithm; import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.databind.*; import jakarta.persistence.*; import java.io.IOException; import java.io.OutputStream; import java.nio.file.*; import java.time.Instant; import java.util.*;
import org.springframework.beans.factory.annotation.Value; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.client.RestTemplate; import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile; import org.springframework.core.io.FileSystemResource;

@RestController @RequestMapping("/api/feed") class FeedController {
  private final PostRepo posts; private final CommentRepo comments; private final NoteRepo notes; private final Algorithm jwt; private final String classroomUrl; private final String authUrl; private final Path mediaDir; private final RestTemplate http=new RestTemplate(); private final ObjectMapper mapper=new ObjectMapper();
  FeedController(PostRepo posts, CommentRepo comments, NoteRepo notes, @Value("${app.jwt.secret}") String secret,@Value("${services.classroom}")String classroomUrl,@Value("${services.auth:http://localhost:8081}")String authUrl,@Value("${app.media-dir:../resources/feed}") String mediaDir){this.posts=posts;this.comments=comments;this.notes=notes;this.jwt=Algorithm.HMAC256(secret);this.classroomUrl=classroomUrl;this.authUrl=authUrl;this.mediaDir=Paths.get(mediaDir).toAbsolutePath().normalize();}
  record User(Long id,String role,String name){} record PostInput(String title,String body,String type,String resourceUrl,String quizId,Long sourceDraftId,Long onBehalfOfUserId){} record PostUpdate(String title,String body,String type,String resourceUrl,String quizId,Long onBehalfOfUserId,Boolean asAdmin){} record CommentInput(String text){} record NoteInput(String text){} record PageView(List<Post> items,int page,int size,boolean hasNext){} record UploadView(String url,String type){} record CompleteUpload(String name,String type,long size,int chunks){}
  private User user(String auth){if(auth==null||!auth.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in required");try{var t=JWT.require(jwt).withIssuer("dashboard-auth").build().verify(auth.substring(7));return new User(Long.valueOf(t.getSubject()),t.getClaim("role").asString(),t.getClaim("name").asString());}catch(JWTVerificationException|NumberFormatException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");}}
  private User teacher(String a){User u=user(a);if(!Set.of("TEACHER","ADMIN").contains(u.role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only teachers and admins can publish to the feed");return u;}
  private User admin(String a){User u=user(a);if(!"ADMIN".equals(u.role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin access required");return u;}
  private boolean gif(String type){return MediaType.IMAGE_GIF_VALUE.equalsIgnoreCase(type);}
  private void validateGif(byte[] bytes){
    if(bytes.length<6)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid GIF file");
    String signature=new String(bytes,0,6,java.nio.charset.StandardCharsets.US_ASCII);
    if(!"GIF87a".equals(signature)&&!"GIF89a".equals(signature))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid GIF file");
  }
  private void validateGif(Path path)throws IOException{try(var input=Files.newInputStream(path)){validateGif(input.readNBytes(6));}}
  private JsonNode representedUser(String authorization,Long id){
    HttpHeaders headers=new HttpHeaders();headers.set("Authorization",authorization);
    try{return http.exchange(authUrl+"/api/auth/admin/users/"+id,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody();}
    catch(org.springframework.web.client.HttpClientErrorException.NotFound e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");}
    catch(org.springframework.web.client.HttpClientErrorException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot represent this user");}
  }
  @GetMapping PageView list(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size){user(a);importLegacyLessons(a);Page<Post> p=posts.findAll(PageRequest.of(Math.max(0,page),Math.min(Math.max(size,1),50),Sort.by(Sort.Direction.DESC,"createdAt").and(Sort.by(Sort.Direction.DESC,"id"))));return new PageView(p.getContent(),p.getNumber(),p.getSize(),p.hasNext());}
  @GetMapping("/{id}") Post read(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){user(a);return posts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Post not found"));}
  private void importLegacyLessons(String authorization) {
    try {
      HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", authorization);
      ResponseEntity<JsonNode> response = http.exchange(classroomUrl + "/api/classroom/feed", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
      if (response.getBody() == null || !response.getBody().isArray()) return;
      for (JsonNode item : response.getBody()) {
        Long legacyId = item.path("id").asLong(); if (legacyId == 0) continue;
        Optional<Post> existing = posts.findByLegacyTopicId(legacyId);
        Post post = existing.orElseGet(Post::new);
        if(existing.isPresent() && Boolean.TRUE.equals(post.adminEdited)) continue;
        String title = item.path("title").asText("Lesson");
        String body = item.path("description").asText("");
        String type = item.path("videoFile").isMissingNode() || item.path("videoFile").isNull() ? "LESSON" : "VIDEO";
        String author = item.path("authorName").asText("Unknown teacher");
        Instant createdAt = item.hasNonNull("createdAt") ? Instant.parse(item.path("createdAt").asText()) : null;
        if (existing.isPresent() && Objects.equals(post.title, title) && Objects.equals(post.body, body)
            && Objects.equals(post.type, type) && Objects.equals(post.authorName, author)
            && Objects.equals(post.createdAt, createdAt)) continue;
        post.legacyTopicId = legacyId; post.title = title; post.body = body; post.type = type;
        post.authorName = author; post.createdAt = createdAt; posts.save(post);
      }
    } catch (Exception ignored) { /* Persisted posts remain visible if classroom is temporarily offline. */ }
  }
  @PostMapping Post create(@RequestHeader(value="Authorization",required=false)String a,@RequestBody PostInput in){
    User u=teacher(a);
    if(in.title()==null||in.title().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A post title is required");
    if(in.onBehalfOfUserId()!=null&&!"ADMIN".equals(u.role))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(in.sourceDraftId()!=null){Optional<Post> existing=posts.findBySourceDraftId(in.sourceDraftId());if(existing.isPresent()){if(!Objects.equals(existing.get().teacherId,u.id))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return existing.get();}}
    Post p=new Post();p.teacherId=u.id;p.authorName=Optional.ofNullable(u.name).orElse("Teacher");
    if("ADMIN".equals(u.role)){
      p.createdByAdmin=true;
      p.authorName="Created by admin";
      if(in.onBehalfOfUserId()!=null){JsonNode represented=representedUser(a,in.onBehalfOfUserId());p.teacherId=in.onBehalfOfUserId();p.authorName=represented.path("name").asText()+" (created by admin)";}
    }
    p.title=in.title().trim();p.body=Optional.ofNullable(in.body()).orElse("");p.type=Optional.ofNullable(in.type()).orElse("POST");p.resourceUrl=in.resourceUrl();p.quizId=in.quizId();p.sourceDraftId=in.sourceDraftId();return posts.save(p);
  }
  @PutMapping("/{id}") Post edit(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody PostUpdate in){
    admin(a);Post post=posts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(in.title()==null||in.title().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A post title is required");
    if(in.onBehalfOfUserId()!=null){JsonNode represented=representedUser(a,in.onBehalfOfUserId());post.teacherId=in.onBehalfOfUserId();post.authorName=represented.path("name").asText()+" (edited by admin)";}
    else if(Boolean.TRUE.equals(in.asAdmin())){post.teacherId=user(a).id();post.authorName="Admin";}
    post.title=in.title().trim();post.body=Optional.ofNullable(in.body()).orElse("");post.type=Optional.ofNullable(in.type()).orElse("POST");post.resourceUrl=in.resourceUrl();post.quizId=in.quizId();post.adminEdited=true;
    if(post.legacyTopicId!=null){HttpHeaders headers=new HttpHeaders();headers.set("Authorization",a);headers.setContentType(MediaType.APPLICATION_JSON);try{http.exchange(classroomUrl+"/api/classroom/topics/"+post.legacyTopicId,HttpMethod.PUT,new HttpEntity<>(Map.of("title",post.title,"description",post.body),headers),Void.class);}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Classroom content could not be updated",e);}}
    return posts.save(post);
  }
  @DeleteMapping("/{id}") void delete(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){
    admin(a);Post post=posts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(post.legacyTopicId!=null){HttpHeaders headers=new HttpHeaders();headers.set("Authorization",a);try{http.exchange(classroomUrl+"/api/classroom/topics/"+post.legacyTopicId,HttpMethod.DELETE,new HttpEntity<>(headers),Void.class);}catch(org.springframework.web.client.HttpClientErrorException.NotFound ignored){}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Classroom content could not be deleted",e);}}
    comments.deleteAll(comments.findByPostIdOrderByCreatedAtAsc(id));notes.deleteAll(notes.findByPostId(id));posts.deleteById(id);
  }
  @DeleteMapping("/{id}/comments/{commentId}") void deleteComment(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@PathVariable Long commentId){admin(a);FeedComment c=comments.findById(commentId).filter(item->item.postId.equals(id)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));comments.delete(c);}
  @PutMapping("/{id}/comments/{commentId}") FeedComment editComment(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@PathVariable Long commentId,@RequestBody CommentInput in){admin(a);FeedComment c=comments.findById(commentId).filter(item->item.postId.equals(id)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);c.text=in.text().trim();return comments.save(c);}
  @GetMapping("/admin/notes") List<FeedNote> adminNotes(@RequestHeader(value="Authorization",required=false)String a){admin(a);return notes.findAll();}
  @DeleteMapping("/admin/notes/{id}") void deleteNote(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){admin(a);FeedNote note=notes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));notes.delete(note);}
  @PutMapping("/admin/notes/{id}") FeedNote editNote(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody NoteInput in){admin(a);FeedNote note=notes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);note.text=in.text().trim();return notes.save(note);}
  @PostMapping(value="/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) UploadView upload(@RequestHeader(value="Authorization",required=false)String a,@RequestParam("file") MultipartFile file){
    teacher(a);if(file.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a file to upload");String type=Optional.ofNullable(file.getContentType()).orElse("");boolean image=type.startsWith("image/");boolean video=type.startsWith("video/");if(!image&&!video)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only image and video files can be uploaded");
    boolean gif=gif(type);long imageLimit=gif?MediaLimits.CLASSROOM_GIF_BYTES:MediaLimits.maxImageUploadBytes();
    if(image&&file.getSize()>imageLimit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,gif?"GIF files must be 50 KB or smaller":"Choose an image smaller than "+MediaLimits.megabytes(imageLimit));if(video&&file.getSize()>MediaLimits.maxVideoBytes())throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Videos must be "+MediaLimits.megabytes(MediaLimits.maxVideoBytes())+" or smaller");
    String original=Optional.ofNullable(file.getOriginalFilename()).orElse("upload");String extension=original.contains(".")?original.substring(original.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]",""):"";String name=UUID.randomUUID()+(gif?".gif":image?".jpg":extension);
    try{Files.createDirectories(mediaDir);if(gif){byte[] bytes=file.getBytes();validateGif(bytes);StorageQuota.reserve(bytes.length);Files.write(mediaDir.resolve(name),bytes);}else if(image){byte[] encoded=ImageOptimizer.optimize(file.getInputStream());StorageQuota.reserve(encoded.length);Files.write(mediaDir.resolve(name),encoded);}else{StorageQuota.reserve(file.getSize());file.transferTo(mediaDir.resolve(name));}}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e);}catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not save the uploaded file",e);}return new UploadView("/api/feed/media/"+name,gif?MediaType.IMAGE_GIF_VALUE:image?MediaType.IMAGE_JPEG_VALUE:type);
  }
  @PutMapping(value="/media/uploads/{id}/{index}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) Map<String,Integer> uploadChunk(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@PathVariable int index,@RequestBody byte[] bytes){teacher(a);if(index<0||index>=MediaLimits.maxUploadChunks()||bytes.length==0||bytes.length>1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid upload chunk");Path folder=mediaDir.resolve("pending").resolve(id.toString());try{StorageQuota.reserve(bytes.length);Files.createDirectories(folder);Path staged=Files.createTempFile(folder,"chunk-",".tmp");try{Files.write(staged,bytes);Files.move(staged,folder.resolve(index+".part"),StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(staged);}}catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Upload chunk was not saved",e);}return Map.of("index",index);}
  @GetMapping("/media/uploads/{id}") List<Integer> uploadedChunks(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){teacher(a);Path folder=mediaDir.resolve("pending").resolve(id.toString());if(!Files.isDirectory(folder))return List.of();try(var files=Files.list(folder)){return files.map(path->path.getFileName().toString()).filter(name->name.matches("[0-9]+\\.part")).map(name->Integer.parseInt(name.substring(0,name.length()-5))).toList();}catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Cannot inspect upload",e);}}
  @PostMapping("/media/uploads/{id}/complete") UploadView completeUpload(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@RequestBody CompleteUpload in){
    teacher(a);
    if(in==null||in.size()<=0||in.chunks()<1||in.chunks()>MediaLimits.maxUploadChunks()||in.type()==null||(!in.type().startsWith("image/")&&!in.type().startsWith("video/")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid upload details");
    boolean image=in.type().startsWith("image/");boolean gif=gif(in.type());long limit=gif?MediaLimits.CLASSROOM_GIF_BYTES:image?MediaLimits.maxImageUploadBytes():MediaLimits.maxVideoBytes();if(in.size()>limit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,gif?"GIF files must be 50 KB or smaller":(image?"Choose an image smaller than ":"Videos must be ")+MediaLimits.megabytes(limit)+" or smaller");
    String extension=Optional.ofNullable(in.name()).filter(n->n.contains(".")).map(n->n.substring(n.lastIndexOf('.')).toLowerCase(Locale.ROOT)).filter(s->s.matches("\\.[a-z0-9]{1,8}")).orElse("");
    String filename=id+(gif?".gif":image?".jpg":extension);Path destination=mediaDir.resolve(filename);Path folder=mediaDir.resolve("pending").resolve(id.toString());
    if(Files.isRegularFile(destination))return new UploadView("/api/feed/media/"+filename,gif?MediaType.IMAGE_GIF_VALUE:image?MediaType.IMAGE_JPEG_VALUE:in.type());
    Path staged=null;
    try{
      StorageQuota.reserve(in.size());Files.createDirectories(mediaDir);staged=Files.createTempFile(mediaDir,id.toString(),".tmp");
      try(OutputStream output=Files.newOutputStream(staged)){
        for(int index=0;index<in.chunks();index++){
          Path chunk=folder.resolve(index+".part");
          if(!Files.isRegularFile(chunk))throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload is incomplete; retry missing chunks");
          Files.copy(chunk,output);
        }
      }
      if(Files.size(staged)!=in.size())throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload size did not match; retry the upload");
      if(gif){validateGif(staged);try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(staged,destination);}}
      else if(image){byte[] optimized;try(var input=Files.newInputStream(staged)){optimized=ImageOptimizer.optimize(input);}Files.write(destination,optimized,StandardOpenOption.CREATE_NEW);}
      else try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(staged,destination);}
      try(var chunks=Files.list(folder)){chunks.forEach(chunk->{try{Files.deleteIfExists(chunk);}catch(IOException ignored){}});}catch(IOException ignored){}
      try{Files.deleteIfExists(folder);}catch(IOException ignored){}
      return new UploadView("/api/feed/media/"+filename,gif?MediaType.IMAGE_GIF_VALUE:image?MediaType.IMAGE_JPEG_VALUE:in.type());
    }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e);}catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Upload could not be completed",e);}
    finally{if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){}}
  }
  @GetMapping("/media/{name:.+}") ResponseEntity<FileSystemResource> media(@PathVariable String name){Path file=mediaDir.resolve(name).normalize();if(!file.startsWith(mediaDir)||!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND);try{String type=name.toLowerCase(Locale.ROOT).endsWith(".gif")?MediaType.IMAGE_GIF_VALUE:Optional.ofNullable(Files.probeContentType(file)).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).body(new FileSystemResource(file));}catch(IOException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}}
  @GetMapping("/{id}/comments") List<FeedComment> comments(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){user(a);return comments.findByPostIdOrderByCreatedAtAsc(id);}
  @PostMapping("/{id}/comments") FeedComment comment(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody CommentInput in){User u=user(a);if(!posts.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Write a comment");FeedComment c=new FeedComment();c.postId=id;c.userId=u.id;c.authorName=Optional.ofNullable(u.name).orElse("Member");c.text=in.text().trim();return comments.save(c);}
  @GetMapping("/{id}/notes") List<FeedNote> notes(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id){User u=user(a);return notes.findByPostIdAndUserIdOrderByCreatedAtDesc(id,u.id);}
  @PostMapping("/{id}/notes") FeedNote note(@RequestHeader(value="Authorization",required=false)String a,@PathVariable Long id,@RequestBody NoteInput in){User u=user(a);if(in.text()==null||in.text().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Write a note");FeedNote n=new FeedNote();n.postId=id;n.userId=u.id;n.text=in.text().trim();return notes.save(n);}
}
@Entity class Post {@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @Column(unique=true) public Long legacyTopicId; @Column(unique=true) public Long sourceDraftId; public Long teacherId; public String authorName; public Boolean createdByAdmin; public Boolean adminEdited; public String title; @Column(length=12000) public String body; public String type; public String resourceUrl; public String quizId; public Instant createdAt=Instant.now();}
@Entity class FeedComment {@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long postId; public Long userId; public String authorName; @Column(length=5000) public String text; public Instant createdAt=Instant.now();}
@Entity class FeedNote {@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long postId; public Long userId; @Column(length=5000) public String text; public Instant createdAt=Instant.now();}
interface PostRepo extends JpaRepository<Post,Long>{Optional<Post> findByLegacyTopicId(Long id);Optional<Post> findBySourceDraftId(Long id);} interface CommentRepo extends JpaRepository<FeedComment,Long>{List<FeedComment> findByPostIdOrderByCreatedAtAsc(Long id);} interface NoteRepo extends JpaRepository<FeedNote,Long>{List<FeedNote> findByPostIdAndUserIdOrderByCreatedAtDesc(Long id,Long userId);List<FeedNote> findByPostId(Long id);}
