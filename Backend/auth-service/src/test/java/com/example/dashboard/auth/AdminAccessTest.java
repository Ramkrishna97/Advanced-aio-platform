package com.example.dashboard.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:admin-access;DB_CLOSE_DELAY=-1", "eureka.client.enabled=false", "app.snapshot-dir=build/test-snapshots", "app.user-resource-dir=build/test-users"})
@AutoConfigureMockMvc
class AdminAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void adminCanCreateAndPermanentlyDeleteUser() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin\",\"password\":\"qwertyuiop@123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String adminToken = mapper.readTree(login).path("token").asText();
        String account = "{\"name\":\"Temporary\",\"email\":\"temporary@example.com\",\"role\":\"STUDENT\",\"password\":\"examplepass123\"}";
        String created = mvc.perform(post("/api/auth/admin/users").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(account)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = mapper.readTree(created).path("id").asLong();
        String userLogin = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"temporary@example.com\",\"password\":\"examplepass123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userToken = mapper.readTree(userLogin).path("token").asText();
        mvc.perform(delete("/api/auth/admin/users/" + id).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/auth/admin/users/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isOk());
        JsonNode users = mapper.readTree(mvc.perform(get("/api/auth/admin/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(users.isArray()).isTrue();
    }

    @Test void onlyAdminCanCreateAdminsAndEditingRevokesOldSessions() throws Exception {
        String adminAccount = "{\"name\":\"Second Admin\",\"email\":\"second-admin@example.com\",\"role\":\"ADMIN\",\"password\":\"firstpass123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(adminAccount))
                .andExpect(status().isBadRequest());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin\",\"password\":\"qwertyuiop@123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("token").asText();
        String created = mvc.perform(post("/api/auth/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(adminAccount)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = mapper.readTree(created).path("id").asLong();
        String secondLogin = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"second-admin@example.com\",\"password\":\"firstpass123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String oldToken = mapper.readTree(secondLogin).path("token").asText();
        mvc.perform(put("/api/auth/admin/users/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated Admin\",\"email\":\"second-admin@example.com\",\"role\":\"ADMIN\",\"seller\":false,\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"second-admin@example.com\",\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
    }
}
