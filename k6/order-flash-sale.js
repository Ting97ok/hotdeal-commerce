import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 동시성 벤치마크 — 핫딜 선착순 구매 폭주 (POST /api/orders 경합)
// 설계와 실측은 docs/rfc/concurrency-benchmark.md
//
// 계정 토큰은 provision-accounts.sh 가 미리 만들어 둔 파일에서 읽는다. setup() 에서
// 만들면 계정 생성 시간이 k6 총 실행 시간에 포함되고, iterations/s 의 분모가 그
// 총 실행 시간이라 처리율이 주문이 아니라 계정 생성 속도를 재게 된다.
// 지금은 총 실행 시간 = 주문 부하 구간이므로 order_success 의 rate 가 곧 주문 처리율이다.
//
// 실행 전 사전 시드: 상품(Product)·ProductStock·활성 HotDeal·HotDealStock(재고 = STOCK).
//   PRODUCT_ID 에 활성 핫딜이 걸려 있어야 주문이 성립한다.
// 전략 교체: 앱 기동 시 -Dstock.deduct.strategy=conditional|redis 로 바꿔 동일하게 재실행.
// 오버셀 0 검증: 측정 후 DB 로 (HotDealStock 잔여 + order_success 수량 == 총량).
// 재실행 시 1인 1주문 제약 때문에 주문 정리 필요 (DELETE FROM orders).
//
// 환경변수
//   BASE_URL·PRODUCT_ID·ACCOUNTS  주문 부하 (ACCOUNTS = 동시 사용자 수 = 총 주문 건수)
//   TOKENS_FILE                   선행 발급한 액세스 토큰 JSON 배열
//   MIX=1                         배경 조회 부하를 함께 준다 (격리 가설 측정)
//   MIX_ONLY=1                    주문 없이 배경 조회만 (배경 부하의 무부하 기준선)
//   MIX_RATE·MIX_DURATION         배경 조회의 도착률과 지속 시간

http.setResponseCallback(http.expectedStatuses(200, 409));

const orderSuccess = new Counter('order_success');
const orderRejected = new Counter('order_rejected');
const orderDuration = new Trend('order_duration', true);     // 주문 차감만의 지연
const lookupDuration = new Trend('lookup_duration', true);   // 배경 조회 지연 — 재고 부하가 번지는지

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const ACCOUNTS = Number(__ENV.ACCOUNTS || 100);
const TOKENS = JSON.parse(open(__ENV.TOKENS_FILE || './benchmark/results/tokens.json'));

const MIX = __ENV.MIX === '1';
const MIX_ONLY = __ENV.MIX_ONLY === '1';
const MIX_RATE = Number(__ENV.MIX_RATE || 50);
const MIX_DURATION = __ENV.MIX_DURATION || '8s';

if (TOKENS.length < ACCOUNTS) {
  throw new Error(`토큰 ${TOKENS.length}개 < 필요 ${ACCOUNTS}개 — provision-accounts.sh 를 먼저 돌릴 것`);
}

const scenarios = {};
if (!MIX_ONLY) {
  scenarios.flashSale = {
    executor: 'shared-iterations',
    vus: ACCOUNTS,
    iterations: ACCOUNTS,   // 계정당 1회 주문 (1인 1주문)
    maxDuration: '2m',
    exec: 'order',
  };
}
if (MIX || MIX_ONLY) {
  // 재고 부하와 무관한 DB 읽기를 같은 시간에 흘린다. 주문 구간(수 초)보다 길게 잡히므로
  // 배경 지연의 절대값은 희석된다 — 기준선 회차도 같은 지속 시간으로 돌려 차이만 읽는다.
  scenarios.background = {
    executor: 'constant-arrival-rate',
    rate: MIX_RATE,
    timeUnit: '1s',
    duration: MIX_DURATION,
    preAllocatedVUs: 20,
    maxVUs: 200,
    exec: 'lookup',
  };
}

export const options = {
  scenarios,
  thresholds: {
    http_req_failed: ['rate<0.01'],   // 5xx 거의 0 — 거짓 성공/서버오류 방어 (200·409는 정상)
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function auth(i) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${TOKENS[i % TOKENS.length]}` };
}

export function order() {
  // 계정당 1주문이라 반복 번호로 배정한다. __VU 는 시나리오가 둘일 때 겹칠 수 있다.
  const res = http.post(`${BASE}/api/orders`,
      JSON.stringify({ productId: PRODUCT_ID, quantity: 1 }),
      { headers: auth(exec.scenario.iterationInTest) });

  orderDuration.add(res.timings.duration);
  if (res.status === 200) {
    orderSuccess.add(1);
  } else {
    orderRejected.add(1);   // 409: 품절(OUT_OF_STOCK) / 경합 밀림(CONCURRENT_UPDATE_CONFLICT)
  }
  check(res, { 'no 5xx': (r) => r.status < 500 });
}

export function lookup() {
  // users 를 PK 단건으로 읽는다. Redis 를 타지 않아 재고 전략과 독립적이다.
  const res = http.get(`${BASE}/api/auth/me`, { headers: auth(exec.scenario.iterationInTest) });
  lookupDuration.add(res.timings.duration);
  check(res, { 'lookup ok': (r) => r.status === 200 });
}
