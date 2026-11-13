/* ============================================================
 * CompBase 前端核心：状态、请求、DOM 小工具、格式化、Markdown。
 * 无构建步骤，使用浏览器原生 ES Module。
 * ============================================================ */

export const state = {
  components: [],
  types: [],
  zones: [],
  locations: [],
  packages: [],
  packageTypes: [],
  filter: { q: '', typeId: '', zoneId: '' },
};

export const $ = (selector, root = document) => root.querySelector(selector);

/* ==================== DOM ==================== */

export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') {
      node.className = value;
    } else if (key === 'text') {
      node.textContent = value;
    } else if (key === 'dataset') {
      Object.assign(node.dataset, value);
    } else if (key.startsWith('on') && typeof value === 'function') {
      node.addEventListener(key.slice(2), value);
    } else {
      node.setAttribute(key, value === true ? '' : String(value));
    }
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function pageHeader(title, subtitle, ...actions) {
  return el('div', { class: 'view-head' },
    el('div', { class: 'view-title' },
      el('h1', { text: title }),
      subtitle ? el('p', { class: 'muted', text: subtitle }) : null),
    el('div', { class: 'view-actions' }, ...actions));
}

export function card(title, ...children) {
  return el('section', { class: 'card' },
    title ? el('h2', { class: 'card-title', text: title }) : null,
    ...children);
}

export function button(label, onClick, variant = '') {
  return el('button', {
    class: 'btn' + (variant ? ' ' + variant : ''),
    type: 'button',
    text: label,
    onclick: onClick,
  });
}

export function badge(text, cls) {
  return el('span', { class: 'badge ' + cls, text });
}

/* ==================== 请求 ==================== */

export async function api(path, options = {}) {
  const init = { method: options.method || 'GET' };
  if (options.json !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(options.json);
  } else if (options.body !== undefined) {
    init.body = options.body;
  }

  const response = await fetch(path, init);
  const contentType = response.headers.get('Content-Type') || '';
  let payload;
  if (contentType.includes('application/json')) {
    payload = await response.json().catch(() => null);
  } else {
    payload = await response.text();
  }

  if (!response.ok) {
    const error = new Error(
      payload && typeof payload === 'object' && payload.error
        ? payload.error
        : (typeof payload === 'string' && payload.trim()
          ? payload.trim()
          : `请求失败 (HTTP ${response.status})`));
    error.status = response.status;
    error.payload = payload;
    throw error;
  }
  return payload;
}

/* ==================== 提示与导航 ==================== */

let toastTimer = null;

export function toast(message, kind = 'ok') {
  const node = $('#toast');
  if (!node) return;
  node.textContent = message;
  node.className = 'toast' + (kind === 'error' ? ' error' : '');
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    node.hidden = true;
  }, 2800);
}

export function navigate(path) {
  const target = '#' + path;
  if (location.hash === target) {
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  } else {
    location.hash = target;
  }
}

export function parseHash() {
  const raw = decodeURIComponent((location.hash || '').replace(/^#/, '') || '/components');
  const mark = raw.indexOf('?');
  const path = mark >= 0 ? raw.slice(0, mark) : raw;
  const query = new URLSearchParams(mark >= 0 ? raw.slice(mark + 1) : '');
  return { path, query, segments: path.split('/').filter(Boolean) };
}

/* ==================== 格式化 ==================== */

export function numberOrNull(value) {
  if (value === '' || value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function fmtInt(value) {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN');
}

export function fmtPrice(value) {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return '¥' + (Math.round(n * 10000) / 10000).toString();
}

export function fmtTime(value) {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
}

export function dash(value) {
  return value === null || value === undefined || value === '' ? '—' : value;
}

/* ==================== 匹配状态 ==================== */

const STATUS_META = {
  MATCHED: { label: '可占用', cls: 'ok' },
  WARN_SHORTAGE: { label: '数量不足', cls: 'warn' },
  WARN_AMBIGUOUS: { label: '匹配多条', cls: 'warn' },
  WARN_PACKAGE_MISMATCH: { label: '封装不符', cls: 'warn' },
  ERROR_NOT_FOUND: { label: '未找到', cls: 'danger' },
  PENDING: { label: '待匹配', cls: 'muted' },
};

export function statusMeta(status) {
  return STATUS_META[status] || { label: status || '未知', cls: 'muted' };
}

/* ==================== 基础数据 ==================== */

export async function loadBasicData() {
  const [types, zones, locations, packages, packageTypes] = await Promise.all([
    api('/api/types'),
    api('/api/zones'),
    api('/api/locations'),
    api('/api/packages'),
    api('/api/package-types'),
  ]);
  Object.assign(state, { types, zones, locations, packages, packageTypes });
}

/* ==================== Markdown ==================== */

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[ch]));
}

function inlineMarkdown(text) {
  let html = escapeHtml(text);
  html = html.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g,
    (match, alt, src) => `<img src="${src}" alt="${alt}" loading="lazy">`);
  html = html.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,
    (match, label, href) => `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`);
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  return html;
}

export function renderMarkdown(source) {
  const lines = String(source || '').replace(/\r\n?/g, '\n').split('\n');
  const out = [];
  const paragraph = [];
  let listTag = null;
  let inCode = false;

  const flushParagraph = () => {
    if (paragraph.length) {
      out.push('<p>' + inlineMarkdown(paragraph.join(' ')) + '</p>');
      paragraph.length = 0;
    }
  };
  const closeList = () => {
    if (listTag) {
      out.push('</' + listTag + '>');
      listTag = null;
    }
  };

  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      flushParagraph();
      closeList();
      out.push(inCode ? '</code></pre>' : '<pre><code>');
      inCode = !inCode;
      continue;
    }
    if (inCode) {
      out.push(escapeHtml(line));
      continue;
    }
    if (!line.trim()) {
      flushParagraph();
      closeList();
      continue;
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flushParagraph();
      closeList();
      const level = heading[1].length;
      out.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      flushParagraph();
      closeList();
      out.push('<hr>');
      continue;
    }

    const unordered = /^\s*[-*+]\s+(.*)$/.exec(line);
    const ordered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    if (unordered || ordered) {
      flushParagraph();
      const wanted = unordered ? 'ul' : 'ol';
      if (listTag !== wanted) {
        closeList();
        out.push('<' + wanted + '>');
        listTag = wanted;
      }
      out.push('<li>' + inlineMarkdown((unordered || ordered)[1]) + '</li>');
      continue;
    }

    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote) {
      flushParagraph();
      closeList();
      out.push('<blockquote>' + inlineMarkdown(quote[1]) + '</blockquote>');
      continue;
    }

    closeList();
    paragraph.push(line.trim());
  }

  flushParagraph();
  closeList();
  if (inCode) out.push('</code></pre>');
  return out.join('\n');
}

/* ==================== 表单选项 ==================== */

export function option(value, label) {
  return el('option', { value: value === null || value === undefined ? '' : value, text: label });
}

export function fillSelect(select, placeholder, items, selected, labelOf) {
  select.textContent = '';
  select.append(option('', placeholder));
  for (const item of items) select.append(option(labelOf.value(item), labelOf.label(item)));
  select.value = selected === null || selected === undefined ? '' : String(selected);
}
