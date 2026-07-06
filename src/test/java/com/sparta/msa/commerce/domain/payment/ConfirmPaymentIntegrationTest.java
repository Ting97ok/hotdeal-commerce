package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.sparta.msa.commerce.domain.order.entity.CancelReason;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.entity.OrderStatus;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.entity.PaymentStatus;
import com.sparta.msa.commerce.domain.payment.facade.PaymentFacade;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
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
import com.sparta.msa.commerce.global.exception.DomainException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("결제 승인 API")
class ConfirmPaymentIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired UserRepository userRepository;
  @Autowired ProductRepository productRepository;
  @Autowired HotDealRepository hotDealRepository;
  @Autowired HotDealStockRepository hotDealStockRepository;
  @Autowired ProductStockRepository productStockRepository;
  @Autowired ProductStockService productStockService;
  @Autowired OrderRepository orderRepository;
  @Autowired PaymentRepository paymentRepository;
  @Autowired CommonOrderService commonOrderService;
  @Autowired PaymentFacade paymentFacade;
  @Autowired TokenIssuer tokenIssuer;
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

  private Product createProductWithStock() {
    Product saved = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    productStockRepository.save(ProductStock.create(saved.getId(), 100));
    productStockService.reserve(saved.getId(), 100);
    return saved;
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("PENDING 주문의 결제 승인 시 Payment 행이 DONE으로 생성되고 주문 상태가 PAID로 전이된다")
    void confirmPayment() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
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
          .willReturn(new PgConfirmResult.Approved(pgPaymentKey,
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          pgPaymentKey, order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.paymentId").isNumber())
          .andExpect(jsonPath("$.data.status").value("DONE"));

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
    @DisplayName("만료 시각이 지난 PENDING 주문(만료 스케줄러 지연)은 토스 호출 없이 409 — markPaid 만료 가드")
    void rejectsConfirmOnOverduePendingOrder() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(2);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(-1)));   // 만료 시각이 이미 지난 PENDING

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_CONFLICT"));

      then(paymentGatewayClient).should(never()).confirm(any(), any(), any());
      assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
          .isEqualTo(OrderStatus.PENDING);
      assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("만료로 CANCELED된 주문에 결제 승인이 들어오면 토스 호출 없이 409 ORDER_STATUS_CONFLICT를 반환하고 Payment 행이 생성되지 않는다")
    void rejectsConfirmOnExpiredOrder() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
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

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_CONFLICT"));

      then(paymentGatewayClient).should(never()).confirm(any(), any(), any());

      Order conflicted = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(conflicted.getStatus()).isEqualTo(OrderStatus.CANCELED);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }

  @Nested
  @DisplayName("중복 승인")
  class DuplicateConfirm {

    @Test
    @DisplayName("이미 PAID된 주문에 결제 승인이 들어오면 토스 호출 없이 409 ORDER_STATUS_CONFLICT를 반환하고 Payment 행이 생성되지 않는다")
    void rejectsConfirmOnPaidOrder() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      commonOrderService.markPaid(order, LocalDateTime.now());

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_CONFLICT"));

      then(paymentGatewayClient).should(never()).confirm(any(), any(), any());

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
      Product product = createProductWithStock();
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
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
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

  @Nested
  @DisplayName("토스 결제 거부")
  class PaymentRejected {

    @Test
    @DisplayName("토스가 결제를 거부하면 402 PAYMENT_REJECTED를 반환하고 주문은 CANCELED(PAYMENT_FAILED)로 종료되며 핫딜·상품 재고가 방출된다")
    void rejectsConfirmWhenGatewayRejects() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult.Rejected());

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isPaymentRequired())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("PAYMENT_REJECTED"));

      Order failed = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(failed.getStatus()).isEqualTo(OrderStatus.CANCELED);
      assertThat(failed.getCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);

      HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(hotDealStock.getRemainingQuantity()).isEqualTo(100);

      ProductStock stock = productStockRepository.findByProductId(product.getId()).orElseThrow();
      assertThat(stock.getOnHandQuantity()).isEqualTo(100);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }

  @Nested
  @DisplayName("토스 미확정")
  class PaymentInDoubt {

    @Test
    @DisplayName("토스가 미확정(InDoubt)을 반환하면 보상 없이 Payment가 IN_DOUBT로 생성되고 주문 PAID·재고 차감이 유지된다")
    void preservesInDoubtWhenGatewayUncertain() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult.InDoubt());

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("IN_DOUBT"))
          .andExpect(jsonPath("$.data.approvedAt").doesNotExist());

      Order preserved = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(preserved.getStatus()).isEqualTo(OrderStatus.PAID);

      List<Payment> payments = paymentRepository.findAll();
      assertThat(payments).hasSize(1);
      assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);
      assertThat(payments.get(0).getPgPaymentKey()).isEqualTo("toss_pk_abc123");

      ProductStock stock = productStockRepository.findByProductId(product.getId()).orElseThrow();
      assertThat(stock.getOnHandQuantity()).isEqualTo(99);
      assertThat(stock.getReservedQuantity()).isEqualTo(99);
    }
  }

  @Nested
  @DisplayName("토스 통신 오류")
  class GatewayError {

    @Test
    @DisplayName("토스 통신 오류(요청 미도달)면 502 PAYMENT_GATEWAY_ERROR, 주문 PENDING 유지, Payment 미생성, 재고 복원")
    void rollsBackWhenGatewayUnreachable() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult.GatewayError());

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadGateway())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("PAYMENT_GATEWAY_ERROR"));

      Order untouched = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(untouched.getStatus()).isEqualTo(OrderStatus.PENDING);

      assertThat(paymentRepository.findAll()).isEmpty();

      ProductStock stock = productStockRepository.findByProductId(product.getId()).orElseThrow();
      assertThat(stock.getReservedQuantity()).isEqualTo(100);
    }
  }

  @Nested
  @DisplayName("동시성")
  class Concurrency {

    @Test
    @DisplayName("같은 PENDING 주문에 여러 결제 승인이 동시에 들어와도 정확히 1건만 PAID로 성공하고 나머지는 ORDER_STATUS_CONFLICT, Payment는 1건만 생성된다")
    void onlyOneConfirmWinsUnderConcurrency() throws Exception {
      int confirmerCount = 8;

      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));

      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willAnswer(invocation -> new PgConfirmResult.Approved(
              "toss_pk_" + UUID.randomUUID(),
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      ExecutorService pool = Executors.newFixedThreadPool(confirmerCount);
      CountDownLatch ready = new CountDownLatch(confirmerCount);
      CountDownLatch startSignal = new CountDownLatch(1);
      AtomicInteger successCount = new AtomicInteger();
      Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

      for (int i = 0; i < confirmerCount; i++) {
        pool.submit(() -> {
          ready.countDown();
          try {
            startSignal.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          try {
            paymentFacade.confirm(user.getId(), request);
            successCount.incrementAndGet();
          } catch (Throwable t) {
            errors.add(t);
          }
        });
      }

      ready.await();
      startSignal.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);

      assertThat(successCount.get()).isEqualTo(1);
      assertThat(errors).hasSize(confirmerCount - 1);
      assertThat(errors).allMatch(t -> t instanceof DomainException
          && ((DomainException) t).getCode().equals("ORDER_STATUS_CONFLICT"));

      Order paid = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);

      assertThat(paymentRepository.findAll()).hasSize(1);

      then(paymentGatewayClient).should(times(1)).confirm(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("소유자 검증")
  class Ownership {

    @Test
    @DisplayName("다른 사용자의 주문을 승인하면 404 ORDER_NOT_FOUND — 토스 미호출·주문 PENDING 유지")
    void rejectsConfirmByNonOwner() throws Exception {
      User owner = userRepository.save(
          User.create("owner@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      User attacker = userRepository.save(
          User.create("attacker@test.com", passwordEncoder.encode("pass"), "공격자", UserRole.USER));
      Product product = createProductWithStock();
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end),
          product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
      Order order = orderRepository.save(
          Order.create(owner, hotDeal, product, 1, Duration.ofMinutes(10)));

      given(paymentGatewayClient.confirm(any(), any(), any()))
          .willReturn(new PgConfirmResult.Approved("toss_pk_abc123",
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          "toss_pk_abc123", order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(attacker.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

      then(paymentGatewayClient).should(never()).confirm(any(), any(), any());

      Order untouched = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(untouched.getStatus()).isEqualTo(OrderStatus.PENDING);

      assertThat(paymentRepository.findAll()).isEmpty();
    }
  }

  @Nested
  @DisplayName("재고 차감")
  class StockDeduction {

    @Test
    @DisplayName("결제 확정 시 ProductStock 실물·예약이 주문 수량만큼 차감된다")
    void confirmDeductsProductStock() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
      Product product = createProductWithStock();
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
          .willReturn(new PgConfirmResult.Approved(pgPaymentKey,
              order.getOrderAmount(), LocalDateTime.now()));

      ConfirmPaymentRequest request = new ConfirmPaymentRequest(
          pgPaymentKey, order.getOrderNo(), order.getOrderAmount());

      mockMvc.perform(post("/api/payments/confirm")
              .header(AUTHORIZATION, "Bearer " + tokenIssuer.createAccessToken(user.getId(), UserRole.USER))
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk());

      ProductStock stock = productStockRepository.findByProductId(product.getId()).orElseThrow();
      assertThat(stock.getOnHandQuantity()).isEqualTo(99);
      assertThat(stock.getReservedQuantity()).isEqualTo(99);
    }
  }
}
