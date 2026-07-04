# 동시성 벤치마크 실행 가이드

> 설계 정본: [docs/design/concurrency-benchmark.md](../docs/design/concurrency-benchmark.md) · 도구 선정 [ADR-0013](../docs/adr/0013-load-test-tool-k6.md) · 측정 대상 [ADR-0009](../docs/adr/0009-stock-concurrency-design.md)

2전략(조건부 UPDATE · Redis+Lua)을 **같은 워크로드로 전략만 바꿔 재실행**해 비교한다. 낙관락은 측정 완료 후 코드에서 제거됐다([ADR-0010](../docs/adr/0010-concurrency-strategy-selection.md) — 측정 결과는 표에 보존).

> **격리 자동 측정(정본)**: `bash k6/benchmark/run.sh` — 일회용 컨테이너(tmpfs)로 2전략 순회 + 오버셀 검증까지 자동([ADR-0010](../docs/adr/0010-concurrency-strategy-selection.md) 환경 B). 아래는 수동(호스트) 절차다.

## 실행 순서

1. **측정 스택 기동** — Prometheus(수집) + Grafana(시각화)
   ```bash
   docker compose -f k6/docker-compose.yml up -d
   ```
   - Prometheus: http://localhost:9090 · Grafana: http://localhost:3000 (익명 Admin)

2. **시드** — `PRODUCT_ID`에 상품·`ProductStock`·활성 `HotDeal`·`HotDealStock`(재고) 준비 (admin API 또는 SQL)

3. **앱 기동 (전략 선택)**
   ```bash
   ./gradlew bootRun --args='--stock.deduct.strategy=conditional'   # 또는 redis
   ```

4. **k6 부하**
   ```bash
   k6 run -e ACCOUNTS=1000 -e PRODUCT_ID=1 k6/order-flash-sale.js
   ```

5. **측정 확인**
   - k6 summary: `order_success` / `order_rejected` / `http_req_duration`(p95·p99)
   - Grafana: `hikaricp_connections_active`·`_pending`(커넥션 풀 포화), 낙관락 충돌, 차감 UPDATE 지연

6. **2전략 반복** — 전략만 바꿔 2~5 재실행 → 결과는 [ADR-0010](../docs/adr/0010-concurrency-strategy-selection.md) 에 정리됨

## 오버셀 0 검증 (측정 후, DB)

```
HotDealStock.remaining + sum(order_success quantity) == totalQuantity   (오버셀 0)
```
