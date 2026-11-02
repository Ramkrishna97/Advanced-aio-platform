package com.example.dashboard.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProfilePersistenceTest {
    @Autowired UserRepository users;
    @Autowired TestEntityManager entities;

    @Test void sellerAndPhotoSurviveReloadFromDatabase() {
        AppUser user = new AppUser("Alex", "persistence@example.com", "hash");
        user.seller = true;
        user.photoData = new byte[] { 1, 2, 3, 4 };
        Long id = users.saveAndFlush(user).id;
        entities.clear();
        AppUser reloaded = users.findById(id).orElseThrow();
        assertThat(reloaded.seller).isTrue();
        assertThat(reloaded.photoData).containsExactly(1, 2, 3, 4);
    }
}
