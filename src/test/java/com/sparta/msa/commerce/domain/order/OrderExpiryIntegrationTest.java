package com.sparta.msa.commerce.domain.order;

import static com.sparta.msa.commerce.domain.order.entity.CancelReason.EXPIRED;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.CANCELED;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.PAID;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.sparta.msa.commerce.LedgerAssertions;
import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.dto.request.CreateOrderRequest;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.facade.OrderExpiryFacade;
import com.sparta.msa.commerce.domain.order.facade.OrderFacade;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("미결제 주문 만료 처리")
class OrderExpiryIntegrationTest {

  @Autowired
  OrderExpiryFacade orderExpiryFacade;
  @Autowired
  OrderFacade orderFacade;
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
  @Autowired
  PaymentRepository paymentRepository;
  @Autowired
  CommonOrderService commonOrderService;
  @Autowired
  PlatformTransactionManager txManager;
  @MockitoSpyBean
  HotDealStockService hotDealStockService;

  @BeforeEach
  void setUp() {
    paymentRepository.deleteAll();
    orderRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    hotDealRepository.deleteAll();
    productRepository.deleteAll();
    userRepository.deleteAll();
  }

  private void assertLedger() {
    LedgerAssertions.assertLedger(
        hotDealRepository, hotDealStockRepository, orderRepository, paymentRepository);
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("결제 제한시간이 지난 PENDING 주문은 만료 처리 시 CANCELED(EXPIRED)로 전이되고 핫딜 재고가 복원된다")
    void expireOverdueOrder() {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 98));

      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 2, Duration.ofMinutes(10)));

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now().plusMinutes(11), 10);

      Order swept = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(swept.getStatus()).isEqualTo(CANCELED);
      assertThat(swept.getCancelReason()).isEqualTo(EXPIRED);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(100);
      assertLedger();
    }
  }

  @Nested
  @DisplayName("보존 (만료 대상 아님)")
  class Preserve {

    @Test
    @DisplayName("결제 제한시간이 지나지 않은 PENDING 주문은 만료 처리해도 PENDING으로 유지되고 재고가 변하지 않는다")
    void notYetExpiredOrderIsPreserved() {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 98));

      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 2, Duration.ofMinutes(10)));

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now(), 10);

      Order swept = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(swept.getStatus()).isEqualTo(PENDING);
      assertThat(swept.getCancelReason()).isNull();

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(98);
      assertLedger();
    }
  }

  @Nested
  @DisplayName("동시성")
  class Concurrency {

    @Test
    @DisplayName("같은 만료 주문을 여러 스레드가 동시에 만료 처리해도 핫딜 재고는 정확히 1회만 복원된다")
    void concurrentSweepRestoresStockOnce() throws Exception {
      int sweeperCount = 10;

      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 98));

      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 2, Duration.ofMinutes(10)));

      LocalDateTime sweepTime = LocalDateTime.now().plusMinutes(11);

      ExecutorService pool = Executors.newFixedThreadPool(sweeperCount);
      CountDownLatch ready = new CountDownLatch(sweeperCount);
      CountDownLatch startSignal = new CountDownLatch(1);
      Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

      for (int i = 0; i < sweeperCount; i++) {
        pool.submit(() -> {
          ready.countDown();
          try {
            startSignal.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          try {
            orderExpiryFacade.expireOverdueOrders(sweepTime, 10);
          } catch (Throwable t) {
            errors.add(t);
          }
        });
      }

      ready.await();
      startSignal.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);

      Order swept = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(swept.getStatus()).isEqualTo(CANCELED);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(100);

      assertThat(errors).isEmpty();
      assertLedger();
    }
  }

  @Nested
  @DisplayName("재구매")
  class Repurchase {

    @Test
    @DisplayName("만료된 주문이 있어도 같은 회원이 같은 핫딜을 다시 구매할 수 있다")
    void canRepurchaseAfterExpiry() {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 100));

      CreateOrderRequest request = new CreateOrderRequest(product.getId(), 1);

      orderFacade.createOrder(user.getId(), request);
      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now().plusMinutes(11), 10);
      orderFacade.createOrder(user.getId(), request);

      List<Order> orders = orderRepository.findAll();
      assertThat(orders).hasSize(2);
      assertThat(orders).filteredOn(o -> o.getStatus() == CANCELED).hasSize(1);
      assertThat(orders).filteredOn(o -> o.getStatus() == PENDING).hasSize(1);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(99);
      assertLedger();
    }
  }

  @Nested
  @DisplayName("부분 실패 격리")
  class PartialFailureIsolation {

    @Test
    @DisplayName("한 건의 재고 복원이 실패해도 나머지 만료 주문은 처리되고, 실패 건은 PENDING으로 남아 다음 회차에 재시도된다")
    void isolatesFailurePerOrder() {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal poisonDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(poisonDeal.getId(), 98));
      HotDeal healthyDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(healthyDeal.getId(), 98));

      Order poisonOrder = orderRepository.save(
          Order.create(user, poisonDeal, product, 2, Duration.ofMinutes(10)));
      Order healthyOrder = orderRepository.save(
          Order.create(user, healthyDeal, product, 2, Duration.ofMinutes(10)));

      doThrow(new RuntimeException("복원 실패 주입"))
          .when(hotDealStockService).restore(poisonDeal.getId(), 2);

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now().plusMinutes(11), 10);

      Order failedOne = orderRepository.findById(poisonOrder.getId()).orElseThrow();
      assertThat(failedOne.getStatus()).isEqualTo(PENDING);
      HotDealStock poisonStock = hotDealStockRepository.findByHotDealId(poisonDeal.getId()).orElseThrow();
      assertThat(poisonStock.getRemainingQuantity()).isEqualTo(98);

      Order processedOne = orderRepository.findById(healthyOrder.getId()).orElseThrow();
      assertThat(processedOne.getStatus()).isEqualTo(CANCELED);
      HotDealStock healthyStock = hotDealStockRepository.findByHotDealId(healthyDeal.getId()).orElseThrow();
      assertThat(healthyStock.getRemainingQuantity()).isEqualTo(100);
      assertLedger();
    }
  }

  @Nested
  @DisplayName("회차 상한")
  class SweepLimit {

    @Test
    @DisplayName("한 회차는 LIMIT 건까지만 처리하고 나머지는 다음 회차로 넘긴다")
    void processesUpToLimitPerSweep() {
      User user1 = userRepository.save(
          User.create("buyer1@test.com", passwordEncoder.encode("password123"), "구매자1", UserRole.USER));
      User user2 = userRepository.save(
          User.create("buyer2@test.com", passwordEncoder.encode("password123"), "구매자2", UserRole.USER));
      User user3 = userRepository.save(
          User.create("buyer3@test.com", passwordEncoder.encode("password123"), "구매자3", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 94));

      orderRepository.save(Order.create(user1, hotDeal, product, 2, Duration.ofMinutes(10)));
      orderRepository.save(Order.create(user2, hotDeal, product, 2, Duration.ofMinutes(10)));
      orderRepository.save(Order.create(user3, hotDeal, product, 2, Duration.ofMinutes(10)));

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now().plusMinutes(11), 2);

      List<Order> orders = orderRepository.findAll();
      assertThat(orders).filteredOn(o -> o.getStatus() == CANCELED).hasSize(2);
      assertThat(orders).filteredOn(o -> o.getStatus() == PENDING).hasSize(1);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(98);
      assertLedger();
    }
  }

  @Nested
  @DisplayName("결제↔만료 경합")
  class PaymentExpiryRace {

    @Test
    @DisplayName("결제로 PAID 전이된 주문은 만료 처리가 CANCELED로 덮지 않고 재고도 복원하지 않는다")
    void paidOrderIsNotOverwrittenByExpirySweep() throws Exception {
      User user = userRepository.save(
          User.create("buyer@test.com", passwordEncoder.encode("password123"), "구매자", UserRole.USER));
      Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
      LocalDateTime start = LocalDateTime.now().minusHours(1);
      LocalDateTime end = LocalDateTime.now().plusHours(1);
      HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
          new CreateHotDealRequest(product.getId(), new BigDecimal("9900"), 100, 5, start, end), product));
      hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 98));

      Order order = orderRepository.save(
          Order.create(user, hotDeal, product, 2, Duration.ofMinutes(10)));

      LocalDateTime sweepTime = LocalDateTime.now().plusMinutes(11);

      CountDownLatch paidLocked = new CountDownLatch(1);
      CountDownLatch allowCommit = new CountDownLatch(1);
      Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
      TransactionTemplate txTemplate = new TransactionTemplate(txManager);

      Thread payer = new Thread(() -> {
        try {
          txTemplate.executeWithoutResult(status -> {
            commonOrderService.markPaid(order, LocalDateTime.now());
            paidLocked.countDown();
            awaitQuietly(allowCommit);
          });
        } catch (Throwable t) {
          errors.add(t);
        }
      });

      Thread sweeper = new Thread(() -> {
        try {
          orderExpiryFacade.expireOverdueOrders(sweepTime, 10);
        } catch (Throwable t) {
          errors.add(t);
        }
      });

      payer.start();
      paidLocked.await();
      sweeper.start();
      Thread.sleep(500);
      allowCommit.countDown();
      payer.join();
      sweeper.join();

      assertThat(errors).isEmpty();

      Order result = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(result.getStatus()).isEqualTo(PAID);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(98);
      assertLedger();
    }

    private void awaitQuietly(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }
}
