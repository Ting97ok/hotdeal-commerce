package com.sparta.msa.commerce.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.auth.token.TokenIssuer;
import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.dto.request.CreateOrderRequest;
import com.sparta.msa.commerce.domain.order.entity.Order;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("핫딜 구매 동시성")
class CreateOrderConcurrencyIntegrationTest {

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

  @Test
  @DisplayName("서로 다른 N명이 동시에 같은 핫딜을 구매해도 오버셀이 0이고 잔여=초기재고−성공수량 정합이 맞다")
  void concurrentPurchaseNeverOversells() throws Exception {
    int stockQuantity = 50;
    int userCount = 50;
    int quantityPerOrder = 1;

    Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    LocalDateTime start = LocalDateTime.now().minusHours(1);
    LocalDateTime end = LocalDateTime.now().plusHours(1);
    HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
        new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), stockQuantity, 5, start, end), product));
    hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), stockQuantity));

    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < userCount; i++) {
      User user = userRepository.save(User.create(
          "buyer" + i + "@test.com", passwordEncoder.encode("password123"), "구매자" + i, UserRole.USER));
      tokens.add(tokenIssuer.createAccessToken(user.getId(), UserRole.USER));
    }

    String body = objectMapper.writeValueAsString(new CreateOrderRequest(product.getId(), quantityPerOrder));

    ExecutorService pool = Executors.newFixedThreadPool(userCount);
    CountDownLatch ready = new CountDownLatch(userCount);
    CountDownLatch startSignal = new CountDownLatch(1);
    Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

    for (int i = 0; i < userCount; i++) {
      String token = tokens.get(i);
      pool.submit(() -> {
        ready.countDown();
        try {
          startSignal.await();
          int status = mockMvc.perform(post("/api/orders")
                  .header(AUTHORIZATION, "Bearer " + token)
                  .contentType(APPLICATION_JSON)
                  .content(body))
              .andReturn().getResponse().getStatus();
          statuses.add(status);
        } catch (Exception e) {
          statuses.add(-1);
        }
      });
    }

    ready.await();
    startSignal.countDown();
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    long successCount = statuses.stream().filter(status -> status == 200).count();

    assertThat(statuses).hasSize(userCount);
    assertThat(statuses).allMatch(status -> status == 200 || status == 409);

    HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
    int soldQuantity = orderRepository.findAll().stream().mapToInt(Order::getQuantity).sum();

    assertThat(stock.getRemainingQuantity()).isGreaterThanOrEqualTo(0);
    assertThat(stock.getRemainingQuantity()).isEqualTo(stockQuantity - soldQuantity);
    assertThat(orderRepository.count()).isEqualTo(successCount);
  }

  @Test
  @DisplayName("같은 회원이 같은 핫딜을 동시에 여러 번 구매해도 주문은 1건이고 나머지는 ALREADY_PURCHASED(409)다")
  void concurrentDuplicatePurchaseKeepsSingleOrder() throws Exception {
    int stockQuantity = 100;
    int attemptCount = 10;

    Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    LocalDateTime start = LocalDateTime.now().minusHours(1);
    LocalDateTime end = LocalDateTime.now().plusHours(1);
    HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
        new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), stockQuantity, 5, start, end), product));
    hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), stockQuantity));

    User user = userRepository.save(User.create(
        "buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
    String token = tokenIssuer.createAccessToken(user.getId(), UserRole.USER);
    String body = objectMapper.writeValueAsString(new CreateOrderRequest(product.getId(), 1));

    ExecutorService pool = Executors.newFixedThreadPool(attemptCount);
    CountDownLatch ready = new CountDownLatch(attemptCount);
    CountDownLatch startSignal = new CountDownLatch(1);
    Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

    for (int i = 0; i < attemptCount; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          startSignal.await();
          int status = mockMvc.perform(post("/api/orders")
                  .header(AUTHORIZATION, "Bearer " + token)
                  .contentType(APPLICATION_JSON)
                  .content(body))
              .andReturn().getResponse().getStatus();
          statuses.add(status);
        } catch (Exception e) {
          statuses.add(-1);
        }
      });
    }

    ready.await();
    startSignal.countDown();
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    long successCount = statuses.stream().filter(status -> status == 200).count();

    assertThat(statuses).hasSize(attemptCount);
    assertThat(successCount).isEqualTo(1);
    assertThat(orderRepository.count()).isEqualTo(1);
    assertThat(statuses).allMatch(status -> status == 200 || status == 409);
  }
}
