(() => {
 'use strict';

 const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

 // ==================== 1. TYPING EDITOR ====================
 class TypingEditor {
  constructor(mount, onChange) {
   this.mount = mount;
   this.onChange = onChange;
   this.zoom = 100;
   this.lineHeight = 1.75;
   this.mount.innerHTML = `<div class="typing-scroll"><div class="typing-page" contenteditable="true" spellcheck="true"></div></div>`;
   this.page = this.mount.querySelector('.typing-page');
   this.page.addEventListener('input', () => this.notify());
   this.page.addEventListener('keyup', () => this.notifySelection());
   this.page.addEventListener('mouseup', () => this.notifySelection());
  }

  load(data, template) {
   if (data && data.html) {
    this.page.innerHTML = data.html;
   } else if (template === 'report') {
    this.page.innerHTML = `<h1>업무 보고서</h1><p style="color:#6b7280">작성일: ${new Date().toISOString().slice(0, 10)} · 작성자: 홍길동</p><h2>1. 개요</h2><p>이번 분기 핵심 목표와 추진 배경을 간결하게 정리합니다.</p><h2>2. 주요 진행 내용</h2><ul><li>핵심 기능 설계 및 구현 완료</li><li>성능 최적화 및 품질 검증 진행</li></ul><h2>3. 다음 단계</h2><table><tr><th>항목</th><th>담당</th><th>일정</th></tr><tr><td>최종 점검</td><td>개발팀</td><td>이번 주 금요일</td></tr><tr><td>배포 및 안내</td><td>운영팀</td><td>다음 주 월요일</td></tr></table>`;
   } else if (template === 'minutes') {
    this.page.innerHTML = `<h1>회의록</h1><p style="color:#6b7280">일시: ${new Date().toISOString().slice(0, 10)} · 참석자: 팀 전체</p><h2>1. 핵심 안건</h2><ul><li>신규 기능 출시 일정 확정</li><li>사용자 피드백 우선순위 정리</li></ul><h2>2. 결정 사항</h2><div class="ws-callout">주요 개선 항목을 이번 주 내로 반영하고 테스트를 완료합니다.</div><h2>3. 액션 아이템</h2><table><tr><th>할 일</th><th>담당자</th><th>마감일</th></tr><tr><td>UI 최종 점검</td><td>디자인</td><td>수요일</td></tr><tr><td>배포 패키지 검증</td><td>엔지니어링</td><td>금요일</td></tr></table>`;
   } else if (template === 'resume') {
    this.page.innerHTML = `<h1>홍길동 — 이력서</h1><p style="color:#6b7280">이메일: user@example.com · 연락처: 010-0000-0000</p><h2>핵심 역량</h2><ul><li>모바일·웹 프로덕트 설계 및 성능 최적화</li><li>크로스 플랫폼 협업 및 문제 해결</li></ul><h2>주요 경력</h2><table><tr><th>기간</th><th>소속</th><th>역할 및 성과</th></tr><tr><td>2024 ~ 현재</td><td>프로덕트 팀</td><td>핵심 앱 아키텍처 개선 및 사용자 경험 고도화</td></tr></table><h2>학력 및 자격</h2><p>OO대학교 컴퓨터공학과 졸업</p>`;
   } else if (template === 'plan') {
    this.page.innerHTML = `<h1>프로젝트 계획서</h1><p style="color:#6b7280">기준일: ${new Date().toISOString().slice(0, 10)}</p><h2>목표</h2><p>핵심 마일스톤과 완료 기준을 명확히 정의합니다.</p><h2>체크리스트</h2><p>☐ 요구사항 확정 및 일정 수립</p><p>☐ 프로토타입 제작 및 사용성 검토</p><p>☐ 품질 테스트 및 최종 배포</p>`;
   } else {
    this.page.innerHTML = '<p><br></p>';
   }
   this.setZoom(this.zoom);
   this.notify(false);
   setTimeout(() => this.page.focus(), 30);
  }

  serialize() {
   return { kind: 'typing', html: this.page.innerHTML, text: this.page.innerText };
  }

  notify(dirty = true) {
   const text = (this.page.innerText || '').trim();
   const words = text ? text.split(/\s+/).length : 0;
   const chars = text.replace(/\s/g, '').length;
   const manuscript = Math.max(1, Math.ceil(text.length / 200));
   const pages = Math.max(1, Math.ceil(this.page.scrollHeight / 1040));
   const headings = Array.from(this.page.querySelectorAll('h1,h2,h3')).map((el, i) => ({ tag: el.tagName.toLowerCase(), text: el.innerText.trim(), index: i })).filter(h => h.text);
   this.onChange({ dirty, stats: `${pages}쪽 · ${words}단어 · ${chars}자 (원고지 ${manuscript}매)`, headings });
  }

  scrollToHeading(idx) {
   const h = this.page.querySelectorAll('h1,h2,h3')[idx];
   if (h) h.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  insertTextBlock(text) {
   this.page.focus();
   const html = String(text || '').split('\n').map(l => `<p>${esc(l)}</p>`).join('');
   document.execCommand('insertHTML', false, html);
   this.notify(true);
  }

  notifySelection() {
   this.onChange({ dirty: false });
  }

  setZoom(z) {
   this.zoom = z;
   this.page.style.zoom = (z / 100).toString();
  }

  exec(cmd, val = null) {
   this.page.focus();
   if (cmd === 'h1' || cmd === 'h2' || cmd === 'h3' || cmd === 'p') {
    document.execCommand('formatBlock', false, cmd === 'p' ? 'P' : cmd.toUpperCase());
   } else if (cmd === 'table') {
    document.execCommand('insertHTML', false, '<table><tr><th>항목</th><th>내용</th><th>비고</th></tr><tr><td><br></td><td><br></td><td><br></td></tr><tr><td><br></td><td><br></td><td><br></td></tr></table><p><br></p>');
   } else if (cmd === 'hr') {
    document.execCommand('insertHTML', false, '<hr class="page-break"><p><br></p>');
   } else if (cmd === 'checklist') {
    document.execCommand('insertHTML', false, '<p>☐ 할 일을 입력하세요</p>');
   } else if (cmd === 'callout') {
    document.execCommand('insertHTML', false, '<div class="ws-callout">핵심 내용을 강조해 적어보세요.</div><p><br></p>');
   } else if (cmd === 'code') {
    document.execCommand('insertHTML', false, '<pre class="ws-code">// 코드를 입력하세요</pre><p><br></p>');
   } else if (cmd === 'date') {
    const d = new Date();
    document.execCommand('insertText', false, `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일`);
   } else if (cmd === 'symbol' && val) {
    document.execCommand('insertText', false, val);
   } else if (cmd === 'hilite') {
    document.execCommand('hiliteColor', false, val || '#fff3a3');
   } else if (cmd === 'strike') {
    document.execCommand('strikeThrough', false, null);
   } else if (cmd === 'image' && val) {
    document.execCommand('insertHTML', false, `<p><img src="${val}" class="doc-img" alt=""></p>`);
   } else if (cmd === 'link') {
    const url = prompt('연결할 주소를 입력하세요 (https://...)');
    if (url) document.execCommand('createLink', false, url);
   } else if (cmd === 'lineHeight') {
    this.lineHeight = Number(val) || 1.75;
    this.page.style.lineHeight = String(this.lineHeight);
   } else if (cmd === 'fontSizePt') {
    const sel = window.getSelection();
    if (sel && sel.rangeCount && !sel.isCollapsed) {
     document.execCommand('fontSize', false, '7');
     this.page.querySelectorAll('font[size="7"]').forEach(el => {
      const span = document.createElement('span');
      span.style.fontSize = val + 'pt';
      span.innerHTML = el.innerHTML;
      el.replaceWith(span);
     });
    } else {
     this.page.style.fontSize = val + 'pt';
    }
   } else if (cmd === 'fontName') {
    document.execCommand('fontName', false, val);
   } else {
    document.execCommand(cmd, false, val);
   }
   this.notify(true);
  }

  find(query) {
   if (!query) return false;
   this.page.focus();
   return window.find(query, false, false, true);
  }

  replaceAll(query, replacement) {
   if (!query) return 0;
   const walker = document.createTreeWalker(this.page, NodeFilter.SHOW_TEXT);
   let count = 0, node;
   const re = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g');
   while ((node = walker.nextNode())) {
    if (node.nodeValue && node.nodeValue.includes(query)) {
     const parts = node.nodeValue.split(query);
     count += parts.length - 1;
     node.nodeValue = node.nodeValue.replace(re, replacement);
    }
   }
   if (count > 0) this.notify(true);
   return count;
  }
 }

 // ==================== 2. GRAPHS EDITOR ====================
 function colName(idx) {
  let s = '', n = idx;
  while (n >= 0) { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1; }
  return s;
 }
 function parseRef(ref) {
  const m = /^([A-Z]+)(\d+)$/i.exec(ref.trim());
  if (!m) return null;
  let c = 0;
  for (const ch of m[1].toUpperCase()) c = c * 26 + (ch.charCodeAt(0) - 64);
  return { r: parseInt(m[2], 10) - 1, c: c - 1 };
 }

 class GraphsEditor {
  constructor(mount, onChange) {
   this.mount = mount;
   this.onChange = onChange;
   this.rows = 50;
   this.cols = 18;
   this.sheets = [{ name: '시트 1', cells: {} }];
   this.activeSheet = 0;
   this.sel = { r: 0, c: 0 };
   this.editing = false;
   this.history = [];
   this.future = [];
   this.zoom = 100;
   this.mount.innerHTML = `<div class="graphs-wrap"><div class="graphs-grid-wrap"><table class="graphs-table"></table></div><div class="graphs-chart" hidden></div><div class="graphs-tabs"></div></div>`;
   this.table = this.mount.querySelector('.graphs-table');
   this.tabsEl = this.mount.querySelector('.graphs-tabs');
   this.chartEl = this.mount.querySelector('.graphs-chart');
   this.bindEvents();
  }

  get sheet() { return this.sheets[this.activeSheet]; }
  get selAddr() { return colName(this.sel.c) + (this.sel.r + 1); }

  bindEvents() {
   this.table.addEventListener('mousedown', e => {
    const td = e.target.closest('td[data-r]');
    if (!td) return;
    this.selectCell(+td.dataset.r, +td.dataset.c);
   });
   this.table.addEventListener('dblclick', e => {
    const td = e.target.closest('td[data-r]');
    if (!td) return;
    this.startInlineEdit(td);
   });
   this.table.addEventListener('keydown', e => {
    if (this.editing) return;
    const { r, c } = this.sel;
    if (e.key === 'ArrowDown') { e.preventDefault(); this.selectCell(Math.min(this.rows - 1, r + 1), c); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); this.selectCell(Math.max(0, r - 1), c); }
    else if (e.key === 'ArrowRight' || e.key === 'Tab') { e.preventDefault(); this.selectCell(r, Math.min(this.cols - 1, c + 1)); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); this.selectCell(r, Math.max(0, c - 1)); }
    else if (e.key === 'Enter' || e.key === 'F2') { e.preventDefault(); this.startInlineEdit(this.getCellTd(r, c)); }
    else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); this.setCellValue(this.selAddr, ''); }
    else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
     e.preventDefault();
     this.startInlineEdit(this.getCellTd(r, c), e.key);
    }
   });
   this.tabsEl.addEventListener('click', e => {
    const btn = e.target.closest('button');
    if (!btn) return;
    if (btn.dataset.sheet !== undefined) {
     this.activeSheet = +btn.dataset.sheet;
     this.sel = { r: 0, c: 0 };
     this.renderAll();
     this.notify(false);
    } else if (btn.dataset.addSheet) {
     this.sheets.push({ name: '시트 ' + (this.sheets.length + 1), cells: {} });
     this.activeSheet = this.sheets.length - 1;
     this.sel = { r: 0, c: 0 };
     this.renderAll();
     this.notify(true);
    }
   });
  }

  load(data, template) {
   this.history = [];
   this.future = [];
   if (data && Array.isArray(data.sheets) && data.sheets.length) {
    this.sheets = data.sheets.map(s => ({ name: s.name || '시트', cells: s.cells || {} }));
    this.activeSheet = data.active || 0;
   } else if (template === 'budget') {
    this.sheets = [{
     name: '월간 예산',
     cells: {
      A1: { v: '항목', b: true, bg: '#edf4f0' }, B1: { v: '구분', b: true, bg: '#edf4f0' }, C1: { v: '예산', b: true, bg: '#edf4f0' }, D1: { v: '지출', b: true, bg: '#edf4f0' }, E1: { v: '잔액', b: true, bg: '#edf4f0' },
      A2: { v: '주거 · 관리비' }, B2: { v: '고정' }, C2: { v: '850000', fmt: 'currency' }, D2: { v: '850000', fmt: 'currency' }, E2: { v: '=C2-D2', fmt: 'currency' },
      A3: { v: '식비 · 생활' }, B3: { v: '변동' }, C3: { v: '600000', fmt: 'currency' }, D3: { v: '465000', fmt: 'currency' }, E3: { v: '=C3-D3', fmt: 'currency' },
      A4: { v: '교통 · 통신' }, B4: { v: '고정' }, C4: { v: '180000', fmt: 'currency' }, D4: { v: '162000', fmt: 'currency' }, E4: { v: '=C4-D4', fmt: 'currency' },
      A5: { v: '여가 · 구독' }, B5: { v: '변동' }, C5: { v: '200000', fmt: 'currency' }, D5: { v: '124000', fmt: 'currency' }, E5: { v: '=C5-D5', fmt: 'currency' },
      A6: { v: '합계', b: true, bg: '#f4f6f9' }, B6: { v: '', bg: '#f4f6f9' }, C6: { v: '=SUM(C2:C5)', b: true, fmt: 'currency', bg: '#f4f6f9' }, D6: { v: '=SUM(D2:D5)', b: true, fmt: 'currency', bg: '#f4f6f9' }, E6: { v: '=SUM(E2:E5)', b: true, fmt: 'currency', bg: '#f4f6f9' }
     }
    }];
    this.activeSheet = 0;
   } else if (template === 'schedule') {
    this.sheets = [{
     name: '주간 일정표',
     cells: {
      A1: { v: '업무 항목', b: true, bg: '#e6effb' }, B1: { v: '담당자', b: true, bg: '#e6effb' }, C1: { v: '시작일', b: true, bg: '#e6effb' }, D1: { v: '마감일', b: true, bg: '#e6effb' }, E1: { v: '진행률', b: true, bg: '#e6effb' },
      A2: { v: '기획 및 설계' }, B2: { v: '김민수' }, C2: { v: '월요일' }, D2: { v: '화요일' }, E2: { v: '1', fmt: 'percent' },
      A3: { v: '핵심 기능 구현' }, B3: { v: '이지은' }, C3: { v: '수요일' }, D3: { v: '목요일' }, E3: { v: '0.75', fmt: 'percent' },
      A4: { v: '테스트 및 배포' }, B4: { v: '박서준' }, C4: { v: '금요일' }, D4: { v: '금요일' }, E4: { v: '0.3', fmt: 'percent' },
      A5: { v: '평균 진행률', b: true, bg: '#f4f6f9' }, E5: { v: '=AVERAGE(E2:E4)', b: true, fmt: 'percent', bg: '#f4f6f9' }
     }
    }];
    this.activeSheet = 0;
   } else if (template === 'sales') {
    this.sheets = [{
     name: '매출·손익',
     cells: {
      A1: { v: '월', b: true, bg: '#edf4f0' }, B1: { v: '매출', b: true, bg: '#edf4f0' }, C1: { v: '비용', b: true, bg: '#edf4f0' }, D1: { v: '순이익', b: true, bg: '#edf4f0' },
      A2: { v: '1분기' }, B2: { v: '4200000', fmt: 'currency' }, C2: { v: '2600000', fmt: 'currency' }, D2: { v: '=B2-C2', fmt: 'currency' },
      A3: { v: '2분기' }, B3: { v: '5100000', fmt: 'currency' }, C3: { v: '2950000', fmt: 'currency' }, D3: { v: '=B3-C3', fmt: 'currency' },
      A4: { v: '3분기' }, B4: { v: '5850000', fmt: 'currency' }, C4: { v: '3100000', fmt: 'currency' }, D4: { v: '=B4-C4', fmt: 'currency' },
      A5: { v: '합계', b: true, bg: '#f4f6f9' }, B5: { v: '=SUM(B2:B4)', b: true, fmt: 'currency', bg: '#f4f6f9' }, C5: { v: '=SUM(C2:C4)', b: true, fmt: 'currency', bg: '#f4f6f9' }, D5: { v: '=SUM(D2:D4)', b: true, fmt: 'currency', bg: '#f4f6f9' }
     }
    }];
    this.activeSheet = 0;
   } else {
    this.sheets = [{ name: '시트 1', cells: {} }];
    this.activeSheet = 0;
   }
   this.sel = { r: 0, c: 0 };
   this.renderAll();
   this.notify(false);
  }

  serialize() {
   const sheets = this.sheets.map(sh => {
    const out = {};
    for (const [k, cell] of Object.entries(sh.cells)) {
     if (cell && (cell.v !== '' || cell.b || cell.bg)) {
      out[k] = { ...cell, d: this.displayValue(k, sh) };
     }
    }
    return { name: sh.name, cells: out };
   });
   return { kind: 'graphs', sheets, active: this.activeSheet };
  }

  evalFormula(expr, sheet, depth = 0) {
   if (depth > 15) return '#REF!';
   const raw = String(expr || '').trim();
   if (!raw.startsWith('=')) return raw;
   try {
    let body = raw.slice(1).trim();
    const getNum = ref => {
     const c = sheet.cells[ref.toUpperCase()];
     if (!c || c.v === '') return 0;
     const v = String(c.v).startsWith('=') ? this.evalFormula(c.v, sheet, depth + 1) : c.v;
     const n = parseFloat(String(v).replace(/,/g, ''));
     return isNaN(n) ? 0 : n;
    };
    const expandRange = range => {
     const [a, b] = range.split(':');
     const pa = parseRef(a), pb = parseRef(b);
     if (!pa || !pb) return [];
     const vals = [];
     for (let r = Math.min(pa.r, pb.r); r <= Math.max(pa.r, pb.r); r++) {
      for (let c = Math.min(pa.c, pb.c); c <= Math.max(pa.c, pb.c); c++) {
       vals.push(getNum(colName(c) + (r + 1)));
      }
     }
     return vals;
    };
    body = body.replace(/\b(SUM|AVERAGE|AVG|MIN|MAX|COUNT)\s*\(([^)]+)\)/gi, (_, fn, arg) => {
     const nums = [];
     for (const part of arg.split(',')) {
      if (part.includes(':')) nums.push(...expandRange(part.trim()));
      else if (/^[A-Z]+\d+$/i.test(part.trim())) nums.push(getNum(part.trim()));
      else { const n = parseFloat(part); if (!isNaN(n)) nums.push(n); }
     }
     const F = fn.toUpperCase();
     if (F === 'SUM') return nums.reduce((a, b) => a + b, 0);
     if (F === 'AVERAGE' || F === 'AVG') return nums.length ? nums.reduce((a, b) => a + b, 0) / nums.length : 0;
     if (F === 'MIN') return nums.length ? Math.min(...nums) : 0;
     if (F === 'MAX') return nums.length ? Math.max(...nums) : 0;
     if (F === 'COUNT') return nums.length;
     return 0;
    });
    body = body.replace(/\b(ROUND|ABS)\s*\(([^)]+)\)/gi, (_, fn, arg) => {
     const parts = arg.split(',').map(s => parseFloat(this.evalFormula('=' + s.trim(), sheet, depth + 1)));
     if (fn.toUpperCase() === 'ABS') return Math.abs(parts[0] || 0);
     const d = Math.pow(10, parts[1] || 0);
     return Math.round((parts[0] || 0) * d) / d;
    });
    body = body.replace(/\b([A-Z]+\d+)\b/gi, (_, ref) => String(getNum(ref)));
    if (!/^[\d\s+\-*/().^]+$/.test(body)) return '#ERR';
    const val = Function('"use strict"; return (' + body.replace(/\^/g, '**') + ')')();
    return typeof val === 'number' && isFinite(val) ? Math.round(val * 1e9) / 1e9 : '#ERR';
   } catch {
    return '#ERR';
   }
  }

  displayValue(addr, sheet = this.sheet) {
   const cell = sheet.cells[addr];
   if (!cell || cell.v === undefined || cell.v === '') return '';
   const val = String(cell.v).startsWith('=') ? this.evalFormula(cell.v, sheet) : cell.v;
   const num = Number(val);
   if (!isNaN(num) && val !== '') {
    if (cell.fmt === 'currency') return '₩' + Math.round(num).toLocaleString('ko-KR');
    if (cell.fmt === 'percent') return (num * 100).toFixed(1) + '%';
    if (String(cell.v).startsWith('=')) return num.toLocaleString('ko-KR');
   }
   return String(val);
  }

  renderAll() {
   let html = '<thead><tr><th class="corner"></th>';
   for (let c = 0; c < this.cols; c++) html += `<th>${colName(c)}</th>`;
   html += '</tr></thead><tbody>';
   for (let r = 0; r < this.rows; r++) {
    html += `<tr><th class="row-h">${r + 1}</th>`;
    for (let c = 0; c < this.cols; c++) {
     const addr = colName(c) + (r + 1);
     const cell = this.sheet.cells[addr] || {};
     const disp = this.displayValue(addr);
     const cls = [r === this.sel.r && c === this.sel.c ? 'sel' : '', cell.b ? 'b' : '', cell.i ? 'i' : '', cell.align ? 'al-' + cell.align : ''].filter(Boolean).join(' ');
     const style = [cell.color ? `color:${cell.color}` : '', cell.bg ? `background:${cell.bg}` : ''].filter(Boolean).join(';');
     html += `<td data-r="${r}" data-c="${c}" tabindex="-1" class="${cls}"${style ? ` style="${style}"` : ''}>${esc(disp)}</td>`;
    }
    html += '</tr>';
   }
   html += '</tbody>';
   this.table.innerHTML = html;
   this.tabsEl.innerHTML = this.sheets.map((s, i) => `<button class="sheet-tab ${i === this.activeSheet ? 'active' : ''}" data-sheet="${i}">${esc(s.name)}</button>`).join('') + `<button class="sheet-add" data-add-sheet="1" title="시트 추가">＋</button>`;
   this.setZoom(this.zoom);
  }

  getCellTd(r, c) { return this.table.querySelector(`td[data-r="${r}"][data-c="${c}"]`); }

  selectCell(r, c) {
   const prev = this.table.querySelector('td.sel');
   if (prev) prev.classList.remove('sel');
   this.sel = { r, c };
   const td = this.getCellTd(r, c);
   if (td) { td.classList.add('sel'); td.focus({ preventScroll: false }); }
   this.notify(false);
  }

  startInlineEdit(td, initialChar = null) {
   if (!td || this.editing) return;
   this.editing = true;
   const addr = colName(+td.dataset.c) + (+td.dataset.r + 1);
   const raw = initialChar !== null ? initialChar : (this.sheet.cells[addr]?.v || '');
   const input = document.createElement('input');
   input.className = 'cell-editor';
   input.value = raw;
   td.textContent = '';
   td.appendChild(input);
   input.focus();
   const commit = moveDown => {
    if (!this.editing) return;
    this.editing = false;
    this.setCellValue(addr, input.value);
    if (moveDown) this.selectCell(Math.min(this.rows - 1, this.sel.r + 1), this.sel.c);
   };
   input.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); commit(true); }
    else if (e.key === 'Escape') { this.editing = false; this.renderAll(); this.selectCell(this.sel.r, this.sel.c); }
    else if (e.key === 'Tab') { e.preventDefault(); commit(false); this.selectCell(this.sel.r, Math.min(this.cols - 1, this.sel.c + 1)); }
   });
   input.addEventListener('blur', () => commit(false));
  }

  pushHistory() {
   this.history.push(JSON.stringify(this.sheets));
   if (this.history.length > 40) this.history.shift();
   this.future = [];
  }

  setCellValue(addr, value) {
   this.pushHistory();
   const prev = this.sheet.cells[addr] || {};
   if (value === '' && !prev.b && !prev.bg && !prev.fmt) delete this.sheet.cells[addr];
   else this.sheet.cells[addr] = { ...prev, v: value };
   this.renderAll();
   this.notify(true);
  }

  exec(cmd, val) {
   const addr = this.selAddr;
   const cell = this.sheet.cells[addr] || { v: '' };
   if (cmd === 'undo' && this.history.length) {
    this.future.push(JSON.stringify(this.sheets));
    this.sheets = JSON.parse(this.history.pop());
    this.renderAll(); this.notify(true); return;
   }
   if (cmd === 'redo' && this.future.length) {
    this.history.push(JSON.stringify(this.sheets));
    this.sheets = JSON.parse(this.future.pop());
    this.renderAll(); this.notify(true); return;
   }
   if (cmd === 'autosum') {
    const r = this.sel.r;
    if (r > 0) {
     const col = colName(this.sel.c);
     this.setCellValue(addr, `=SUM(${col}1:${col}${r})`);
    }
    return;
   }
   if (cmd === 'sortAsc' || cmd === 'sortDesc') {
    this.pushHistory();
    const c = this.sel.c;
    const rowsData = [];
    for (let r = 1; r < this.rows; r++) {
     const rowObj = {};
     let has = false;
     for (let k = 0; k < this.cols; k++) {
      const a = colName(k) + (r + 1);
      if (this.sheet.cells[a]) { rowObj[k] = this.sheet.cells[a]; has = true; }
     }
     if (has) rowsData.push(rowObj);
    }
    rowsData.sort((a, b) => {
     const va = a[c]?.v || '', vb = b[c]?.v || '';
     const na = parseFloat(va), nb = parseFloat(vb);
     const cmp = (!isNaN(na) && !isNaN(nb)) ? na - nb : String(va).localeCompare(String(vb), 'ko');
     return cmd === 'sortAsc' ? cmp : -cmp;
    });
    for (let r = 1; r < this.rows; r++) for (let k = 0; k < this.cols; k++) delete this.sheet.cells[colName(k) + (r + 1)];
    rowsData.forEach((rowObj, idx) => {
     for (const [k, obj] of Object.entries(rowObj)) this.sheet.cells[colName(+k) + (idx + 2)] = obj;
    });
    this.renderAll(); this.notify(true); return;
   }
   if (cmd === 'chart') {
    this.toggleChart(); return;
   }
   this.pushHistory();
   if (cmd === 'bold') cell.b = !cell.b;
   else if (cmd === 'italic') cell.i = !cell.i;
   else if (cmd === 'currency') cell.fmt = cell.fmt === 'currency' ? '' : 'currency';
   else if (cmd === 'percent') cell.fmt = cell.fmt === 'percent' ? '' : 'percent';
   else if (cmd === 'align') cell.align = val;
   else if (cmd === 'color') cell.color = val;
   else if (cmd === 'bg') cell.bg = val;
   this.sheet.cells[addr] = cell;
   this.renderAll();
   this.notify(true);
  }

  toggleChart() {
   if (!this.chartEl.hidden) { this.chartEl.hidden = true; return; }
   const items = [];
   for (let r = 1; r < 25; r++) {
    const label = this.displayValue('A' + (r + 1));
    const rawVal = this.evalFormula(this.sheet.cells[colName(this.sel.c) + (r + 1)]?.v || '', this.sheet);
    const num = parseFloat(String(rawVal).replace(/,/g, ''));
    if (label && !isNaN(num)) items.push({ label, num });
   }
   if (!items.length) {
    this.chartEl.innerHTML = `<div class="chart-head"><b>차트 미리보기</b><button class="chart-close">닫기</button></div><p class="empty">A열에 항목 이름, 선택한 열에 숫자가 있으면 막대 차트를 그려줍니다.</p>`;
   } else {
    const max = Math.max(...items.map(i => Math.abs(i.num)), 1);
    this.chartEl.innerHTML = `<div class="chart-head"><b>${colName(this.sel.c)}열 막대 차트</b><button class="chart-close">닫기</button></div><div class="chart-bars">${items.map(i => `<div class="chart-row"><span>${esc(i.label)}</span><div class="bar-track"><div class="bar-fill" style="width:${Math.max(4, Math.abs(i.num) / max * 100)}%"></div></div><em>${i.num.toLocaleString('ko-KR')}</em></div>`).join('')}</div>`;
   }
   this.chartEl.hidden = false;
   this.chartEl.querySelector('.chart-close').onclick = () => { this.chartEl.hidden = true; };
  }

  setZoom(z) {
   this.zoom = z;
   this.table.style.zoom = (z / 100).toString();
  }

  notify(dirty = true) {
   const addr = this.selAddr;
   const formula = this.sheet.cells[addr]?.v || '';
   this.onChange({ dirty, stats: `${this.sheet.name} · ${addr}`, cell: addr, formula });
  }
 }

 // ==================== 3. PRESENT EDITOR ====================
 const THEMES = {
  light: { bg: '#ffffff', text: '#1d2430', accent: '#3b6fd4' },
  slate: { bg: '#181f2c', text: '#f4f7fb', accent: '#7aa7ff' },
  warm: { bg: '#fbf6ef', text: '#2e241c', accent: '#d87b63' },
  mint: { bg: '#eef7f3', text: '#1c352d', accent: '#269b78' }
 };

 class PresentEditor {
  constructor(mount, onChange) {
   this.mount = mount;
   this.onChange = onChange;
   this.slides = [];
   this.active = 0;
   this.selectedId = null;
   this.zoom = 100;
   this.mount.innerHTML = `<div class="present-wrap"><aside class="slide-list"></aside><div class="present-main"><div class="slide-stage-wrap"><div class="slide-canvas"></div></div><textarea class="slide-notes" placeholder="발표자 노트를 입력하세요..."></textarea></div></div>`;
   this.listEl = this.mount.querySelector('.slide-list');
   this.canvasEl = this.mount.querySelector('.slide-canvas');
   this.notesEl = this.mount.querySelector('.slide-notes');
   this.bindEvents();
  }

  get slide() { return this.slides[this.active]; }
  get selectedItem() { return this.slide?.items.find(i => i.id === this.selectedId) || null; }

  bindEvents() {
   this.listEl.addEventListener('click', e => {
    const btn = e.target.closest('[data-slide-idx]');
    if (!btn) return;
    this.active = +btn.dataset.slideIdx;
    this.selectedId = null;
    this.render();
    this.notify(false);
   });
   this.notesEl.addEventListener('input', () => {
    if (this.slide) { this.slide.notes = this.notesEl.value; this.notify(true); }
   });
   this.canvasEl.addEventListener('mousedown', e => {
    const box = e.target.closest('.slide-item');
    if (!box) { this.selectedId = null; this.renderCanvas(); return; }
    this.selectedId = box.dataset.id;
    const item = this.selectedItem;
    if (!item) return;
    if (e.target.classList.contains('resize-handle')) {
     e.preventDefault();
     const rect = this.canvasEl.getBoundingClientRect();
     const sx = e.clientX, sy = e.clientY, sw = item.w, sh = item.h;
     const move = ev => {
      item.w = Math.max(10, Math.min(100 - item.x, sw + (ev.clientX - sx) / rect.width * 100));
      item.h = Math.max(8, Math.min(100 - item.y, sh + (ev.clientY - sy) / rect.height * 100));
      box.style.width = item.w + '%'; box.style.height = item.h + '%';
     };
     const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); this.renderList(); this.notify(true); };
     window.addEventListener('mousemove', move); window.addEventListener('mouseup', up);
     return;
    }
    if (e.target.closest('.item-body') && document.activeElement === e.target.closest('.item-body')) return;
    this.renderCanvas();
    const rect = this.canvasEl.getBoundingClientRect();
    const sx = e.clientX, sy = e.clientY, ox = item.x, oy = item.y;
    let moved = false;
    const move = ev => {
     if (Math.hypot(ev.clientX - sx, ev.clientY - sy) < 4) return;
     moved = true;
     item.x = Math.max(0, Math.min(100 - item.w, ox + (ev.clientX - sx) / rect.width * 100));
     item.y = Math.max(0, Math.min(100 - item.h, oy + (ev.clientY - sy) / rect.height * 100));
     const el = this.canvasEl.querySelector(`.slide-item[data-id="${item.id}"]`);
     if (el) { el.style.left = item.x + '%'; el.style.top = item.y + '%'; }
    };
    const up = () => {
     window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up);
     if (moved) { this.renderList(); this.notify(true); }
    };
    window.addEventListener('mousemove', move); window.addEventListener('mouseup', up);
   });
  }

  load(data, template) {
   if (data && Array.isArray(data.slides) && data.slides.length) {
    this.slides = data.slides;
    this.active = data.active || 0;
   } else if (template === 'pitch') {
    this.slides = [
     { id: 's-1', bg: '#f6f8fc', transition: 'fade', duration: 0.5, notes: '오프닝 인사 및 주제 소개', items: [
      { id: 'e-1', type: 'text', x: 8, y: 26, w: 84, h: 20, text: '새로운 가능성', size: 46, bold: true, color: '#1d2430', align: 'left' },
      { id: 'e-2', type: 'text', x: 8, y: 50, w: 72, h: 16, text: '프로젝트 제안 · 워크스페이스 팀', size: 22, bold: false, color: '#647084', align: 'left' }
     ] },
     { id: 's-2', bg: '#ffffff', transition: 'push', duration: 0.5, notes: '핵심 문제와 해결 방향 설명', items: [
      { id: 'e-3', type: 'text', x: 8, y: 12, w: 84, h: 14, text: '핵심 목표', size: 34, bold: true, color: '#1d2430', align: 'left' },
      { id: 'e-4', type: 'text', x: 8, y: 32, w: 84, h: 48, text: '• 더 빠른 실행과 가벼운 리소스\n• 직관적인 편집 경험\n• 일관된 모던 글래스 디자인', size: 24, bold: false, color: '#333d4b', align: 'left' }
     ] }
    ];
    this.active = 0;
   } else if (template === 'weekly') {
    this.slides = [
     { id: 's-1', bg: '#f4f8ff', transition: 'fade', duration: 0.5, notes: '주간 보고 개요', items: [
      { id: 'e-1', type: 'text', x: 8, y: 28, w: 84, h: 20, text: '주간 성과 보고', size: 44, bold: true, color: '#1e293b', align: 'left' },
      { id: 'e-2', type: 'text', x: 8, y: 52, w: 72, h: 14, text: '핵심 지표 · 완료 항목 · 다음 주 계획', size: 20, bold: false, color: '#64748b', align: 'left' }
     ] },
     { id: 's-2', bg: '#ffffff', transition: 'push', duration: 0.5, notes: '이번 주 주요 성과', items: [
      { id: 'e-3', type: 'text', x: 8, y: 12, w: 84, h: 14, text: '이번 주 완료 항목', size: 32, bold: true, color: '#1e293b', align: 'left' },
      { id: 'e-4', type: 'text', x: 8, y: 32, w: 84, h: 50, text: '✓ 핵심 기능 개발 및 검증 완료\n✓ 주요 이슈 100% 해결\n✓ 다음 주 릴리즈 준비 완료', size: 22, bold: false, color: '#334155', align: 'left' }
     ] }
    ];
    this.active = 0;
   } else if (template === 'launch') {
    this.slides = [
     { id: 's-1', bg: '#111827', transition: 'fade', duration: 0.5, notes: '키노트 오프닝', items: [
      { id: 'e-1', type: 'text', x: 10, y: 30, w: 80, h: 20, text: 'Next Generation', size: 48, bold: true, color: '#f8fafc', align: 'center' },
      { id: 'e-2', type: 'text', x: 14, y: 54, w: 72, h: 14, text: '더 빠르고 선명한 새로운 경험', size: 22, bold: false, color: '#94a3b8', align: 'center' }
     ] },
     { id: 's-2', bg: '#1e293b', transition: 'wipe', duration: 0.5, notes: '핵심 하이라이트', items: [
      { id: 'e-3', type: 'text', x: 8, y: 14, w: 84, h: 14, text: '핵심 하이라이트', size: 34, bold: true, color: '#f8fafc', align: 'left' },
      { id: 'e-4', type: 'shape', x: 8, y: 36, w: 26, h: 36, text: '속도 2배', size: 22, bold: true, color: '#0f172a', fill: '#93c5fd', align: 'center' },
      { id: 'e-5', type: 'shape', x: 37, y: 36, w: 26, h: 36, text: '광학 글래스', size: 22, bold: true, color: '#0f172a', fill: '#a7f3d0', align: 'center' },
      { id: 'e-6', type: 'shape', x: 66, y: 36, w: 26, h: 36, text: '완벽 호환', size: 22, bold: true, color: '#0f172a', fill: '#fde68a', align: 'center' }
     ] }
    ];
    this.active = 0;
   } else {
    this.slides = [{
     id: 's-1', bg: '#ffffff', transition: 'fade', duration: 0.5, notes: '',
     items: [
      { id: 'e-1', type: 'text', x: 10, y: 30, w: 80, h: 18, text: '제목을 입력하세요', size: 42, bold: true, color: '#1d2430', align: 'center' },
      { id: 'e-2', type: 'text', x: 15, y: 52, w: 70, h: 14, text: '부제목을 입력하세요', size: 20, bold: false, color: '#6f7989', align: 'center' }
     ]
    }];
    this.active = 0;
   }
   this.selectedId = null;
   this.render();
   this.notify(false);
  }

  serialize() {
   return { kind: 'present', slides: this.slides, active: this.active };
  }

  render() {
   this.renderList();
   this.renderCanvas();
   this.notesEl.value = this.slide?.notes || '';
  }

  renderList() {
   this.listEl.innerHTML = this.slides.map((s, i) => {
    const preview = (s.items[0]?.text || '빈 슬라이드').split('\n')[0];
    return `<button class="slide-thumb ${i === this.active ? 'active' : ''}" data-slide-idx="${i}"><span class="thumb-num">${i + 1}</span><div class="thumb-box" style="background:${esc(s.bg || '#fff')}"><span>${esc(preview)}</span></div></button>`;
   }).join('');
  }

  renderCanvas() {
   const s = this.slide;
   if (!s) return;
   this.canvasEl.style.background = s.bg || '#ffffff';
   this.canvasEl.innerHTML = s.items.map(it => {
    const sel = it.id === this.selectedId ? ' selected' : '';
    const style = `left:${it.x}%;top:${it.y}%;width:${it.w}%;height:${it.h}%;`;
    if (it.type === 'image') {
     return `<div class="slide-item${sel}" data-id="${it.id}" style="${style}"><img src="${it.src}" alt=""><span class="resize-handle"></span></div>`;
    }
    if (it.type === 'shape') {
     return `<div class="slide-item slide-shape${sel}" data-id="${it.id}" style="${style}background:${esc(it.fill || '#e6eefb')};border-radius:16px;"><div class="item-body" contenteditable="true" style="font-size:${it.size || 20}px;color:${esc(it.color || '#1d2430')};text-align:${it.align || 'center'};font-weight:${it.bold ? 700 : 400}">${esc(it.text || '')}</div><span class="resize-handle"></span></div>`;
    }
    return `<div class="slide-item${sel}" data-id="${it.id}" style="${style}"><div class="item-body" contenteditable="true" style="font-size:${it.size || 22}px;color:${esc(it.color || '#1d2430')};text-align:${it.align || 'left'};font-weight:${it.bold ? 700 : 400};font-style:${it.italic ? 'italic' : 'normal'}">${esc(it.text || '')}</div><span class="resize-handle"></span></div>`;
   }).join('');
   this.canvasEl.querySelectorAll('.item-body').forEach(el => {
    el.addEventListener('input', () => {
     const id = el.closest('.slide-item').dataset.id;
     const item = this.slide.items.find(i => i.id === id);
     if (item) { item.text = el.innerText; this.renderList(); this.notify(true); }
    });
   });
   this.setZoom(this.zoom);
  }

  exec(cmd, val) {
   if (cmd === 'addSlide') {
    this.slides.splice(this.active + 1, 0, {
     id: 's-' + Date.now(), bg: this.slide?.bg || '#ffffff', transition: 'fade', duration: 0.5, notes: '',
     items: [{ id: 'e-' + Date.now(), type: 'text', x: 8, y: 14, w: 84, h: 16, text: '새 슬라이드 제목', size: 34, bold: true, color: '#1d2430', align: 'left' }]
    });
    this.active++; this.selectedId = null; this.render(); this.notify(true); return;
   }
   if (cmd === 'dupSlide') {
    const copy = JSON.parse(JSON.stringify(this.slide));
    copy.id = 's-' + Date.now();
    this.slides.splice(this.active + 1, 0, copy);
    this.active++; this.render(); this.notify(true); return;
   }
   if (cmd === 'delSlide') {
    if (this.slides.length <= 1) return;
    this.slides.splice(this.active, 1);
    this.active = Math.max(0, this.active - 1);
    this.selectedId = null; this.render(); this.notify(true); return;
   }
   if (cmd === 'addText') {
    const id = 'e-' + Date.now();
    this.slide.items.push({ id, type: 'text', x: 16, y: 38, w: 68, h: 18, text: '텍스트를 입력하세요', size: 22, bold: false, color: '#1d2430', align: 'left' });
    this.selectedId = id; this.render(); this.notify(true); return;
   }
   if (cmd === 'addShape') {
    const id = 'e-' + Date.now();
    this.slide.items.push({ id, type: 'shape', x: 25, y: 32, w: 32, h: 24, text: '도형', size: 20, bold: true, color: '#1d2430', fill: '#e6eefb', align: 'center' });
    this.selectedId = id; this.render(); this.notify(true); return;
   }
   if (cmd === 'image' && val) {
    const id = 'e-' + Date.now();
    this.slide.items.push({ id, type: 'image', x: 22, y: 20, w: 56, h: 56, src: val });
    this.selectedId = id; this.render(); this.notify(true); return;
   }
   if (cmd === 'theme' && THEMES[val]) {
    const t = THEMES[val];
    this.slide.bg = t.bg;
    this.slide.items.forEach(it => { if (it.type !== 'image') it.color = t.text; });
    this.render(); this.notify(true); return;
   }
   if (cmd === 'transition') {
    this.slide.transition = val.effect || 'fade';
    this.slide.duration = val.duration || 0.5;
    this.notify(true); return;
   }
   const item = this.selectedItem || this.slide.items[0];
   if (!item) return;
   if (cmd === 'bold') item.bold = !item.bold;
   else if (cmd === 'italic') item.italic = !item.italic;
   else if (cmd === 'align') item.align = val;
   else if (cmd === 'color') item.color = val;
   else if (cmd === 'fontSizePt') item.size = Math.max(10, Math.min(96, Number(val) || 22));
   else if (cmd === 'deleteItem') {
    this.slide.items = this.slide.items.filter(i => i.id !== item.id);
    this.selectedId = null;
   }
   this.render(); this.notify(true);
  }

  setZoom(z) {
   this.zoom = z;
   this.canvasEl.style.transform = `scale(${z / 100})`;
  }

  notify(dirty = true) {
   this.onChange({ dirty, stats: `${this.active + 1} / ${this.slides.length} 슬라이드`, transition: this.slide?.transition || 'fade' });
  }
 }

 window.WorkspaceEditors = { TypingEditor, GraphsEditor, PresentEditor };
})();
