package com.sparta.msa.commerce.global;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Import(HttpErrorContractIntegrationTest.BoomController.class)
@DisplayName("HTTP 오류 응답 계약")
class HttpErrorContractIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  @DisplayName("미매핑 URL은 500이 아니라 404와 ApiResponse 실패 구조로 응답한다")
  void respondsNotFoundOnUnmappedUrl() throws Exception {
    mockMvc.perform(get("/api/no-such-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.result").value(false))
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("미지원 HTTP 메서드는 500이 아니라 405와 ApiResponse 실패 구조로 응답한다")
  void respondsMethodNotAllowedOnUnsupportedMethod() throws Exception {
    mockMvc.perform(get("/api/payments/confirm"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.result").value(false))
        .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("처리되지 않은 예외는 고정 메시지 500으로 응답하고 내부 예외 메시지를 노출하지 않는다")
  void hidesInternalMessageOnUnhandledException() throws Exception {
    mockMvc.perform(get("/test/unhandled-boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.result").value(false))
        .andExpect(jsonPath("$.error.code").value("SERVER_ERROR"))
        .andExpect(jsonPath("$.error.message", not(containsString("내부 구현 상세"))));
  }

  @RestController
  static class BoomController {
    @GetMapping("/test/unhandled-boom")
    String boom() {
      throw new IllegalStateException("내부 구현 상세 — 클라이언트에 노출되면 안 됨");
    }
  }
}
