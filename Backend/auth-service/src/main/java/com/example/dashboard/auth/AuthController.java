package com.example.dashboard.auth;

import com.example.dashboard.media.ImageOptimizer;
import com.example.dashboard.media.MediaLimits;
import com.example.dashboard.media.StorageQuota;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final UserActivityRepository activities;
    private final ActivityRecorder activityRecorder;
    private final Algorithm algorithm;
    private final Path userResourceDir;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    AuthController(UserRepository users, UserActivityRepository activities, ActivityRecorder activityRecorder, @Value("${app.jwt.secret}") String secret,
                   @Value("${app.user-resource-dir}") String userResourceDir) {
        this.users = users;
        this.activities = activities;
        this.activityRecorder = activityRecorder;
        this.algorithm = Algorithm.HMAC256(secret);
        this.userResourceDir = Path.of(userResourceDir).toAbsolutePath().normalize();
    }

    record RegisterRequest(@NotBlank String name, @Email @NotBlank String email,
                           @NotBlank String role,
                           @Size(min = 8) String password) {}
    record AdminUserUpdate(@NotBlank String name, @Email @NotBlank String email,
                           @NotBlank String role, boolean seller, String password) {}
    record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    record ForgotRequest(@Email @NotBlank String email) {}
    record ResetRequest(@NotBlank String token, @Size(min = 8) String password) {}
    record UserView(Long id, String name, String email, boolean seller, String role, List<String> roles, boolean hasPhoto, Instant photoUpdatedAt) {}
    record ActivityView(Long id, String action, Instant createdAt) {}
    record ActivityRequest(@NotBlank @Size(max = 180) String action) {}
    record AuthResponse(String token, UserView user) {}

    private UserView view(AppUser user) {
        String primary = user.role == null ? "STUDENT" : user.role;
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        roles.add(primary); roles.add("BUYER"); if (user.seller) roles.add("SELLER");
        return new UserView(user.id, user.name, user.email, user.seller, primary, List.copyOf(roles), user.photoData != null || user.photoFile != null, user.photoUpdatedAt);
    }

    private AuthResponse signedIn(AppUser user) {
        String token = JWT.create().withIssuer("dashboard-auth").withSubject(user.id.toString()).withClaim("name", user.name)
                .withClaim("seller", user.seller).withClaim("role", user.role == null ? "STUDENT" : user.role)
                .withClaim("version", user.sessionVersion == null ? 0 : user.sessionVersion).withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS)).sign(algorithm);
        return new AuthResponse(token, view(user));
    }

    private AppUser current(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        }
        try {
            var jwt = JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7));
            AppUser user = users.findById(Long.parseLong(jwt.getSubject())).orElseThrow();
            Integer tokenVersion = jwt.getClaim("version").asInt();
            int version = tokenVersion == null ? 0 : tokenVersion;
            if (version != (user.sessionVersion == null ? 0 : user.sessionVersion)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired");
            return user;
        } catch (JWTVerificationException | NumberFormatException | java.util.NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
    }

    private AppUser admin(String authorization) {
        AppUser actor = current(authorization);
        if (!"ADMIN".equals(actor.role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        return actor;
    }

    private AppUser createUser(RegisterRequest request, boolean allowAdmin) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        String role = request.role().trim().toUpperCase();
        if (!(allowAdmin ? Set.of("STUDENT", "TEACHER", "BUYER", "SELLER", "ADMIN") : Set.of("STUDENT", "TEACHER", "BUYER", "SELLER")).contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account role");
        AppUser user = new AppUser(request.name().trim(), email, passwords.encode(request.password()));
        user.role = role;
        user.seller = role.equals("SELLER");
        AppUser saved = users.save(user);
        activityRecorder.record(saved.id, "Created account");
        return saved;
    }

    @PostMapping("/register")
    @Transactional
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return signedIn(createUser(request, false));
    }

    @GetMapping("/admin/users")
    public List<UserView> users(@RequestHeader(value = "Authorization", required = false) String token) {
        admin(token);
        return users.findAll().stream().map(this::view).toList();
    }

    @GetMapping("/admin/users/{id}")
    public UserView adminUser(@RequestHeader(value = "Authorization", required = false) String token, @PathVariable Long id) {
        admin(token);
        return view(users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    }

    @PostMapping("/admin/users")
    @Transactional
    public UserView addUser(@RequestHeader(value = "Authorization", required = false) String token,
                            @Valid @RequestBody RegisterRequest request) {
        admin(token);
        return view(createUser(request, true));
    }

    @PutMapping("/admin/users/{id}")
    @Transactional
    public UserView editUser(@RequestHeader(value = "Authorization", required = false) String token,
                             @PathVariable Long id, @Valid @RequestBody AdminUserUpdate input) {
        admin(token);
        AppUser user = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String role = input.role().trim().toUpperCase();
        if (!Set.of("STUDENT", "TEACHER", "BUYER", "SELLER", "ADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account role");
        if ("ADMIN".equals(user.role) && !"ADMIN".equals(role) && users.findAll().stream().filter(candidate -> "ADMIN".equals(candidate.role)).count() <= 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last admin account cannot be changed to another role");
        String email = input.email().trim().toLowerCase();
        if (!email.equals(user.email) && users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        if (input.password() != null && !input.password().isBlank() && input.password().length() < 8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must have at least 8 characters");
        user.name = input.name().trim(); user.email = email; user.role = role;
        user.seller = input.seller() || "SELLER".equals(role);
        if (input.password() != null && !input.password().isBlank()) user.passwordHash = passwords.encode(input.password());
        user.sessionVersion = (user.sessionVersion == null ? 0 : user.sessionVersion) + 1;
        return view(users.saveAndFlush(user));
    }

    @DeleteMapping("/admin/users/{id}")
    @Transactional
    public void deleteUser(@RequestHeader(value = "Authorization", required = false) String token, @PathVariable Long id) {
        AppUser actor = admin(token);
        if (actor.id.equals(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The admin account cannot delete itself");
        AppUser target = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if ("ADMIN".equals(target.role) && users.findAll().stream().filter(candidate -> "ADMIN".equals(candidate.role)).count() <= 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last admin account cannot be deleted");
        activities.deleteAll(activities.findByUserId(id));
        users.delete(target);
        if (target.photoFile != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deletePhoto(target.photoFile); }
        });
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.findByEmail("admin".equalsIgnoreCase(request.email().trim()) ? "admin@oneplace.local" : request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwords.matches(request.password(), user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        activityRecorder.record(user.id, "Signed in");
        return signedIn(user);
    }

    @GetMapping("/me")
    public UserView me(@RequestHeader(value = "Authorization", required = false) String token) {
        return view(current(token));
    }

    @PostMapping("/seller")
    @Transactional
    public AuthResponse becomeSeller(@RequestHeader(value = "Authorization", required = false) String token) {
        AppUser user = current(token);
        if (user.seller) return signedIn(user);
        user.seller = true;
        // Flush before replying: a follow-up /me request must observe this change immediately.
        users.saveAndFlush(user);
        activityRecorder.record(user.id, "Enabled seller workspace");
        return signedIn(user);
    }

    @GetMapping("/activities")
    public List<ActivityView> activity(@RequestHeader(value = "Authorization", required = false) String token) {
        // The profile panel is a convenience view. Never let an unbounded activity
        // history make opening the profile slow or block an account update.
        return activities.findTop50ByUserIdOrderByCreatedAtDesc(current(token).id).stream()
                .map(item -> new ActivityView(item.id, item.action, item.createdAt)).toList();
    }

    @PostMapping("/activities")
    @Transactional
    public void recordActivity(@RequestHeader(value = "Authorization", required = false) String token,
                               @Valid @RequestBody ActivityRequest request) {
        String action = request.action().trim();
        if (!Set.of("Listed a product", "Updated a product", "Placed an order", "Added a classroom resource", "Added a private note", "Uploaded a classroom video").contains(action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported activity");
        activityRecorder.record(current(token).id, action);
    }

    @PostMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public UserView uploadPhoto(@RequestHeader(value = "Authorization", required = false) String token,
                                @RequestParam("file") MultipartFile file) {
        AppUser user = current(token);
        if (file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("image/"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image file");
        if (file.getSize() > MediaLimits.maxImageUploadBytes())
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Choose an image smaller than " + MediaLimits.megabytes(MediaLimits.maxImageUploadBytes()));
        try {
            byte[] encoded;
            try (var input = file.getInputStream()) { encoded = ImageOptimizer.optimize(input); }
            StorageQuota.reserve(encoded.length);
            Files.createDirectories(userResourceDir);
            Path staged = Files.createTempFile(userResourceDir, ".profile-", ".tmp");
            String name = UUID.randomUUID() + ".jpg";
            Path destination = userResourceDir.resolve(name);
            try {
                Files.write(staged, encoded);
                try { Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(staged, destination); }
            } finally { Files.deleteIfExists(staged); }
            String previous = user.photoFile;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    if (previous != null) deletePhoto(previous);
                }
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deletePhoto(name);
                }
            });
            user.photoFile = name;
            user.photoData = null;
            user.photoUpdatedAt = Instant.now();
            // Keep the cropped image out of the user row so account updates do not
            // load or rewrite image bytes; the file and DB pointer commit together.
            users.saveAndFlush(user);
            activityRecorder.record(user.id, "Updated profile photo");
            return view(user);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save profile photo", ex);
        }
    }

    private void deletePhoto(String name) {
        try {
            Path path = userResourceDir.resolve(name).normalize();
            if (path.startsWith(userResourceDir)) Files.deleteIfExists(path);
        } catch (Exception ignored) { /* Cleanup must not undo a committed profile. */ }
    }

    @GetMapping("/me/photo")
    public ResponseEntity<Resource> photo(@RequestHeader(value = "Authorization", required = false) String token) {
        AppUser user = current(token);
        if (user.photoData != null) return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .body(new org.springframework.core.io.ByteArrayResource(user.photoData));
        if (user.photoFile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found");
        Path photo = userResourceDir.resolve(user.photoFile).normalize();
        if (!photo.startsWith(userResourceDir) || !Files.isRegularFile(photo)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found");
        try { return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.parseMediaType(java.util.Objects.requireNonNullElse(Files.probeContentType(photo), "image/jpeg")))
                .body(new FileSystemResource(photo)); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found"); }
    }

    @GetMapping("/users/{id}/photo")
    public ResponseEntity<Resource> userPhoto(@PathVariable Long id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.photoData != null) return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .body(new org.springframework.core.io.ByteArrayResource(user.photoData));
        if (user.photoFile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found");
        Path photo = userResourceDir.resolve(user.photoFile).normalize();
        if (!photo.startsWith(userResourceDir) || !Files.isRegularFile(photo)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found");
        try { return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.parseMediaType(java.util.Objects.requireNonNullElse(Files.probeContentType(photo), "image/jpeg")))
                .body(new FileSystemResource(photo)); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile photo not found"); }
    }

    @GetMapping("/users/{id}/display-name")
    public Map<String, String> userDisplayName(@PathVariable Long id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return Map.of("name", user.name);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @PostMapping("/forgot-password")
    @Transactional
    public Map<String, String> forgot(@Valid @RequestBody ForgotRequest request) {
        var found = users.findByEmail(request.email().trim().toLowerCase());
        if (found.isEmpty()) return Map.of("message", "If the account exists, a reset code was created");
        if ("ADMIN".equals(found.get().role)) return Map.of("message", "If the account exists, a reset code was created");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AppUser user = found.get();
        user.resetTokenHash = hash(code);
        user.resetExpiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        users.save(user);
        // Local H2 demo: return the code because no mail provider is configured.
        return Map.of("message", "Reset code created (local demo)", "resetToken", code);
    }

    @PostMapping("/reset-password")
    @Transactional
    public Map<String, String> reset(@Valid @RequestBody ResetRequest request) {
        AppUser user = users.findByResetTokenHash(hash(request.token()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reset code"));
        if ("ADMIN".equals(user.role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (user.resetExpiresAt == null || user.resetExpiresAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset code expired");
        }
        user.passwordHash = passwords.encode(request.password());
        user.resetTokenHash = null;
        user.resetExpiresAt = null;
        users.save(user);
        return Map.of("message", "Password updated");
    }
}
