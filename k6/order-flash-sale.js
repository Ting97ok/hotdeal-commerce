import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 동시성 벤치마크 — 핫딜 선착순 구매 폭주 (POST /api/orders 경합)
// 설계: docs/design/concurrency-benchmark.md (2장 워크로드)
//
// 실행 전 사전 시드: 상품(Product)·ProductStock·활성 HotDeal·HotDealStock(재고 = STOCK).
//   PRODUCT_ID 에 활성 핫딜이 걸려 있어야 주문이 성립한다.
// 전략 교체: 앱 기동 시 -Dstock.deduct.strategy=optimistic|conditional|redis 로 바꿔 동일하게 재실행.
// 환경변수: BASE_URL, PRODUCT_ID, ACCOUNTS(= 동시 사용자 수).
//   예) k6 run -e ACCOUNTS=1000 -e PRODUCT_ID=1 order-flash-sale.js
// 오버셀 0 검증: 측정 후 DB 로 (HotDealStock 잔여 + order_success 수량 == 총량).
// 재실행 시 1인 1주문 제약 때문에 계정/주문 정리 필요 (email prefix 변경 또는 DB 초기화).

http.setResponseCallback(http.expectedStatuses(200, 409));

const orderSuccess = new Counter('order_success');
const orderRejected = new Counter('order_rejected');
const orderDuration = new Trend('order_duration', true);   // 주문 차감만의 지연 (setup 로그인 지연과 분리)

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const ACCOUNTS = Number(__ENV.ACCOUNTS || 100);

export const options = {
  setupTimeout: '300s',
  scenarios: {
    flashSale: {
      executor: 'shared-iterations',
      vus: ACCOUNTS,
      iterations: ACCOUNTS,   // 계정당 1회 주문 (1인 1주문)
      maxDuration: '1m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],   // 5xx 거의 0 — 거짓 성공/서버오류 방어 (200·409는 정상)
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// VU 별 고유 계정 회원가입 + 로그인 → 토큰 배열 (1인 1주문 유니크 충족)
export function setup() {
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `bench${i}@test.com`;
    const password = 'password123';
    http.post(`${BASE}/api/auth/signup`,
        JSON.stringify({ email, password, name: `bench${i}` }), { headers: JSON_HEADERS });
    const res = http.post(`${BASE}/api/auth/login`,
        JSON.stringify({ email, password }), { headers: JSON_HEADERS });
    tokens.push(res.json('data.accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.post(`${BASE}/api/orders`,
      JSON.stringify({ productId: PRODUCT_ID, quantity: 1 }),
      { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });

  orderDuration.add(res.timings.duration);
  if (res.status === 200) {
    orderSuccess.add(1);
  } else {
    orderRejected.add(1);   // 409: 품절(OUT_OF_STOCK) / 경합 밀림(CONCURRENT_UPDATE_CONFLICT)
  }
  check(res, { 'no 5xx': (r) => r.status < 500 });
}
