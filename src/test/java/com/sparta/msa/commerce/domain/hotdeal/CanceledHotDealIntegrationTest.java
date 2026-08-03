package com.sparta.msa.commerce.domain.hotdeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
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
import com.sparta.msa.commerce.domain.payment.client.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("취소된 핫딜 차단")
class CanceledHotDealIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TokenIssuer tokenIssuer;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired UserRepository userRepository;
  @Autowired ProductRepository productRepository;
  @Autowired ProductStockRepository productStockRepository;
  @Autowired ProductStockService productStockService;
  @Autowired HotDealRepository hotDealRepository;
  @Autowired HotDealStockRepository hotDealStockRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired PaymentRepository paymentRepository;
  @MockitoBean PaymentGatewayClient paymentGatewayClient;

  @BeforeEach
  void setUp() {
    paymentRepository.deleteAll();
    orderRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    productStockRepository.deleteAll();
    hotDealRepository.deleteAll();
    productRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("취소된 핫딜의 상품을 구매하려 하면 NO_ACTIVE_DEAL(404)을 반환하고 주문도 재고 차감도 없다")
  void blocksNewOrderOnCanceledHotDeal() throws Exception {
    User user = createUser();
    Product product = createProductWithStock();
    HotDeal hotDeal = openHotDeal(product);
    cancelHotDeal(hotDeal.getId());

    CreateOrderRequest request = new CreateOrderRequest(product.getId(), 1);

    mockMvc.perform(post("/api/orders")
            .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.result").value(false))
        .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_DEAL"));

    assertThat(orderRepository.count()).isZero();
    assertThat(hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow()
        .getRemainingQuantity()).isEqualTo(100);
  }

  @Test
  @DisplayName("주문 뒤 핫딜이 취소되면 결제 승인이 토스 호출 없이 HOTDEAL_CANCELED(409)로 막히고 주문은 PENDING으로 남는다")
  void blocksPaymentConfirmOnCanceledHotDeal() throws Exception {
    User user = createUser();
    Product product = createProductWithStock();
    HotDeal hotDeal = openHotDeal(product);
    Order order = orderRepository.save(Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

    cancelHotDeal(hotDeal.getId());

    ConfirmPaymentRequest request = new ConfirmPaymentRequest(
        "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

    mockMvc.perform(post("/api/payments/confirm")
            .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.result").value(false))
        .andExpect(jsonPath("$.error.code").value("HOTDEAL_CANCELED"));

    then(paymentGatewayClient).should(never()).confirm(any(), any(), any());
    assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PENDING);
    assertThat(paymentRepository.findAll()).isEmpty();
  }

  private User createUser() {
    return userRepository.save(
        User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
  }

  private Product createProductWithStock() {
    Product saved = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    productStockRepository.save(ProductStock.create(saved.getId(), 100));
    productStockService.reserve(saved.getId(), 100);
    return saved;
  }

  private HotDeal openHotDeal(Product product) {
    HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
        new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5,
            LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)),
        product));
    hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 100));
    return hotDeal;
  }

  // 취소 API 가 없다. 운영자가 DB 로 상태를 뒤집는 것을 그대로 재현한다.
  private void cancelHotDeal(Long hotDealId) {
    jdbcTemplate.update("UPDATE hot_deals SET status = 'CANCELED', canceled_at = ? WHERE id = ?",
        Timestamp.valueOf(LocalDateTime.now()), hotDealId);
  }
}
