package com.sparta.msa.commerce.domain.stock.repository;

import com.sparta.msa.commerce.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, Long> {
}
