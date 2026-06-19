package com.sparta.msa.commerce.domain.hotdeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
@DisplayName("핫딜 등록 API")
class CreateHotDealIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  ObjectMapper objectMapper;
  @Autowired
  HotDealRepository hotDealRepository;
  @Autowired
  HotDealStockRepository hotDealStockRepository;
  @Autowired
  ProductRepository productRepository;
  @Autowired
  ProductStockRepository productStockRepository;

  @BeforeEach
  void setUp() {
    hotDealRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    productStockRepository.deleteAll();
    productRepository.deleteAll();
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("정상 등록 시 상품 재고가 예약되고 핫딜·핫딜재고가 생성된다")
    void createHotDealWithStock() throws Exception {
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      productStockRepository.save(ProductStock.create(product.getId(), 1000));
      CreateHotDealRequest request = new CreateHotDealRequest(
          product.getId(),
          new BigDecimal("9900"),
          100,
          LocalDateTime.of(2026, 6, 20, 7, 0),
          LocalDateTime.of(2026, 6, 20, 9, 0));

      mockMvc.perform(post("/api/admin/hotdeals")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.hotDealId").isNumber());

      assertThat(hotDealRepository.count()).isEqualTo(1);

      Long hotDealId = hotDealRepository.findAll().get(0).getId();
      HotDealStock stock = hotDealStockRepository.findAll().get(0);
      assertThat(stock.getHotDealId()).isEqualTo(hotDealId);
      assertThat(stock.getRemainingQuantity()).isEqualTo(100);

      ProductStock productStock = productStockRepository.findByProductId(product.getId()).orElseThrow();
      assertThat(productStock.getReservedQuantity()).isEqualTo(100);
    }
  }

  @Nested
  @DisplayName("실패")
  class Failure {

    @Test
    @DisplayName("상품 가용 재고가 총 한정 수량보다 적으면 INSUFFICIENT_PRODUCT_STOCK(409)을 반환하고 핫딜이 생성되지 않는다")
    void insufficientProductStock() throws Exception {
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      productStockRepository.save(ProductStock.create(product.getId(), 50));
      CreateHotDealRequest request = new CreateHotDealRequest(
          product.getId(),
          new BigDecimal("9900"),
          100,
          LocalDateTime.of(2026, 6, 20, 7, 0),
          LocalDateTime.of(2026, 6, 20, 9, 0));

      mockMvc.perform(post("/api/admin/hotdeals")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_PRODUCT_STOCK"));

      assertThat(hotDealRepository.count()).isZero();
      assertThat(hotDealStockRepository.count()).isZero();
    }
  }
}
