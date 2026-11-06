package com.example.dashboard.catalog;

import com.example.dashboard.media.ImageOptimizer;
import com.example.dashboard.media.MediaLimits;
import com.example.dashboard.media.StorageQuota;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class CatalogController {
    private static final int MAX_PRODUCT_IMAGES = 3;
    private static final int MAX_STORED_PRODUCT_IMAGE_BYTES = 30_000;
    private final ProductRepository products;
    private final Algorithm algorithm;
    private final Path resourceDir;
    private final String authUrl;

    CatalogController(ProductRepository products, @Value("${app.jwt.secret}") String secret, @Value("${services.auth:http://localhost:8081}") String authUrl,
                      @Value("${app.product-resource-dir:${app.resource-dir:${user.dir}/../resources/products}}") String resourceDir) {
        this.products = products;
        this.algorithm = Algorithm.HMAC256(secret);
        this.authUrl = authUrl;
        this.resourceDir = Path.of(resourceDir).toAbsolutePath().normalize();
    }

    record NewProduct(@NotBlank String name, @NotBlank String description, @NotBlank String category,
                      @NotNull @DecimalMin("0.01") BigDecimal price, @NotBlank String currency, @Min(1) int stock, Long onBehalfOfUserId) {}
    record ProductUpdate(@NotBlank String name, @NotBlank String description, @NotBlank String category,
                         @NotNull @DecimalMin("0.01") BigDecimal price, @NotBlank String currency, @Min(0) int stock,
                         Long onBehalfOfUserId, Boolean asAdmin) {}

    private String currency(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("INR", "USD", "EUR", "GBP").contains(code))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose INR, USD, EUR, or GBP");
        return code;
    }
    private BigDecimal inr(BigDecimal amount, String currency) {
        BigDecimal rate = switch (currency) { case "USD" -> new BigDecimal("83.00"); case "EUR" -> new BigDecimal("90.00"); case "GBP" -> new BigDecimal("105.00"); default -> BigDecimal.ONE; };
        return amount.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private Long sellerId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        try {
            var jwt = JWT.require(algorithm).withIssuer("dashboard-auth").build()
                    .verify(authorization.substring(7));
            if (!Boolean.TRUE.equals(jwt.getClaim("seller").asBoolean()) && !"ADMIN".equals(jwt.getClaim("role").asString()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Register as a seller first");
            return Long.valueOf(jwt.getSubject());
        } catch (JWTVerificationException | NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
    }

    private boolean isAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return "ADMIN".equals(JWT.require(algorithm).withIssuer("dashboard-auth").build().verify(authorization.substring(7)).getClaim("role").asString()); }
        catch (JWTVerificationException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }
    private void validateRepresentedUser(String authorization, Long id) {
        try { var connection = (java.net.HttpURLConnection) new java.net.URL(authUrl + "/api/auth/admin/users/" + id).openConnection(); connection.setRequestProperty("Authorization", authorization); connection.setConnectTimeout(3000); connection.setReadTimeout(3000); if (connection.getResponseCode() != 200) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"); connection.disconnect(); }
        catch (java.io.IOException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Auth service unavailable", ex); }
    }

    @GetMapping
    public List<Product> list(@RequestParam(defaultValue = "") String search) {
        String term = search.trim().toLowerCase();
        return products.findAll().stream().filter(p -> term.isEmpty()
                || p.name.toLowerCase().contains(term)
                || p.category.toLowerCase().contains(term)).toList();
    }

    @GetMapping("/{id}")
    public Product one(@PathVariable Long id) {
        return products.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @GetMapping("/mine")
    public List<Product> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return products.findBySellerIdOrderByIdDesc(sellerId(authorization));
    }

    @PostMapping(value="/{id}/image", consumes="multipart/form-data")
    @Transactional
    public Product image(@RequestHeader(value="Authorization",required=false) String authorization,
                         @PathVariable Long id,@RequestParam("file") MultipartFile file) {
        return replaceImages(authorization, id, List.of(file));
    }

    @PostMapping(value="/{id}/images", consumes="multipart/form-data")
    @Transactional
    public Product images(@RequestHeader(value="Authorization",required=false) String authorization,
                          @PathVariable Long id,@RequestParam("files") List<MultipartFile> files) {
        return replaceImages(authorization, id, files);
    }

    private Product replaceImages(String authorization, Long id, List<MultipartFile> files) {
        Long seller=sellerId(authorization); Product product=products.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!seller.equals(product.sellerId) && !isAdmin(authorization)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the product's seller can replace its images");
        if(files==null||files.isEmpty()||files.size()>MAX_PRODUCT_IMAGES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose between 1 and 3 product images");

        List<byte[]> encodedImages=new ArrayList<>();
        for(MultipartFile file:files){
            String type=file.getContentType()==null?"":file.getContentType();
            if(file.isEmpty()||!type.startsWith("image/"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload image files only");
            if(file.getSize()>MediaLimits.maxImageUploadBytes())throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Choose images smaller than "+MediaLimits.megabytes(MediaLimits.maxImageUploadBytes()));
            try{encodedImages.add(ImageOptimizer.optimize(file.getInputStream(),MAX_STORED_PRODUCT_IMAGE_BYTES));}
            catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e);}
            catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Image upload failed",e);}
        }

        long totalBytes=encodedImages.stream().mapToLong(bytes->bytes.length).sum();
        StorageQuota.reserve(totalBytes);
        List<String> names=encodedImages.stream().map(bytes->UUID.randomUUID()+".jpg").toList();
        try{
            Files.createDirectories(resourceDir);
            for(int index=0;index<names.size();index++)Files.write(resourceDir.resolve(names.get(index)),encodedImages.get(index));
        }catch(Exception e){
            names.forEach(name->{try{Files.deleteIfExists(resourceDir.resolve(name));}catch(Exception ignored){}});
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Image upload failed",e);
        }

        List<String> previous=product.getImageFiles();
        product.imageFile=names.get(0);
        product.imageFile2=names.size()>1?names.get(1):null;
        product.imageFile3=names.size()>2?names.get(2):null;
        Product saved=products.save(product);
        previous.forEach(this::deleteImage);
        return saved;
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return productImage(id,0);
    }

    @GetMapping("/{id}/images/{position}")
    public ResponseEntity<byte[]> productImage(@PathVariable Long id,@PathVariable int position) {
        if(position<0||position>=MAX_PRODUCT_IMAGES)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product image not found");
        Product product=products.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> images=product.getImageFiles();
        String file=position<images.size()?images.get(position):position==0?"default.svg":null;
        if(file==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product image not found");
        Path image=resourceDir.resolve(file).normalize();
        if(!image.startsWith(resourceDir)||!java.nio.file.Files.exists(image)) image=resourceDir.resolve("default.svg");
        try {return ResponseEntity.ok().contentType(MediaType.parseMediaType(Files.probeContentType(image)==null?"image/svg+xml":Files.probeContentType(image))).body(Files.readAllBytes(image));}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product image not found",e);}
    }

    private void deleteImage(String file){
        if(file==null||"default.svg".equals(file))return;
        Path image=resourceDir.resolve(file).normalize();
        if(image.startsWith(resourceDir))try{Files.deleteIfExists(image);}catch(Exception ignored){}
    }

    @PostMapping
    @Transactional
    public Product create(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @Valid @RequestBody NewProduct input) {
        Long seller = sellerId(authorization);
        String currency = currency(input.currency());
        boolean admin = isAdmin(authorization);
        if (input.onBehalfOfUserId() != null && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (input.onBehalfOfUserId() != null) validateRepresentedUser(authorization, input.onBehalfOfUserId());
        Product product = new Product(input.onBehalfOfUserId() == null ? seller : input.onBehalfOfUserId(), input.name().trim(), input.description().trim(),
                input.category().trim(), inr(input.price(), currency), currency, input.stock());
        product.createdByAdmin = admin;
        return products.save(product);
    }

    @PutMapping("/{id}")
    @Transactional
    public Product update(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @PathVariable Long id, @Valid @RequestBody ProductUpdate input) {
        Long seller = sellerId(authorization);
        Product product = products.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!seller.equals(product.sellerId) && !isAdmin(authorization)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the product's seller can edit it");
        if (!isAdmin(authorization) && (input.onBehalfOfUserId() != null || Boolean.TRUE.equals(input.asAdmin()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (isAdmin(authorization) && input.onBehalfOfUserId() != null) { validateRepresentedUser(authorization, input.onBehalfOfUserId()); product.sellerId = input.onBehalfOfUserId(); }
        else if (isAdmin(authorization) && Boolean.TRUE.equals(input.asAdmin())) product.sellerId = seller;
        product.name = input.name().trim();
        product.description = input.description().trim();
        product.category = input.category().trim();
        String currency = currency(input.currency());
        product.price = inr(input.price(), currency);
        product.currency = currency;
        product.stock = input.stock();
        return products.save(product);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long id) {
        if (!isAdmin(authorization)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        Product product = products.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        products.delete(product);
        product.getImageFiles().forEach(this::deleteImage);
    }

    @Bean
    CommandLineRunner seed(ProductRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.saveAll(List.of(
                    new Product(0L, "Everyday Notebook", "A durable notebook for daily plans and ideas.", "Stationery", new BigDecimal("999.00"), "INR", 25),
                    new Product(0L, "Ceramic Coffee Cup", "Hand finished ceramic cup for your morning coffee.", "Home", new BigDecimal("1535.50"), "INR", 14),
                    new Product(0L, "Canvas Tote Bag", "Reusable lightweight bag for shopping and travel.", "Accessories", new BigDecimal("1328.00"), "INR", 20)
                ));
            } else {
                List<Product> legacy = repository.findAll().stream().filter(product -> product.currency == null || product.currency.isBlank()).peek(product -> {
                    product.price = inr(product.price, "USD"); product.currency = "INR";
                }).toList();
                if (!legacy.isEmpty()) repository.saveAll(legacy);
            }
        };
    }
}
