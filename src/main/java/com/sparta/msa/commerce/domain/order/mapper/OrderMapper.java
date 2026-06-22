package com.sparta.msa.commerce.domain.order.mapper;

import com.sparta.msa.commerce.domain.order.dto.response.CreateOrderResponse;
import com.sparta.msa.commerce.domain.order.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {

  @Mapping(source = "id", target = "orderId")
  CreateOrderResponse toCreateResponse(Order order);
}
