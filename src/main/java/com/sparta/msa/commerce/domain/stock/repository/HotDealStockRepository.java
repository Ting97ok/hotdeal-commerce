package com.sparta.msa.commerce.domain.stock.repository;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotDealStockRepository extends JpaRepository<HotDealStock, Long> {

  Optional<HotDealStock> findByHotDealId(Long hotDealId);
}
