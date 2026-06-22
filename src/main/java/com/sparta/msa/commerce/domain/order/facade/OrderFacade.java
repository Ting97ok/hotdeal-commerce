package com.sparta.msa.commerce.domain.order.facade;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.service.CommonHotDealService;
import com.sparta.msa.commerce.domain.order.dto.request.CreateOrderRequest;
import com.sparta.msa.commerce.domain.order.dto.response.CreateOrderResponse;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.mapper.OrderMapper;
import com.sparta.msa.commerce.domain.order.service.OrderService;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.service.ProductService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderFacade {

  private final UserService userService;
  private final ProductService productService;
  private final CommonHotDealService commonHotDealService;
  private final OrderService orderService;
  private final HotDealStockService hotDealStockService;
  private final OrderMapper orderMapper;

  @Transactional
  public CreateOrderResponse createOrder(Long userId, CreateOrderRequest request) {
    User user = userService.getById(userId);
    Product product = productService.getProduct(request.productId());
    HotDeal hotDeal = commonHotDealService.getActiveHotDeal(product);
    Order order = orderService.create(user, hotDeal, product, request.quantity());
    hotDealStockService.deduct(hotDeal.getId(), request.quantity());
    return orderMapper.toCreateResponse(order);
  }
}
