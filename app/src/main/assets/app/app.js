(() => {
 'use strict';
 const $ = id => document.getElementById(id);
 const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

 const APPS = {
  workspace: { name: '워크스페이스', color: '#427ad2', soft: '#e8effb', subtitle: '생각을 작업으로.', noun: '문서' },
  typing: { name: '타이핑', color: '#3b73d1', soft: '#e6effb', subtitle: '한글과 워드 문서.', description: '한글과 워드 문서.\n생각이 또렷해지는 페이지.', noun: '문서' },
  graphs: { name: '그래프즈', color: '#229672', soft: '#e2f2ec', subtitle: '숫자와 데이터.', description: '숫자와 데이터.\n근거가 보이는 스프레드시트.', noun: '스프레드시트' },
  present: { name: '프레젠트', color: '#d47158', soft: '#faebe6', subtitle: '아이디어와 이야기.', description: '아이디어와 이야기.\n설득력 있는 프레젠테이션.', noun: '프레젠테이션' }
 };

 const SVG = {
  alignL: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M3 5h14M3 9.5h9M3 14h12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
  alignC: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M3 5h14M5.5 9.5h9M4 14h12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
  alignR: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M3 5h14M8 9.5h9M5 14h12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
  bullet: '<svg viewBox="0 0 20 20" width="18" height="18"><circle cx="4.5" cy="5.5" r="1.4" fill="currentColor"/><circle cx="4.5" cy="10" r="1.4" fill="currentColor"/><circle cx="4.5" cy="14.5" r="1.4" fill="currentColor"/><path d="M8.5 5.5h8M8.5 10h8M8.5 14.5h8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
  number: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M3.5 6.5h2M4.5 4.2v2.3M3.5 10.8h2.2M3.5 15h2.2M8.5 5.5h8M8.5 10h8M8.5 14.5h8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>',
  link: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M8.5 11.5l3-3M7 13l-1.2 1.2a2.5 2.5 0 01-3.5-3.5L4.5 8.5a2.5 2.5 0 013.5 0M13 7l1.2-1.2a2.5 2.5 0 013.5 3.5l-2.2 2.2a2.5 2.5 0 01-3.5 0" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>',
  chart: '<svg viewBox="0 0 20 20" width="20" height="20"><rect x="3" y="10" width="3.5" height="6.5" rx="1" fill="currentColor" opacity=".6"/><rect x="8.25" y="6" width="3.5" height="10.5" rx="1" fill="currentColor"/><rect x="13.5" y="3.5" width="3.5" height="13" rx="1" fill="currentColor" opacity=".8"/></svg>',
  del: '<svg viewBox="0 0 20 20" width="18" height="18"><path d="M5 6h10M8 6V4.5h4V6M6.5 6l.7 9.5h5.6l.7-9.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  trans: '<svg viewBox="0 0 20 20" width="18" height="18"><rect x="3" y="4.5" width="14" height="11" rx="2.2" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M9 4.5v11" stroke="currentColor" stroke-width="1.4" stroke-dasharray="2 2"/></svg>'
 };

 const pending = new Map();
 let seq = 0, info = null, editor = null, docPath = '', docTitle = '제목 없음', docModified = false, docImported = false;
 let tab = 'home', toastTimer = 0, inspectorShown = true, showIndex = 0;
 let startSection = 'new', startQuery = '', autoSaveEnabled = true, autoSaveTimer = 0;
 let uiPref = { theme: 'system', glassT: 0.55, glassStyle: 'prism', autoSave: true };

 function call(method, args = {}) {
  return new Promise((resolve, reject) => {
   const id = ++seq;
   pending.set(id, { resolve, reject });
   chrome.webview.postMessage({ id, method, args });
  });
 }

 chrome.webview.addEventListener('message', event => {
  const msg = event.data;
  if (msg.event) {
   if (msg.event === 'openArg' && msg.data?.path) openFile(msg.data.path);
   if (msg.event === 'templateArg') openNew(msg.data?.template || 'blank');
   return;
  }
  const task = pending.get(msg.id);
  if (!task) return;
  pending.delete(msg.id);
  msg.ok ? task.resolve(msg.data) : task.reject(Error(msg.error || '작업을 완료하지 못했습니다.'));
 });

 function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
 }

 const cmdImg = name => `<img src="assets/cmd/${name}.svg" alt="" onerror="this.hidden=true">`;
 const toolBtn = (label, action, img, svg, wide) =>
  `<button class="tool${wide ? ' toolwide' : ''}" data-action="${esc(action)}" title="${esc(label)}">${img ? cmdImg(img) : svg || ''}<span>${esc(label)}</span></button>`;
 const group = (name, body) => `<div class="ribbon-group">${body}<small class="group-name">${esc(name)}</small></div>`;

 function updateHeader() {
  $('document-title').textContent = editor ? docTitle : '';
  $('save-state').textContent = !editor ? '' : docModified ? '수정됨' : docImported ? '가져온 문서' : docPath ? '저장됨' : '';
  call('setTitle', { title: editor ? docTitle : '', modified: docModified }).catch(() => {});
 }

 function renderStart(section = startSection) {
  startSection = section;
  $('start').hidden = false;
  $('editor').hidden = true;
  $('find-bar').hidden = true;
  document.body.classList.remove('focus-mode');
  if ($('exit-focus')) $('exit-focus').hidden = true;
  document.querySelectorAll('[data-start]').forEach(b => b.classList.toggle('selected', b.dataset.start === section));
  let html = '';
  const kind = info.kind;
  const app = APPS[kind] || APPS.workspace;
  const q = startQuery.trim().toLowerCase();
  const filteredRecent = (info.recent || []).filter(it => !q || (it.name || '').toLowerCase().includes(q) || (it.path || '').toLowerCase().includes(q));
  if (info.recovery && kind !== 'workspace') {
   html += `<div class="recovery-banner"><span>저장되지 않은 작업의 복구 사본이 있습니다.</span><button data-action="recover">다시 열기</button></div>`;
  }
  if (q) {
   html += `<h2>"${esc(startQuery.trim())}" 검색 결과</h2><p class="subtitle">최근 문서 ${filteredRecent.length}건</p>` + recentList(filteredRecent);
  } else if (section === 'recent') {
   html += `<h2>최근 문서</h2><p class="subtitle">마지막으로 작업한 문서를 이어서 열어보세요. 별표로 상단 고정할 수 있습니다.</p>` + recentList(filteredRecent);
  } else if (kind === 'workspace') {
   html += `<h2>오늘의 작업을 시작하세요</h2><p class="subtitle">문서와 숫자, 이야기를 한곳에서.</p><div class="app-cards">` +
    ['typing', 'graphs', 'present'].map(k => {
     const item = APPS[k];
     return `<button class="app-card" data-launch="${k}"><img src="assets/${k}.png" alt=""><b>${item.name}</b><span>${item.description.replace('\n', '<br>')}</span></button>`;
    }).join('') +
    `</div><div class="section-heading"><h3>최근 작업</h3><button class="text-button" data-start="open">문서 열기</button></div>` +
    recentList(filteredRecent.slice(0, 8));
  } else {
   const templates = kind === 'typing'
    ? [
       ['report', '업무 보고서', '개요·진행 내용·일정 표', '<div class="paper-lines"></div>'],
       ['minutes', '회의록', '안건·결정 사항·담당 액션', '<div class="paper-lines"></div>'],
       ['resume', '이력서 · 소개서', '경력·학력·핵심 역량 정리', '<div class="paper-lines"></div>'],
       ['plan', '프로젝트 계획서', '목표·마일스톤·체크리스트', '<div class="paper-lines"></div>']
      ]
    : kind === 'graphs'
     ? [
        ['budget', '월간 예산', '수입과 지출을 수식으로 정리', '<div class="paper-cells"></div>'],
        ['schedule', '주간 일정표', '담당자별 업무 진행률 추적', '<div class="paper-cells"></div>'],
        ['sales', '매출·손익 분석', '분기 합계·평균·성장률 자동 계산', '<div class="paper-cells"></div>']
       ]
     : [
        ['pitch', '프로젝트 제안', '넓은 화면에 또렷한 메시지', '<div class="paper-slide"><strong>새로운 가능성</strong><small>프로젝트 제안 · 워크스페이스</small></div>'],
        ['weekly', '주간 성과 보고', '핵심 지표·성과·다음 주 계획', '<div class="paper-slide"><strong>주간 성과 보고</strong><small>핵심 지표 요약</small></div>'],
        ['launch', '제품 런칭 덱', '다크 슬레이트 발표 테마', '<div class="paper-slide" style="background:#1e293b"><strong style="color:#f8fafc">제품 런칭</strong><small style="color:#94a3b8">Launch Keynote</small></div>']
       ];
   html += `<h2>새 ${app.noun}</h2><p class="subtitle">빈 작업에서 시작하거나 실무 템플릿을 골라보세요.</p><div class="template-grid">` +
    `<button class="template" data-template="blank"><div class="template-paper"><span class="blank-label">＋</span></div><b>빈 ${app.noun}</b><span class="description">내용을 자유롭게 구성</span></button>` +
    templates.map(t => `<button class="template" data-template="${t[0]}"><div class="template-paper">${t[3]}</div><b>${t[1]}</b><span class="description">${t[2]}</span></button>`).join('') +
    `</div><div class="section-heading"><h3>최근 작업</h3><button class="text-button" data-start="open">다른 문서 열기</button></div>` +
    recentList(filteredRecent.slice(0, 6));
  }
  const sc = $('start-content');
  sc.classList.remove('ws-anim-page');
  void sc.offsetWidth;
  sc.classList.add('ws-anim-page');
  sc.innerHTML = html;
  if (window.PillNav) requestAnimationFrame(() => window.PillNav.refresh());
  updateHeader();
 }

 function recentList(items) {
  if (!items || !items.length) return '<div class="empty">최근에 연 문서가 없습니다.</div>';
  return `<div class="recent-list">${items.map(it =>
   `<div class="recent-row"><button class="recent-item" data-recent="${esc(it.path)}"><img src="assets/${esc(it.kind)}.png" alt=""><span class="recent-info"><b>${it.pinned ? '★ ' : ''}${esc(it.name)}</b><small>${esc(it.path)}</small></span><span class="recent-date">${esc(it.time)}</span></button><button class="recent-act${it.pinned ? ' pinned' : ''}" data-pin-recent="${esc(it.path)}" title="고정">${it.pinned ? '★' : '☆'}</button><button class="recent-act" data-del-recent="${esc(it.path)}" title="삭제">×</button></div>`
  ).join('')}</div>`;
 }

 function renderTabs() {
  const kind = info.kind;
  const items = kind === 'graphs'
   ? [['home', '홈'], ['data', '데이터'], ['formula', '수식'], ['view', '보기']]
   : kind === 'present'
    ? [['home', '홈'], ['insert', '삽입'], ['design', '디자인'], ['show', '슬라이드 쇼']]
    : [['home', '홈'], ['insert', '삽입'], ['layout', '레이아웃'], ['view', '보기']];
  if (!items.some(([k]) => k === tab)) tab = 'home';
  $('tabs').innerHTML = items.map(([k, label]) => `<button class="tab${k === tab ? ' active' : ''}" role="tab" aria-selected="${k === tab}" data-tab="${k}">${label}</button>`).join('');
 }

 function renderRibbon() {
  const kind = info.kind;
  let html = '';
  const fontGroup = group('글꼴', `<div class="font-tools"><div class="font-row"><select id="font-name" aria-label="글꼴"><option>맑은 고딕</option><option>Pretendard</option><option>바탕</option><option>돋움</option><option>Arial</option><option>Calibri</option></select><input id="font-size" aria-label="글자 크기" type="number" min="8" max="96" value="${kind === 'present' ? 22 : 11}"></div><div class="font-row"><button data-action="bold" title="굵게 (Ctrl+B)">${cmdImg('bold')}</button><button data-action="italic" title="기울임 (Ctrl+I)">${cmdImg('italic')}</button><button data-action="underline" title="밑줄 (Ctrl+U)">${cmdImg('underline')}</button><label class="color-btn" title="글자 색">${cmdImg('color')}<input type="color" id="font-color" value="#1d2430"></label></div></div>`);
  const alignGroup = group('정렬', `<div class="compact-tools"><button data-action="alignLeft" title="왼쪽 정렬">${SVG.alignL}</button><button data-action="alignCenter" title="가운데 정렬">${SVG.alignC}</button><button data-action="alignRight" title="오른쪽 정렬">${SVG.alignR}</button>${kind === 'typing' ? `<button data-action="bullet" title="글머리 기호">${SVG.bullet}</button><button data-action="number" title="번호 목록">${SVG.number}</button><button data-action="link" title="하이퍼링크">${SVG.link}</button>` : ''}</div>`);

  if (tab === 'home') {
   html = group('파일', toolBtn('저장', 'save', 'save') + toolBtn('다른 이름', 'saveAs', 'save', '', true)) + fontGroup + alignGroup;
   if (kind === 'typing') {
    html += group('스타일', toolBtn('제목 1', 'h1', 'fontdialog') + toolBtn('제목 2', 'h2', 'fontdialog') + toolBtn('본문', 'p', 'paragraphdialog') + toolBtn('찾기', 'find', 'searchdialog'));
   } else if (kind === 'graphs') {
    html += group('셀 서식', toolBtn('통화', 'currency', 'numberformatcurrency') + toolBtn('백분율', 'percent', 'numberformatpercent') + toolBtn('합계', 'autosum', 'autosum'));
   } else if (kind === 'present') {
    html += group('슬라이드', toolBtn('새 슬라이드', 'addSlide', 'insertpage') + toolBtn('복제', 'dupSlide', 'insertpage') + toolBtn('삭제', 'delSlide', '', SVG.del));
   }
  } else if (tab === 'insert') {
   if (kind === 'typing') {
    html = group('삽입', toolBtn('그림', 'image', 'insertgraphic') + toolBtn('표', 'table', 'inserttable') + toolBtn('체크리스트', 'checklist', 'accepttrackedchange') + toolBtn('강조 박스', 'callout', 'insertdraw') + toolBtn('코드 블록', 'code', 'fontdialog') + toolBtn('오늘 날짜', 'date', 'inserttitlefield') + toolBtn('페이지 나누기', 'hr', 'insertpage', '', true) + toolBtn('링크', 'link', '', SVG.link));
   } else {
    html = group('삽입', toolBtn('텍스트 상자', 'addText', 'insertdraw', '', true) + toolBtn('도형', 'addShape', 'starshapes') + toolBtn('그림', 'image', 'insertgraphic') + toolBtn('개체 삭제', 'deleteItem', '', SVG.del));
   }
  } else if (tab === 'layout') {
   html = group('페이지', toolBtn('PDF 내보내기', 'pdf', 'exportdirecttopdf', '', true) + toolBtn('인쇄', 'print', 'print')) +
    group('줄 간격', toolBtn('좁게 (1.4)', 'lh14', 'paragraphdialog') + toolBtn('보통 (1.75)', 'lh175', 'paragraphdialog') + toolBtn('넓게 (2.1)', 'lh21', 'paragraphdialog'));
  } else if (tab === 'data') {
   html = group('정렬', toolBtn('오름차순', 'sortAsc', 'sortascending') + toolBtn('내림차순', 'sortDesc', 'sortdescending')) +
    group('시각화', toolBtn('막대 차트', 'chart', '', SVG.chart, true));
  } else if (tab === 'formula') {
   html = group('함수', toolBtn('자동 합계', 'autosum', 'autosum', '', true) + toolBtn('평균 입력', 'fnAvg', '', SVG.chart) + toolBtn('최대값', 'fnMax', '', SVG.chart));
  } else if (tab === 'design') {
   html = group('테마', toolBtn('라이트', 'themeLight', 'color') + toolBtn('다크 슬레이트', 'themeSlate', 'color', '', true) + toolBtn('웜 샌드', 'themeWarm', 'color') + toolBtn('민트', 'themeMint', 'color')) +
    group('전환', toolBtn('페이드', 'transFade', '', SVG.trans) + toolBtn('밀기', 'transPush', '', SVG.trans) + toolBtn('닦아내기', 'transWipe', '', SVG.trans));
  } else if (tab === 'show') {
   html = group('발표', toolBtn('처음부터', 'showStart', 'presentation', '', true) + toolBtn('현재 슬라이드', 'showCurrent', 'presentation', '', true) + toolBtn('PDF 내보내기', 'pdf', 'exportdirecttopdf', '', true));
  } else if (tab === 'view') {
   html = group('보기', toolBtn('찾기', 'find', 'searchdialog') + toolBtn('PDF 내보내기', 'pdf', 'exportdirecttopdf', '', true) + toolBtn('인쇄', 'print', 'print'));
  }
  const rb = $('ribbon');
  rb.classList.remove('ws-anim-ribbon');
  void rb.offsetWidth;
  rb.classList.add('ws-anim-ribbon');
  rb.innerHTML = html;
  rb.scrollLeft = 0;
  if (window.PillNav) requestAnimationFrame(() => window.PillNav.refresh());
 }

 function renderInspector() {
  const kind = info.kind;
  let html = `<h3>서식 · 도구</h3>`;
  html += `<section class="inspector-section ai-box" id="ai-panel" hidden>
   <h4>✦ AI 도우미 <span class="npu-badge">${esc(info.npu || '로컬 AI')}</span></h4>
   <div class="ai-actions">
    <button class="inspector-action" data-ai="summarize">문서 핵심 요약</button>
    <button class="inspector-action" data-ai="bullets">글머리 기호 정리</button>
    <button class="inspector-action" data-ai="polish">문장 다듬기</button>
    <button class="inspector-action" data-ai="translate">한 ↔ 영 번역</button>
   </div>
   <div id="ai-output" class="ai-output" hidden></div>
   <button class="inspector-action ai-insert" id="ai-insert-btn" hidden>결과를 문서에 삽입</button>
  </section>`;
  html += `<section class="inspector-section"><h4>텍스트</h4><div class="field"><span>글자 색</span><input type="color" id="insp-color" value="#1d2430"></div><button class="inspector-action" data-action="bold">굵게 (Ctrl+B)</button><button class="inspector-action" data-action="italic">기울임 (Ctrl+I)</button>${kind === 'typing' ? '<button class="inspector-action" data-action="hilite">형광펜 강조</button><button class="inspector-action" data-action="strike">취소선</button>' : ''}</section>`;
  if (kind === 'typing') {
   html += `<section class="inspector-section"><h4>문서 개요 (목차)</h4><div id="doc-outline" class="doc-outline"><span class="hint">제목 1~3을 넣으면 개요가 자동 생성됩니다.</span></div></section>`;
   html += `<section class="inspector-section"><h4>한컴 특수문자</h4><div class="sym-grid">${['※','·','★','☆','■','□','▶','→','✓','℃','₩','©'].map(s => `<button class="sym-btn" data-symbol="${s}">${s}</button>`).join('')}</div></section>`;
   html += `<h4>문단 스타일</h4><button class="inspector-action" data-action="h1">제목 1 (큰 제목)</button><button class="inspector-action" data-action="h2">제목 2 (소제목)</button><button class="inspector-action" data-action="p">본문</button><button class="inspector-action" data-action="table">표 삽입 (3×3)</button>`;
  } else if (kind === 'graphs') {
   html += `<h4>셀과 데이터</h4><div class="field"><span>셀 배경</span><input type="color" id="insp-bg" value="#edf4f0"></div><button class="inspector-action" data-action="currency">통화 서식 (₩)</button><button class="inspector-action" data-action="percent">백분율 (%)</button><button class="inspector-action" data-action="autosum">자동 합계 (=SUM)</button><button class="inspector-action" data-action="chart">막대 차트 보기</button><p class="hint">수식은 =SUM(A1:A5), =AVERAGE(B1:B5), =C2-D2처럼 입력하세요.</p>`;
  } else if (kind === 'present') {
   html += `<h4>슬라이드 전환</h4><label class="field"><span>효과</span><select id="transition-effect"><option value="fade">페이드</option><option value="push">밀기</option><option value="wipe">닦아내기</option><option value="none">없음</option></select></label><button class="inspector-action" data-action="showCurrent">현재 슬라이드부터 발표</button><h4>개체 추가</h4><button class="inspector-action" data-action="addText">텍스트 상자 추가</button><button class="inspector-action" data-action="addShape">도형 추가</button><button class="inspector-action" data-action="image">그림 삽입</button><button class="inspector-action" data-action="deleteItem">선택 개체 삭제</button>`;
  }
  $('inspector').innerHTML = html;
 }

 function mountEditor() {
  $('start').hidden = true;
  const ed = $('editor');
  ed.hidden = false;
  ed.classList.remove('ws-anim-page');
  void ed.offsetWidth;
  ed.classList.add('ws-anim-page');
  $('formula-bar').hidden = info.kind !== 'graphs';
  renderTabs();
  renderRibbon();
  renderInspector();
  const vp = $('viewport');
  const onChange = state => {
   if (state.dirty) {
    docModified = true;
    updateHeader();
    if (autoSaveEnabled && docPath) {
     clearTimeout(autoSaveTimer);
     autoSaveTimer = setTimeout(() => { if (editor && docModified && docPath) saveDoc(false, true); }, 4000);
    }
   }
   if (state.stats) $('document-stats').textContent = state.stats;
   if (state.headings && $('doc-outline')) {
    $('doc-outline').innerHTML = state.headings.length
     ? state.headings.map(h => `<button class="outline-item lv-${h.tag}" data-outline-idx="${h.index}">${esc(h.text)}</button>`).join('')
     : '<span class="hint">제목 1~3을 넣으면 개요가 자동 생성됩니다.</span>';
   }
   if (state.cell !== undefined) $('cell-address').value = state.cell;
   if (state.formula !== undefined && document.activeElement !== $('cell-formula')) $('cell-formula').value = state.formula;
   if (state.transition && $('transition-effect')) $('transition-effect').value = state.transition;
  };
  const { TypingEditor, GraphsEditor, PresentEditor } = window.WorkspaceEditors;
  editor = info.kind === 'graphs' ? new GraphsEditor(vp, onChange) : info.kind === 'present' ? new PresentEditor(vp, onChange) : new TypingEditor(vp, onChange);
 }

 function openNew(template = 'blank') {
  docPath = '';
  docTitle = '제목 없음';
  docModified = false;
  docImported = false;
  mountEditor();
  editor.load(null, template);
  updateHeader();
 }

 async function openFile(path) {
  try {
   const res = path ? await call('readFile', { path }) : await call('openDialog');
   if (!res || res.launched) return;
   docPath = res.path || '';
   docTitle = res.title || '제목 없음';
   docModified = false;
   docImported = !!res.imported;
   mountEditor();
   editor.load(res, 'blank');
   updateHeader();
  } catch (err) {
   toast(err.message);
  }
 }

 async function saveDoc(asNew = false, silent = false) {
  if (!editor) return false;
  try {
   const res = await call('saveFile', { path: docPath, as: asNew, imported: docImported, suggested: docTitle.replace(/\.[^.]+$/, ''), doc: editor.serialize() });
   if (!res) return false;
   docPath = res.path;
   docTitle = res.title;
   docModified = false;
   docImported = false;
   updateHeader();
   if (!silent) toast('저장했습니다.');
   return true;
  } catch (err) {
   if (!silent) toast(err.message);
   return false;
  }
 }

 function startSlideshow(startIdx = 0) {
  if (!editor || info.kind !== 'present') return;
  showIndex = Math.max(0, Math.min(startIdx, editor.slides.length - 1));
  $('slideshow').hidden = false;
  renderSlideShowFrame();
 }

 function renderSlideShowFrame() {
  const s = editor.slides[showIndex];
  if (!s) return;
  const stage = $('slideshow-stage');
  stage.className = 'trans-' + (s.transition || 'fade');
  stage.style.background = s.bg || '#ffffff';
  stage.innerHTML = s.items.map(it => {
   const st = `left:${it.x}%;top:${it.y}%;width:${it.w}%;height:${it.h}%;`;
   if (it.type === 'image') return `<div class="ss-item" style="${st}"><img src="${it.src}" alt=""></div>`;
   if (it.type === 'shape') return `<div class="ss-item ss-shape" style="${st}background:${esc(it.fill || '#e6eefb')};font-size:${it.size || 20}px;color:${esc(it.color || '#1d2430')};text-align:${it.align || 'center'};font-weight:${it.bold ? 700 : 400}">${esc(it.text || '')}</div>`;
   return `<div class="ss-item" style="${st}font-size:${it.size || 22}px;color:${esc(it.color || '#1d2430')};text-align:${it.align || 'left'};font-weight:${it.bold ? 700 : 400};font-style:${it.italic ? 'italic' : 'normal'}">${esc(it.text || '').replace(/\n/g, '<br>')}</div>`;
  }).join('');
  $('slideshow-counter').textContent = `${showIndex + 1} / ${editor.slides.length}`;
 }

 async function handleAction(action) {
  if (action === 'home') {
   if (docModified && !(await call('confirmDiscard'))) return;
   editor = null; docPath = ''; docModified = false;
   info = await call('bootstrap');
   renderStart();
   return;
  }
  if (action === 'new') {
   if (docModified && !(await call('confirmDiscard'))) return;
   openNew('blank'); return;
  }
  if (action === 'open') { await openFile(null); return; }
  if (action === 'save') { await saveDoc(false); return; }
  if (action === 'saveAs') { await saveDoc(true); return; }
  if (action === 'pdf') {
   if (!editor) return;
   try { const r = await call('pdf', { title: docTitle }); if (r) toast('PDF로 내보냈습니다.'); } catch (e) { toast(e.message); }
   return;
  }
  if (action === 'print') { window.print(); return; }
  if (action === 'find') {
   $('find-bar').hidden = !$('find-bar').hidden;
   if (!$('find-bar').hidden) $('find-input').focus();
   return;
  }
  if (action === 'focus') {
   const on = !document.body.classList.contains('focus-mode');
   document.body.classList.toggle('focus-mode', on);
   if ($('exit-focus')) $('exit-focus').hidden = !on;
   return;
  }
  if (action === 'share') {
   if (!editor) return;
   const ser = editor.serialize();
   const txt = ser.text || (ser.slides ? ser.slides.map((s, idx) => `[슬라이드 ${idx + 1}]\n` + s.items.map(i => i.text || '').join('\n')).join('\n\n') : JSON.stringify(ser.sheets || '', null, 2));
   await call('shareText', { title: docTitle, text: txt }).catch(() => {});
   return;
  }
  if (action === 'recover') {
   const rec = await call('loadRecovery');
   if (rec && rec.doc) {
    docPath = rec.path || ''; docTitle = rec.title || '복구된 문서'; docModified = true;
    mountEditor(); editor.load(rec.doc, 'blank'); updateHeader();
    await call('clearRecovery');
   }
   return;
  }
  if (!editor) return;
  if (action === 'image') {
   try { const img = await call('pickImage'); if (img?.dataUrl) editor.exec('image', img.dataUrl); } catch (e) { toast(e.message); }
   return;
  }
  if (action === 'showStart') { startSlideshow(0); return; }
  if (action === 'showCurrent') { startSlideshow(editor.active); return; }
  const map = {
   undo: ['undo'], redo: ['redo'], bold: ['bold'], italic: ['italic'], underline: ['underline'],
   alignLeft: info.kind === 'typing' ? ['justifyLeft'] : ['align', 'left'],
   alignCenter: info.kind === 'typing' ? ['justifyCenter'] : ['align', 'center'],
   alignRight: info.kind === 'typing' ? ['justifyRight'] : ['align', 'right'],
   bullet: ['insertUnorderedList'], number: ['insertOrderedList'], link: ['link'],
   h1: ['h1'], h2: ['h2'], p: ['p'], table: ['table'], hr: ['hr'], hilite: ['hilite'], strike: ['strike'],
   checklist: ['checklist'], callout: ['callout'], code: ['code'], date: ['date'],
   lh14: ['lineHeight', 1.4], lh175: ['lineHeight', 1.75], lh21: ['lineHeight', 2.1],
   currency: ['currency'], percent: ['percent'], autosum: ['autosum'],
   sortAsc: ['sortAsc'], sortDesc: ['sortDesc'], chart: ['chart'],
   addSlide: ['addSlide'], dupSlide: ['dupSlide'], delSlide: ['delSlide'],
   addText: ['addText'], addShape: ['addShape'], deleteItem: ['deleteItem'],
   themeLight: ['theme', 'light'], themeSlate: ['theme', 'slate'], themeWarm: ['theme', 'warm'], themeMint: ['theme', 'mint'],
   transFade: ['transition', { effect: 'fade', duration: 0.5 }],
   transPush: ['transition', { effect: 'push', duration: 0.5 }],
   transWipe: ['transition', { effect: 'wipe', duration: 0.5 }]
  };
  if (action === 'fnAvg' && info.kind === 'graphs') { $('cell-formula').value = '=AVERAGE(A1:A5)'; $('cell-formula').focus(); return; }
  if (action === 'fnMax' && info.kind === 'graphs') { $('cell-formula').value = '=MAX(A1:A5)'; $('cell-formula').focus(); return; }
  if (map[action]) editor.exec(...map[action]);
 }

 document.addEventListener('click', async e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  if (btn.dataset.host) { await call(btn.dataset.host).catch(err => toast(err.message)); return; }
  if (btn.dataset.action) { await handleAction(btn.dataset.action); return; }
  if (btn.dataset.launch) { await call('launch', { kind: btn.dataset.launch }).catch(err => toast(err.message)); return; }
  if (btn.dataset.recent) { await openFile(btn.dataset.recent); return; }
  if (btn.dataset.pinRecent) {
   const res = await call('pinRecent', { path: btn.dataset.pinRecent }).catch(() => null);
   if (res && res.recent) { info.recent = res.recent; renderStart(startSection); toast(res.pinned ? '상단에 고정했습니다.' : '고정을 해제했습니다.'); }
   return;
  }
  if (btn.dataset.delRecent) {
   const res = await call('deleteRecent', { path: btn.dataset.delRecent }).catch(() => null);
   if (res && res.recent) { info.recent = res.recent; renderStart(startSection); toast('목록에서 삭제했습니다.'); }
   return;
  }
  if (btn.id === 'exit-focus') { document.body.classList.remove('focus-mode'); btn.hidden = true; return; }
  if (btn.id === 'start-search-fab') {
   $('start-search-bar').hidden = false;
   $('start-search-fab').hidden = true;
   $('start-search-input').focus();
   return;
  }
  if (btn.id === 'start-search-close') {
   startQuery = '';
   $('start-search-input').value = '';
   $('start-search-bar').hidden = true;
   $('start-search-fab').hidden = false;
   renderStart(startSection);
   return;
  }
  if (btn.id === 'open-settings') {
   syncSettingsUi();
   $('settings-dialog').showModal();
   return;
  }
  if (btn.id === 'close-settings') { $('settings-dialog').close(); return; }
  if (btn.dataset.themeVal) {
   uiPref.theme = btn.dataset.themeVal;
   window.__applyWorkspaceAppearance && window.__applyWorkspaceAppearance(uiPref);
   call('setPref', { theme: uiPref.theme }).catch(() => {});
   syncSettingsUi();
   return;
  }
  if (btn.dataset.styleVal) {
   uiPref.glassStyle = btn.dataset.styleVal;
   window.__applyWorkspaceAppearance && window.__applyWorkspaceAppearance(uiPref);
   call('setPref', { glassStyle: uiPref.glassStyle }).catch(() => {});
   syncSettingsUi();
   return;
  }
  if (btn.dataset.template) { openNew(btn.dataset.template); return; }
  if (btn.dataset.tab) { tab = btn.dataset.tab; renderTabs(); renderRibbon(); return; }
  if (btn.dataset.start) { if (btn.dataset.start === 'open') await openFile(null); else renderStart(btn.dataset.start); return; }
  if (btn.id === 'toggle-inspector') { inspectorShown = !inspectorShown; $('inspector').hidden = !inspectorShown; }
  if (btn.id === 'toggle-ai') {
   inspectorShown = true; $('inspector').hidden = false;
   const ap = $('ai-panel'); if (ap) ap.hidden = !ap.hidden;
  }
  if (btn.dataset.symbol && editor?.exec) { editor.exec('symbol', btn.dataset.symbol); return; }
  if (btn.dataset.outlineIdx !== undefined && editor?.scrollToHeading) { editor.scrollToHeading(+btn.dataset.outlineIdx); return; }
  if (btn.dataset.ai && editor) {
   const out = $('ai-output'), ins = $('ai-insert-btn');
   const ser = editor.serialize();
   const srcText = ser.text || (ser.slides ? ser.slides.map(s => s.items.map(i => i.text).join('\n')).join('\n\n') : JSON.stringify(ser.sheets || ''));
   out.hidden = false; out.textContent = 'AI 분석 중…'; ins.hidden = true;
   try {
    const res = await call('aiAssist', { task: btn.dataset.ai, text: srcText });
    out.innerHTML = `<div class="ai-eng">${esc(res.engine || '')}</div><pre>${esc(res.result || '')}</pre>`;
    ins.dataset.text = res.result || '';
    ins.hidden = !res.result;
   } catch (err) { out.textContent = err.message; }
   return;
  }
  if (btn.id === 'ai-insert-btn' && editor) {
   const txt = btn.dataset.text || '';
   if (editor.insertTextBlock) editor.insertTextBlock(txt);
   else if (info.kind === 'present') { editor.exec('addText'); const it = editor.selectedItem; if (it) { it.text = txt; editor.render(); } }
   toast('AI 결과를 문서에 삽입했습니다.');
   return;
  }
  if (btn.id === 'about') $('about-dialog').showModal();
  if (btn.id === 'close-about') $('about-dialog').close();
  if (btn.id === 'find-close') $('find-bar').hidden = true;
  if (btn.id === 'find-next' && editor?.find) { if (!editor.find($('find-input').value)) toast('일치하는 항목이 없습니다.'); }
  if (btn.id === 'replace-all' && editor?.replaceAll) { const n = editor.replaceAll($('find-input').value, $('replace-input').value); toast(`${n}개 항목을 바꿨습니다.`); }
  if (btn.id === 'slideshow-prev') { showIndex = Math.max(0, showIndex - 1); renderSlideShowFrame(); }
  if (btn.id === 'slideshow-next') { showIndex = Math.min(editor.slides.length - 1, showIndex + 1); renderSlideShowFrame(); }
  if (btn.id === 'slideshow-exit') $('slideshow').hidden = true;
 });

 document.addEventListener('change', e => {
  if (!editor) return;
  if (e.target.id === 'font-name') editor.exec('fontName', e.target.value);
  if (e.target.id === 'font-size') editor.exec('fontSizePt', Number(e.target.value));
  if (e.target.id === 'font-color' || e.target.id === 'insp-color') editor.exec(info.kind === 'typing' ? 'foreColor' : 'color', e.target.value);
  if (e.target.id === 'insp-bg') editor.exec('bg', e.target.value);
  if (e.target.id === 'transition-effect') editor.exec('transition', { effect: e.target.value, duration: 0.5 });
  if (e.target.id === 'zoom') { const z = Number(e.target.value); $('zoom-label').value = z + '%'; editor.setZoom(z); }
 });

 $('cell-formula').addEventListener('keydown', e => {
  if (!editor || info.kind !== 'graphs') return;
  if (e.key === 'Enter') { e.preventDefault(); editor.setCellValue(editor.selAddr, e.target.value); }
  if (e.key === 'Escape') { e.target.value = editor.sheet.cells[editor.selAddr]?.v || ''; }
 });

 document.addEventListener('keydown', e => {
  if (!$('slideshow').hidden) {
   if (e.key === 'Escape') $('slideshow').hidden = true;
   else if (e.key === 'ArrowRight' || e.key === ' ' || e.key === 'Enter') { showIndex = Math.min(editor.slides.length - 1, showIndex + 1); renderSlideShowFrame(); }
   else if (e.key === 'ArrowLeft') { showIndex = Math.max(0, showIndex - 1); renderSlideShowFrame(); }
   return;
  }
  if (!e.ctrlKey) return;
  const k = e.key.toLowerCase();
  if (k === 's') { e.preventDefault(); saveDoc(e.shiftKey); }
  else if (k === 'o') { e.preventDefault(); openFile(null); }
  else if (k === 'n' && info?.kind !== 'workspace') { e.preventDefault(); handleAction('new'); }
  else if (k === 'p' && editor) { e.preventDefault(); window.print(); }
  else if (k === 'f' && editor) { e.preventDefault(); handleAction('find'); }
 });

 $('titlebar').addEventListener('mousedown', e => { if (e.button === 0 && !e.target.closest('button')) call('drag').catch(() => {}); });
 $('titlebar').addEventListener('dblclick', e => { if (!e.target.closest('button')) call('maximize').catch(() => {}); });

 window.Workspace = { call, openNew, openFile, saveDoc, get info() { return info; }, get editor() { return editor; } };

 function syncSettingsUi() {
  document.querySelectorAll('[data-theme-val]').forEach(b => b.classList.toggle('active', b.dataset.themeVal === uiPref.theme));
  document.querySelectorAll('[data-style-val]').forEach(b => b.classList.toggle('active', b.dataset.styleVal === uiPref.glassStyle));
  const sl = $('set-glass-t'), vl = $('set-glass-val'), as = $('set-autosave');
  if (sl) sl.value = String(Math.round((uiPref.glassT ?? 0.55) * 100));
  if (vl) vl.textContent = Math.round((uiPref.glassT ?? 0.55) * 100) + '%';
  if (as) as.checked = !!uiPref.autoSave;
 }

 if ($('start-search-input')) {
  $('start-search-input').addEventListener('input', e => {
   startQuery = e.target.value || '';
   renderStart(startSection);
  });
 }
 if ($('set-glass-t')) {
  $('set-glass-t').addEventListener('input', e => {
   uiPref.glassT = Number(e.target.value) / 100;
   if ($('set-glass-val')) $('set-glass-val').textContent = e.target.value + '%';
   window.__applyWorkspaceAppearance && window.__applyWorkspaceAppearance(uiPref);
   call('setPref', { glassT: uiPref.glassT }).catch(() => {});
  });
 }
 if ($('set-autosave')) {
  $('set-autosave').addEventListener('change', e => {
   uiPref.autoSave = !!e.target.checked;
   autoSaveEnabled = uiPref.autoSave;
   call('setPref', { autoSave: uiPref.autoSave }).catch(() => {});
  });
 }

 (async () => {
  try {
   info = await call('bootstrap');
   const loadedPref = await call('getPrefs').catch(() => null);
   if (loadedPref) {
    uiPref = { ...uiPref, ...loadedPref };
    autoSaveEnabled = uiPref.autoSave !== false;
    window.__applyWorkspaceAppearance && window.__applyWorkspaceAppearance(uiPref);
   }
   const app = APPS[info.kind] || APPS.workspace;
   document.title = info.name;
   $('app-name').textContent = info.name;
   $('about-name').textContent = info.name;
   $('app-icon').src = $('about-icon').src = $('brand-icon').src = `assets/${info.kind}.png`;
   $('brand-name').textContent = info.name;
   $('brand-desc').textContent = app.subtitle;
   document.documentElement.style.setProperty('--accent', app.color);
   document.documentElement.style.setProperty('--soft', app.soft);
   renderStart();
   document.body.classList.remove('booting');
   setInterval(() => {
    if (editor && docModified && !document.hidden) {
     call('saveRecovery', { path: docPath, title: docTitle, doc: editor.serialize() }).then(() => {
      $('recovery-state').textContent = '복구 사본 저장됨';
     }).catch(() => {});
    }
   }, 60000);
  } catch (err) {
   document.body.classList.remove('booting');
   toast(err.message);
  }
 })();
})();
