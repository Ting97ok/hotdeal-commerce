package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.entity.OrderStatus;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.entity.PaymentStatus;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@DisplayName("결제 승인 API")
class ConfirmPaymentIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired UserRepository userRepository;
  @Autowired ProductRepository productRepository;
  @Autowired HotDealRepository hotDealRepository;
  @Autowired HotDealStockRepository hotDealStockRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired PaymentRepository paymentRepository;
  @Autowired CommonOrderService commonOrderService;
  @MockitoBean PaymentGatewayClient paymentGatewayClient;

  @BeforeEach
  void setUp() {
    paymentRepository.deleteAll();
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
    @DisplayName("PENDING 주문의 결제 승인 시 Payment 행이 DONE으로 생성되고 주문 상태가 PAID로 전이된다")
    void confirmPayment() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      String pgPaymentKey = "toss_pk_abc123";
      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult(pgPaymentKey, UUID.randomUUID().toString(),
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          pgPaymentKey, order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.paymentId").isNumber());

      Order paid = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);

      List<Payment> payments = paymentRepository.findAll();
      assertThat(payments).hasSize(1);
      assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.DONE);
      assertThat(payments.get(0).getPgPaymentKey()).isEqualTo(pgPaymentKey);
    }
  }

  @Nested
  @DisplayName("만료↔결제 경합")
  class ExpiryRace {

    @Test
    @DisplayName("만료로 CANCELED된 주문에 결제 승인이 들어오면 409 ORDER_STATUS_CONFLICT를 반환하고 Payment 행이 생성되지 않는다")
    void rejectsConfirmOnExpiredOrder() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      order.expire();
      orderRepository.save(order);

      String pgPaymentKey = "toss_pk_abc123";
      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult(pgPaymentKey, UUID.randomUUID().toString(),
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          pgPaymentKey, order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_CONFLICT"));

      Order conflicted = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(conflicted.getStatus()).isEqualTo(OrderStatus.CANCELED);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }

  @Nested
  @DisplayName("중복 승인")
  class DuplicateConfirm {

    @Test
    @DisplayName("이미 PAID된 주문에 결제 승인이 들어오면 409 ORDER_STATUS_CONFLICT를 반환하고 Payment 행이 생성되지 않는다")
    void rejectsConfirmOnPaidOrder() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      commonOrderService.markPaid(order);

      String pgPaymentKey = "toss_pk_abc123";
      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult(pgPaymentKey, UUID.randomUUID().toString(),
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          pgPaymentKey, order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_CONFLICT"));

      Order conflicted = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(conflicted.getStatus()).isEqualTo(OrderStatus.PAID);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }

  @Nested
  @DisplayName("금액 불일치")
  class AmountMismatch {

    @Test
    @DisplayName("결제 금액이 주문 금액과 다르면 토스 호출 전에 400 AMOUNT_MISMATCH로 차단되고 Payment가 생성되지 않는다")
    void rejectsConfirmOnAmountMismatch() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      BigDecimal wrongAmount = order.getOrderAmount().subtract(BigDecimal.ONE);
      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), wrongAmount);

      mockMvc.perform(post("/api/payments/confirm")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("AMOUNT_MISMATCH"));

      then(paymentGatewayClient).should(never()).confirm(any(), any(), any());

      Order untouched = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(untouched.getStatus()).isEqualTo(OrderStatus.PENDING);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }
}
