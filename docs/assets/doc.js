/* docs 공통 스크립트. 두 조각 모두 점진 확장이라 실패해도 페이지는 읽힌다 */

/* 목차 초기 상태를 화면 폭과 동기화 — 좁으면 접고, 넓으면 편다.
   폭 경계를 넘는 리사이즈에도 다시 맞춘다(모바일 폭 로드 → 데스크톱 확대 시
   summary 가 숨어 목차를 열 수 없게 되는 문제) */
(function () {
  var toc = document.querySelector('.toc');
  var mq = matchMedia('(max-width: 979px)');
  function sync() { toc.open = !mq.matches; }
  sync();
  if (mq.addEventListener) mq.addEventListener('change', sync); else mq.addListener(sync);
})();

/* 인쇄 시 접힌 항목을 펼친다 — 닫힌 <details> 내용은 인쇄에 나오지 않는다 */
(function () {
  var opened = [];
  window.addEventListener('beforeprint', function () {
    document.querySelectorAll('details:not([open])').forEach(function (d) {
      opened.push(d); d.open = true;
    });
  });
  window.addEventListener('afterprint', function () {
    opened.splice(0).forEach(function (d) { d.open = false; });
  });
})();

/* h3 id 부여 → 목차 하위 목록 생성 → 헤딩 앵커(¶) → 현재 위치 하이라이트.
   전부 점진 확장 — 스크립트가 죽으면 h2 목차·앵커 없음(초기 상태)으로 남는다 */
(function () {
  var tocTop = {};
  document.querySelectorAll('.toc > ol > li').forEach(function (li, i) {
    tocTop['sec-' + (i + 1)] = { li: li, a: li.querySelector('a') };
  });

  /* h3 에 상위 절 번호를 이어 id 를 만든다 (sec-4-3) + 목차 하위 항목 */
  var h2id = null, n = 0;
  document.querySelectorAll('.doc h2[id], .doc h3').forEach(function (h) {
    if (h.tagName === 'H2') { h2id = h.id; n = 0; return; }
    if (!h2id) return;
    n += 1;
    h.id = h2id + '-' + n;
    var t = tocTop[h2id];
    if (!t) return;
    var ol = t.li.querySelector('ol');
    if (!ol) { ol = document.createElement('ol'); t.li.appendChild(ol); }
    var li = document.createElement('li');
    var a = document.createElement('a');
    a.href = '#' + h.id;
    a.textContent = h.textContent;
    a.title = h.textContent;
    li.appendChild(a);
    ol.appendChild(li);
  });

  /* 호버 앵커 — 절 단위 공유용 (목차 텍스트를 다 만든 뒤에 단다) */
  document.querySelectorAll('.doc h2[id], .doc h3[id]').forEach(function (h) {
    var a = document.createElement('a');
    a.className = 'h-anchor';
    a.href = '#' + h.id;
    a.textContent = '¶';
    a.setAttribute('aria-label', '이 절 링크');
    h.appendChild(a);
  });

  /* scroll-spy — 뷰포트 상단(+100px)을 지난 마지막 헤딩이 현재 위치 */
  var heads = Array.prototype.slice.call(document.querySelectorAll('.doc h2[id], .doc h3[id]'));
  var current = null;
  function onScroll() {
    var y = window.scrollY + 100;
    var active = heads[0];
    for (var i = 0; i < heads.length; i++) {
      if (heads[i].offsetTop <= y) active = heads[i]; else break;
    }
    if (!active || active.id === current) return;
    current = active.id;
    var top = current.split('-').slice(0, 2).join('-');
    document.querySelectorAll('.toc .active').forEach(function (el) { el.classList.remove('active'); });
    var t = tocTop[top];
    if (t) { t.li.classList.add('active'); t.a.classList.add('active'); }
    var sub = document.querySelector('.toc a[href="#' + current + '"]');
    if (sub) sub.classList.add('active');
  }
  var tick = false;
  window.addEventListener('scroll', function () {
    if (tick) return;
    tick = true;
    requestAnimationFrame(function () { tick = false; onScroll(); });
  }, { passive: true });
  onScroll();
})();
