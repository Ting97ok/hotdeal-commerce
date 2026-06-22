package com.sparta.msa.commerce.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.auth.token.TokenIssuer;
import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.dto.request.CreateOrderRequest;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.entity.OrderStatus;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("핫딜 구매 API")
class CreateOrderIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  ObjectMapper objectMapper;
  @Autowired
  TokenIssuer tokenIssuer;
  @Autowired
  PasswordEncoder passwordEncoder;
  @Autowired
  UserRepository userRepository;
  @Autowired
  ProductRepository productRepository;
  @Autowired
  HotDealRepository hotDealRepository;
  @Autowired
  HotDealStockRepository hotDealStockRepository;
  @Autowired
  OrderRepository orderRepository;

  @BeforeEach
  void setUp() {
    orderRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    hotDealRepository.deleteAll();
    productRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("활성 핫딜 상품을 구매하면 주문이 PENDING으로 생성되고 핫딜 재고가 차감된다")
    void purchaseHotDeal() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      String token = tokenIssuer.createAccessToken(user.getId(), UserRole.USER);
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 100));

      CreateOrderRequest request = new CreateOrderRequest(product.getId(), 2);

      mockMvc.perform(post("/api/orders")
              .header(AUTHORIZATION, "Bearer " + token)
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.orderId").isNumber())
          .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
          .andExpect(jsonPath("$.data.orderAmount").value(19800))
          .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

      assertThat(orderRepository.count()).isEqualTo(1);
      Order order = orderRepository.findAll().get(0);
      assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(order.getQuantity()).isEqualTo(2);
      assertThat(order.getOrderAmount()).isEqualByComparingTo("19800");

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(98);
    }
  }

  @Nested
  @DisplayName("실패")
  class Failure {

    @Test
    @DisplayName("상품에 활성 핫딜이 없으면 NO_ACTIVE_DEAL(404)을 반환한다")
    void noActiveDeal() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      String token = tokenIssuer.createAccessToken(user.getId(), UserRole.USER);
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));

      CreateOrderRequest request = new CreateOrderRequest(product.getId(), 1);

      mockMvc.perform(post("/api/orders")
              .header(AUTHORIZATION, "Bearer " + token)
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_DEAL"));

      assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 회원이 같은 핫딜을 이미 샀으면 ALREADY_PURCHASED(409)를 반환한다")
    void alreadyPurchased() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      String token = tokenIssuer.createAccessToken(user.getId(), UserRole.USER);
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 100));

      CreateOrderRequest request = new CreateOrderRequest(product.getId(), 1);
      mockMvc.perform(post("/api/orders")
              .header(AUTHORIZATION, "Bearer " + token)
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk());

      mockMvc.perform(post("/api/orders")
              .header(AUTHORIZATION, "Bearer " + token)
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ALREADY_PURCHASED"));

      assertThat(orderRepository.count()).isEqualTo(1);
    }
  }
}
