package com.sparta.msa.commerce.domain.payment.controller;

import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.dto.response.ConfirmPaymentResponse;
import com.sparta.msa.commerce.domain.payment.facade.PaymentFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

  private final PaymentFacade paymentFacade;

  @PostMapping("/confirm")
  public ConfirmPaymentResponse confirm(@RequestBody @Valid ConfirmPaymentRequest request) {
    return paymentFacade.confirm(request);
  }
}
