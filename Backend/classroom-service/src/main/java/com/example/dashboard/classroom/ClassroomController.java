package com.example.dashboard.classroom;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/classroom/materials")
class ClassroomController {
    private final MaterialRepository materials; private final Algorithm algorithm; private final Path resourceDir;
    ClassroomController(MaterialRepository materials, @Value("${app.jwt.secret}") String secret,
                        @Value("${app.resource-dir}") String dir) {
        this.materials=materials; this.algorithm=Algorithm.HMAC256(secret); this.resourceDir=Path.of(dir).toAbsolutePath().normalize();
    }
    record Principal(Long id, String role) {}
    record Note(@NotBlank String title, String content) {}
    record MaterialUpdate(@NotBlank String title, String description, String content) {}
    private Principal principal(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in required");
        try { var jwt=JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7));
            String role=jwt.getClaim("role").asString();
            if (!Set.of("STUDENT","TEACHER","ADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Classroom access required");
            return new Principal(Long.valueOf(jwt.getSubject()),role);
        } catch(JWTVerificationException | NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session"); }
    }
    @GetMapping public List<Material> list(@RequestHeader(value="Authorization",required=false) String auth) { principal(auth); return materials.findAllByOrderByCreatedAtDesc(); }
    @DeleteMapping("/{id}") public void delete(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable Long id) {
        Principal actor=principal(auth);if(!"ADMIN".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Material material=materials.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        materials.delete(material);
        if(material.fileName!=null)try{Files.deleteIfExists(resourceDir.resolve(material.fileName).normalize());}catch(java.io.IOException ignored){}
    }
    @PutMapping("/{id}") public Material edit(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable Long id,@RequestBody MaterialUpdate in) {
        Principal actor=principal(auth);if(!"ADMIN".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(in.title()==null||in.title().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Title required");
        Material material=materials.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        material.title=in.title().trim();material.description=in.description();material.content=in.content();return materials.save(material);
    }
    @PostMapping public Material create(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Note note) {
        Principal p=principal(auth); if(!Set.of("TEACHER","ADMIN").contains(p.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only teachers and admins can add materials");
        Material m=new Material(); m.title=note.title().trim(); m.content=note.content(); m.teacherId=p.id(); return materials.save(m);
    }
    @PostMapping("/upload") public Material upload(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam("title") String title,@RequestParam("file") MultipartFile file) {
        Principal p=principal(auth); if(!Set.of("TEACHER","ADMIN").contains(p.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only teachers and admins can add materials");
        String original=Objects.requireNonNullElse(file.getOriginalFilename(),"material"); String safe=UUID.randomUUID()+"-"+Path.of(original).getFileName();
        try { Files.copy(file.getInputStream(),resourceDir.resolve(safe)); } catch(Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to save material",ex); }
        Material m=new Material(); m.title=title.trim(); m.fileName=safe; m.contentType=file.getContentType(); m.teacherId=p.id(); return materials.save(m);
    }
    @GetMapping("/{id}/file") public ResponseEntity<byte[]> file(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable Long id) {
        principal(auth); Material m=materials.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(m.fileName==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try { Path file=resourceDir.resolve(m.fileName).normalize(); if(!file.startsWith(resourceDir)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(Objects.requireNonNullElse(m.contentType,"application/octet-stream"))).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+Path.of(m.fileName).getFileName()+"\"").body(Files.readAllBytes(file));
        } catch(java.io.IOException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Material file not found"); }
    }
}
