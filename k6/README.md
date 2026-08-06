# 동시성 벤치마크 실행 가이드

> 설계 정본: [docs/rfc/concurrency-benchmark.md](../docs/rfc/concurrency-benchmark.md) (도구 선정은 2절) · 측정 대상 [재고 동시성 ADR](../docs/adr/concurrency.md)

2전략(조건부 UPDATE · Redis+Lua)을 **같은 워크로드로 전략만 바꿔 재실행**해 비교한다. 낙관락은 측정 완료 후 코드에서 제거됐다([재고 동시성 ADR](../docs/adr/concurrency.md) — 측정 결과는 표에 보존).

> **격리 자동 측정(정본)**: `bash k6/benchmark/run.sh` — 일회용 컨테이너(tmpfs)로 2전략 순회 + 오버셀 검증까지 자동([재고 동시성 ADR](../docs/adr/concurrency.md) 환경 B). 아래는 수동(호스트) 절차다.

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

4. **계정 선행 발급** — 부하에 쓸 계정을 미리 만들어 토큰을 파일로 남긴다
   ```bash
   BASE_URL=http://localhost:8080 bash k6/benchmark/provision-accounts.sh 1000
   ```
   k6 의 `setup()` 안에서 만들면 계정 생성 시간이 k6 총 실행 시간에 들어가고,
   `iterations/s` 의 분모가 그 총 실행 시간이라 처리율이 주문이 아니라 계정 생성 속도가 된다.

5. **k6 부하**
   ```bash
   k6 run -e ACCOUNTS=1000 -e PRODUCT_ID=1 -e TOKENS_FILE=k6/benchmark/results/tokens.json k6/order-flash-sale.js
   ```
   - 배경 조회를 함께 흘리려면 `-e MIX=1`, 조회만 재려면 `-e MIX_ONLY=1`

6. **측정 확인**
   - k6 summary: `order_success`(건수와 초당 처리율) / `order_rejected` / `order_duration`(p95)
   - Grafana: `hikaricp_connections_active`·`_pending`(커넥션 풀 포화), 차감 UPDATE 지연

7. **2전략 반복** — 전략만 바꿔 3·5 재실행 → 결과는 [재고 동시성 ADR](../docs/adr/concurrency.md) 에 정리됨

## 다중 인스턴스 자동 측정

```bash
bash k6/benchmark/run-multi.sh              # 인원 스윕 (SLA 가 깨지는 지점)
MODE=disk bash k6/benchmark/run-multi.sh    # MySQL 데이터를 디스크에 두고
MODE=mix  bash k6/benchmark/run-multi.sh    # 주문 폭주 + 무관한 조회 동시
```

같은 조건을 `ROUNDS`(기본 3)회 반복한다. 회차별 원값은 `k6/benchmark/results/{모드}/`,
접힌 요약은 `results/summary-{모드}.md` 에 자동 생성된다.

## 오버셀 0 검증 (측정 후, DB)

```
HotDealStock.remaining + sum(order_success quantity) == totalQuantity   (오버셀 0)
```
