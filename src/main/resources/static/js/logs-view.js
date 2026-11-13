/* 导入日志：所有导入操作（基础数据 + BOM）的可追溯记录。 */

import { api, el, pageHeader, badge, button, navigate, fmtTime, fmtInt } from './core.js';

const TYPE_LABELS = {
  TYPE: '类型',
  SUBTYPE: '子类型',
  PACKAGE_TYPE: '封装类型',
  PACKAGE: '封装',
  ZONE: '位置区',
  LOCATION: '位置',
  BOM: 'BOM',
};

export async function renderLogs(container, query) {
  container.textContent = '';
  const type = query.get('type') || '';
  const logs = await api('/api/import-log/list' + (type ? '?type=' + encodeURIComponent(type) : ''));

  const typeSelect = el('select', { class: 'filter' });
  typeSelect.append(el('option', { value: '', text: '全部类型' }));
  for (const [value, label] of Object.entries(TYPE_LABELS)) {
    typeSelect.append(el('option', { value, text: label }));
  }
  typeSelect.value = type;
  typeSelect.addEventListener('change', () => {
    navigate(typeSelect.value ? `/logs?type=${encodeURIComponent(typeSelect.value)}` : '/logs');
  });

  const tbody = el('tbody');
  for (const log of logs) tbody.append(...logRow(log));

  container.append(
    pageHeader('导入日志', '每次 CSV 导入都会留下记录，可查看失败详情',
      button('去基础数据导入', () => navigate('/import'), 'ghost')),
    el('section', { class: 'card' },
      el('div', { class: 'toolbar' }, typeSelect,
        el('span', { class: 'count', text: `共 ${logs.length} 条` })),
      logs.length
        ? el('div', { class: 'table-wrap' },
          el('table', { class: 'grid' },
            el('thead', {}, el('tr', {},
              el('th', { text: '时间' }),
              el('th', { text: '类型' }),
              el('th', { text: '文件名' }),
              el('th', { text: '重复策略' }),
              el('th', { class: 'num', text: '总数' }),
              el('th', { class: 'num', text: '成功' }),
              el('th', { class: 'num', text: '跳过' }),
              el('th', { class: 'num', text: '失败' }),
              el('th', { class: 'actions-col', text: '操作' }))),
            tbody))
        : el('p', { class: 'muted', text: '还没有导入记录。' })));
}

function logRow(log) {
  const detailRow = el('tr', { class: 'log-detail', hidden: true },
    el('td', { colspan: 9 }, el('div', { class: 'log-detail-body' })));

  const toggle = button('详情', async () => {
    if (!detailRow.hidden) {
      detailRow.hidden = true;
      toggle.textContent = '详情';
      return;
    }
    const body = el('div', { class: 'log-detail-body' });
    try {
      const detail = await api(`/api/import-log/${log.log_id}`);
      if (detail.errors && detail.errors.length) {
        body.append(el('h3', { text: '失败详情' }),
          el('ul', {}, ...detail.errors.map((message) => el('li', { text: message }))));
      } else {
        body.append(el('p', { class: 'muted', text: '没有失败详情。' }));
      }
      if (detail.bom_id) {
        body.append(el('p', {}, '关联 BOM：',
          el('a', { href: `#/bom/${detail.bom_id}`, text: `查看 BOM #${detail.bom_id} 明细` })));
      }
    } catch (error) {
      body.append(el('p', { class: 'error-text', text: error.message }));
    }
    detailRow.querySelector('td').textContent = '';
    detailRow.querySelector('td').append(body);
    detailRow.hidden = false;
    toggle.textContent = '收起';
  }, 'tiny ghost');

  const row = el('tr', {},
    el('td', { text: fmtTime(log.created_at) }),
    el('td', {}, badge(TYPE_LABELS[log.import_type] || log.import_type, log.import_type === 'BOM' ? 'info' : 'muted')),
    el('td', { text: log.file_name || '—' }),
    el('td', { text: log.duplicate_strategy || '—' }),
    el('td', { class: 'num', text: fmtInt(log.total_rows) }),
    el('td', { class: 'num', text: fmtInt(log.success_rows) }),
    el('td', { class: 'num', text: fmtInt(log.skipped_rows) }),
    el('td', { class: 'num' + (log.failed_rows ? ' danger-text' : ''), text: fmtInt(log.failed_rows) }),
    el('td', { class: 'actions' },
      toggle,
      log.bom_id
        ? el('button', {
          class: 'mini-btn', type: 'button', title: '查看 BOM 明细',
          text: '👁', onclick: () => navigate(`/bom/${log.bom_id}`),
        })
        : null));

  return [row, detailRow];
}
