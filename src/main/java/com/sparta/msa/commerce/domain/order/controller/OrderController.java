package com.sparta.msa.commerce.domain.order.controller;

import com.sparta.msa.commerce.domain.order.dto.request.CreateOrderRequest;
import com.sparta.msa.commerce.domain.order.dto.response.CreateOrderResponse;
import com.sparta.msa.commerce.domain.order.facade.OrderFacade;
import com.sparta.msa.commerce.global.security.AuthUser;
import com.sparta.msa.commerce.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderFacade orderFacade;

  @PostMapping
  public CreateOrderResponse createOrder(@CurrentUser AuthUser currentUser,
      @RequestBody @Valid CreateOrderRequest request) {
    return orderFacade.createOrder(currentUser.userId(), request);
  }
}
