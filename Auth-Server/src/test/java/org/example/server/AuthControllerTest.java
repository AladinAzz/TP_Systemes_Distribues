package org.example.server;

import org.example.db.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuthControllerTest {

    private MockMvc mockMvc;
    private StubUserRepository userRepository;
    private StubJwtManager jwtManager;

    @BeforeEach
    public void setup() {
        userRepository = new StubUserRepository();
        jwtManager = new StubJwtManager();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(userRepository, jwtManager)).build();
    }

    @Test
    public void testAuthenticate_Success() throws Exception {
        String salt = "c2FsdA==";
        String hash = hashPassword("password", salt);
        userRepository.authData = new String[]{hash, salt};
        jwtManager.nextToken = "jwt-token";

        mockMvc.perform(post("/auth/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    public void testUserExists() throws Exception {
        userRepository.existsAlice = true;
        userRepository.existsBob = false;

        mockMvc.perform(get("/auth/users/alice/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        mockMvc.perform(get("/auth/users/bob/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    public void testGetAllUsers() throws Exception {
        userRepository.users = Collections.singletonList("alice");

        mockMvc.perform(get("/auth/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("alice"));
    }

    @Test
    public void testDeleteUser_Success() throws Exception {
        userRepository.deleteResult = true;

        mockMvc.perform(delete("/auth/users/alice"))
                .andExpect(status().isOk());
    }

    private static String hashPassword(String password, String salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Base64.getDecoder().decode(salt));
        byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private static class StubUserRepository extends UserRepository {
        String[] authData;
        boolean existsAlice;
        boolean existsBob;
        boolean deleteResult;
        List<String> users = Collections.emptyList();

        @Override
        public String[] getAuthData(String username) {
            return authData;
        }

        @Override
        public boolean exists(String username) {
            if ("alice".equals(username)) {
                return existsAlice;
            }
            if ("bob".equals(username)) {
                return existsBob;
            }
            return false;
        }

        @Override
        public boolean deleteUser(String username) {
            return deleteResult;
        }

        @Override
        public List<String> getAllUsers() {
            return users;
        }
    }

    private static class StubJwtManager extends JwtManager {
        String nextToken = "token";

        @Override
        public String generateToken(String username) {
            return nextToken;
        }
    }
}
