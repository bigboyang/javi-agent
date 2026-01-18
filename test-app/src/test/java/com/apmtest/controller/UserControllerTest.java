package com.apmtest.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apmtest.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class UserControllerTest {

    private static final String BASE_URL = "/api/users";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("헬스 체크: API가 정상 응답한다")
    void healthCheck() throws Exception {
        mockMvc.perform(get(BASE_URL + "/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User API is running")));
    }

    @Test
    @DisplayName("사용자 생성 후 ID로 조회된다")
    void createUserAndFetchById() throws Exception {
        String email = uniqueEmail("hong");
        String name = uniqueName("홍길동");
        JsonNode created = createUser(name, email, "010-1111-2222");
        long id = created.get("id").asLong();

        mockMvc.perform(get(BASE_URL + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value("010-1111-2222"));
    }

    @Test
    @DisplayName("전체 사용자 조회: 방금 만든 데이터가 포함된다")
    void getAllUsers() throws Exception {
        String email1 = uniqueEmail("hana");
        String email2 = uniqueEmail("two");
        createUser(uniqueName("김하나"), email1, "010-2222-3333");
        createUser(uniqueName("이둘"), email2, "010-3333-4444");

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + email1 + "')]").value(hasSize(1)))
                .andExpect(jsonPath("$[?(@.email == '" + email2 + "')]").value(hasSize(1)));
    }

    @Test
    @DisplayName("사용자 수정: 변경된 값이 반영된다")
    void updateUser() throws Exception {
        JsonNode created = createUser(uniqueName("박셋"), uniqueEmail("three"), "010-4444-5555");
        long id = created.get("id").asLong();

        String updatedEmail = uniqueEmail("three.updated");
        String updatedName = uniqueName("박셋-수정");
        User updatePayload = new User(updatedName, updatedEmail, "010-9999-8888");
        performJson(put(BASE_URL + "/{id}", id), updatePayload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(updatedName))
                .andExpect(jsonPath("$.email").value(updatedEmail))
                .andExpect(jsonPath("$.phone").value("010-9999-8888"));
    }

    @Test
    @DisplayName("사용자 삭제: 삭제 후 조회하면 404")
    void deleteUser() throws Exception {
        JsonNode created = createUser(uniqueName("삭제"), uniqueEmail("delete"), "010-0000-1111");
        long id = created.get("id").asLong();

        mockMvc.perform(delete(BASE_URL + "/{id}", id))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_URL + "/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("이메일로 조회: 정확한 사용자를 반환한다")
    void getUserByEmail() throws Exception {
        String email = uniqueEmail("mail");
        createUser(uniqueName("메일"), email, "010-5555-6666");

        mockMvc.perform(get(BASE_URL + "/email/{email}", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("이름 검색: 일부 문자열로 검색된다")
    void searchUserByName() throws Exception {
        String name = uniqueName("가나다");
        createUser(name, uniqueEmail("ganada"), "010-1212-3434");
        createUser(uniqueName("나다라"), uniqueEmail("nadara"), "010-5656-7878");

        mockMvc.perform(get(BASE_URL + "/search").param("name", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
    }

    private JsonNode createUser(String name, String email, String phone) throws Exception {
        User payload = new User(name, email, phone);
        MvcResult result = performJson(post(BASE_URL), payload)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                      Object body) throws Exception {
        return mockMvc.perform(request.contentType(JSON).content(objectMapper.writeValueAsString(body)));
    }

    private String uniqueEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueName(String base) {
        return base + "-" + UUID.randomUUID();
    }
}
