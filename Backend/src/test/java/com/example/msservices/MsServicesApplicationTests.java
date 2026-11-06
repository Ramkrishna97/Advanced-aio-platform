package com.example.msservices;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:unified-test;DB_CLOSE_DELAY=-1",
        "app.snapshot-dir=${java.io.tmpdir}/all-ms-service-test-snapshots",
        "app.user-resource-dir=${java.io.tmpdir}/all-ms-service-test-users",
        "app.resource-dir=${java.io.tmpdir}/all-ms-service-test-classroom",
        "app.quiz-dir=${java.io.tmpdir}/all-ms-service-test-quizzes",
        "app.video-dir=${java.io.tmpdir}/all-ms-service-test-videos",
        "app.media-dir=${java.io.tmpdir}/all-ms-service-test-feed"
})
class MsServicesApplicationTests {

    @Test
    void contextLoads() {
    }

}
