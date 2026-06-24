package com.sparta.msa.commerce.domain.payment.mapper;

import com.sparta.msa.commerce.domain.payment.dto.response.ConfirmPaymentResponse;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

  @Mapping(source = "id", target = "paymentId")
  ConfirmPaymentResponse toConfirmResponse(Payment payment);
}
