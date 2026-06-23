package com.sparta.msa.commerce.domain.order;

import static com.sparta.msa.commerce.domain.order.entity.CancelReason.EXPIRED;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.CANCELED;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.facade.OrderExpiryFacade;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("미결제 주문 만료 sweep")
class OrderExpiryIntegrationTest {

  @Autowired
  OrderExpiryFacade orderExpiryFacade;
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
    @DisplayName("결제 제한시간이 지난 PENDING 주문은 sweep 시 CANCELED(EXPIRED)로 전이되고 핫딜 재고가 복원된다")
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

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now().plusMinutes(11));

      Order swept = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(swept.getStatus()).isEqualTo(CANCELED);
      assertThat(swept.getCancelReason()).isEqualTo(EXPIRED);

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(100);
    }
  }

  @Nested
  @DisplayName("보존 (만료 대상 아님)")
  class Preserve {

    @Test
    @DisplayName("결제 제한시간이 지나지 않은 PENDING 주문은 sweep해도 PENDING으로 유지되고 재고가 변하지 않는다")
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

      orderExpiryFacade.expireOverdueOrders(LocalDateTime.now());

      Order swept = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(swept.getStatus()).isEqualTo(PENDING);
      assertThat(swept.getCancelReason()).isNull();

      HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDeal.getId()).orElseThrow();
      assertThat(stock.getRemainingQuantity()).isEqualTo(98);
    }
  }
}
