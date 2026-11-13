/* BOM 匹配占用：导入 CSV / xlsx、预览标色、人工匹配、确认占用、撤销占用。 */

import {
  api, el, pageHeader, button, badge, toast, navigate, statusMeta,
  fmtInt, fmtTime, dash,
} from './core.js';

/* ==================== BOM 列表 + 导入 ==================== */

export async function renderBomList(container) {
  container.textContent = '';
  const boms = await api('/api/bom/list');

  const projectInput = el('input', { name: 'project_name', placeholder: '项目名（可选），例如 电源板 v1' });
  const fileInput = el('input', {
    type: 'file',
    accept: '.csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    required: true,
  });
  const importButton = el('button', { class: 'btn primary', type: 'submit', text: '导入并预览' });
  const mappingBox = el('div', { class: 'mapping-box', hidden: true });
  let pendingFile = null;
  let pendingProject = '';
  let pendingColumns = {};

  async function upload(file, project, columns) {
    const params = new URLSearchParams({
      file_name: file.name,
      project_name: project || '',
    });
    for (const [key, value] of Object.entries(columns || {})) {
      if (value !== '' && value !== null && value !== undefined) params.set(key, value);
    }
    const result = await api(`/api/bom/import?${params.toString()}`, {
      method: 'POST',
      body: file,
    });
    navigate(`/bom/${result.bom.bom_id}`);
  }

  const importForm = el('form', {
    class: 'inline-form bom-import-form',
    onsubmit: async (event) => {
      event.preventDefault();
      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        toast('请选择 BOM CSV 文件', 'error');
        return;
      }
      importButton.disabled = true;
      try {
        pendingFile = file;
        pendingProject = projectInput.value.trim();
        pendingColumns = {};
        mappingBox.hidden = true;
        await upload(file, pendingProject, {});
      } catch (error) {
        if (error.payload && error.payload.code === 'MAPPING_REQUIRED') {
          renderMapping(error.payload);
        } else {
          toast('导入失败：' + error.message, 'error');
        }
      } finally {
        importButton.disabled = false;
      }
    },
  }, projectInput, fileInput, importButton);

  function renderMapping(payload) {
    mappingBox.textContent = '';
    mappingBox.hidden = false;
    const selects = {};
    for (const field of payload.fields || []) {
      const select = el('select', { name: field.key });
      select.append(el('option', { value: '', text: '（不指定）' }));
      (payload.headers || []).forEach((header, index) => {
        select.append(el('option', { value: String(index), text: `${index + 1}. ${header}` }));
      });
      if (payload.values && payload.values[field.key] !== undefined) {
        select.value = String(payload.values[field.key]);
      }
      selects[field.key] = select;
      const required = field.required ? el('span', { class: 'required', text: '*' }) : null;
      mappingBox.append(el('label', { class: 'mapping-field' },
        el('span', { text: field.label }), required, select));
    }
    mappingBox.append(el('div', { class: 'mapping-actions' },
      el('p', { class: 'muted', text: '表头无法自动识别，请手动指定列，然后重新导入。' }),
      button('按指定列导入', async () => {
        if (!pendingFile) return;
        const columns = {};
        for (const [key, select] of Object.entries(selects)) {
          if (select.value !== '') columns[key] = select.value;
        }
        try {
          await upload(pendingFile, pendingProject, columns);
        } catch (error) {
          toast('导入失败：' + error.message, 'error');
        }
      }, 'primary')));
  }

  container.append(
    pageHeader('BOM 匹配占用',
      '导入 BOM，按「值 + 封装」自动匹配；确认后占用库存，可随时撤销',
      button('查看导入日志', () => navigate('/logs'), 'ghost')),
    el('section', { class: 'card' },
      el('h2', { class: 'card-title', text: '导入 BOM 表' }),
      el('p', { class: 'muted', text: '支持 CSV 与 xlsx（读取第一个工作表），需要包含 值 / 封装 / 数量 三列；中英文表头自动识别，识别失败可手动指定列。' }),
      importForm,
      mappingBox),
    el('section', { class: 'card' },
      el('h2', { class: 'card-title', text: '导入记录' }),
      bomTable(boms)));
}

function bomTable(boms) {
  if (!boms.length) {
    return el('p', { class: 'muted', text: '还没有导入过 BOM。' });
  }
  const tbody = el('tbody');
  for (const bom of boms) {
    tbody.append(el('tr', {},
      el('td', { class: 'mono', text: `#${bom.bom_id}` }),
      el('td', {},
        el('div', { class: 'cell-main', text: bom.file_name || '—' }),
        bom.project_name ? el('div', { class: 'cell-sub', text: bom.project_name }) : null),
      el('td', { text: fmtTime(bom.imported_at) }),
      el('td', {},
        bom.confirmed_at
          ? badge('已占用', 'ok')
          : badge('未确认', 'muted'),
        bom.confirmed_at ? el('div', { class: 'cell-sub', text: fmtTime(bom.confirmed_at) }) : null),
      el('td', { class: 'num', text: `${fmtInt(bom.matched_count)} / ${fmtInt(bom.item_count)}` }),
      el('td', { class: 'num', text: fmtInt(bom.occupied_qty) }),
      el('td', { class: 'actions' },
        el('button', {
          class: 'mini-btn', type: 'button', title: '查看明细',
          text: '👁', onclick: () => navigate(`/bom/${bom.bom_id}`),
        }),
        el('button', {
          class: 'mini-btn', type: 'button', title: '在元器件列表高亮关联元件',
          text: '◎', onclick: () => navigate(`/components?highlight=${bom.bom_id}`),
        }),
        bom.confirmed_at
          ? el('button', {
            class: 'mini-btn danger', type: 'button', title: '撤销占用',
            text: '↩', onclick: () => revoke(bom),
          })
          : null)));
  }
  return el('div', { class: 'table-wrap' },
    el('table', { class: 'grid' },
      el('thead', {}, el('tr', {},
        el('th', { text: '编号' }),
        el('th', { text: '文件 / 项目' }),
        el('th', { text: '导入时间' }),
        el('th', { text: '状态' }),
        el('th', { class: 'num', text: '已匹配 / 明细' }),
        el('th', { class: 'num', text: '已占用' }),
        el('th', { class: 'actions-col', text: '操作' }))),
      tbody));
}

async function revoke(bom) {
  if (!confirm(`撤销 BOM #${bom.bom_id} 的占用？已占用的数量会退回。`)) return;
  try {
    const result = await api(`/api/bom/${bom.bom_id}/revoke`, { method: 'POST' });
    toast(`已撤销，恢复 ${result.revoked} 个元器件的占用数量`);
    navigate('/bom');
  } catch (error) {
    toast(error.message, 'error');
  }
}

/* ==================== BOM 明细 ==================== */

export async function renderBomDetail(container, bomId) {
  container.textContent = '';
  const data = await api(`/api/bom/${bomId}`);
  const { bom, items } = data;

  const counts = { total: items.length, matched: 0, warning: 0, error: 0 };
  for (const item of items) {
    const meta = statusMeta(item.match_status);
    if (meta.cls === 'ok') counts.matched++;
    else if (meta.cls === 'warn') counts.warning++;
    else if (meta.cls === 'danger') counts.error++;
  }
  const pending = counts.total - counts.matched;

  const tbody = el('tbody');
  for (const item of items) tbody.append(itemRow(item, tbody));

  const confirmButton = bom.confirmed_at
    ? null
    : button(pending > 0 ? `强制确认（${pending} 行不占用）` : '确认占用', () => confirmOccupy(bom, pending), 'primary');
  const revokeButton = bom.confirmed_at
    ? button('撤销占用', () => revoke(bom), 'danger')
    : null;

  container.append(
    pageHeader(`BOM #${bom.bom_id}`,
      `${bom.file_name}${bom.project_name ? ' · ' + bom.project_name : ''} · 导入于 ${fmtTime(bom.imported_at)}`,
      button('返回 BOM 列表', () => navigate('/bom'), 'ghost'),
      button('高亮关联元件', () => navigate(`/components?highlight=${bom.bom_id}`), 'ghost'),
      confirmButton,
      revokeButton),
    el('section', { class: 'card' },
      el('div', { class: 'bom-summary' },
        summaryChip('明细', counts.total),
        summaryChip('绿色可占用', counts.matched, 'ok'),
        summaryChip('黄色待人工', counts.warning, 'warn'),
        summaryChip('红色未找到', counts.error, 'danger'),
        bom.confirmed_at ? summaryChip('状态', '已占用', 'ok') : summaryChip('状态', '未确认', 'muted')),
      el('p', { class: 'muted', text: '确认后只有绿色行会占用库存；黄色 / 红色行不会占用，可逐行「匹配」后再确认，或直接强制确认。' }),
      items.length
        ? el('div', { class: 'table-wrap' },
          el('table', { class: 'grid bom-grid' },
            el('thead', {}, el('tr', {},
              el('th', { text: '原始值' }),
              el('th', { text: '原始封装' }),
              el('th', { class: 'num', text: '数量' }),
              el('th', { text: '匹配到的元器件' }),
              el('th', { text: '位置' }),
              el('th', { class: 'num', text: '可用' }),
              el('th', { text: '状态' }),
              el('th', { class: 'actions-col', text: '操作' }))),
            tbody))
        : el('p', { class: 'muted', text: '这个 BOM 没有明细。' })));
}

function summaryChip(label, value, cls = '') {
  return el('span', { class: 'summary-chip ' + cls },
    el('span', { class: 'summary-label', text: label }),
    el('strong', { text: String(value) }));
}

function itemRow(item, tbody) {
  const meta = statusMeta(item.match_status);
  const row = el('tr', { class: 'status-' + meta.cls },
    el('td', {}, el('span', { class: 'value', text: item.raw_value || '—' })),
    el('td', { text: dash(item.raw_package) }),
    el('td', { class: 'num', text: fmtInt(item.quantity) }),
    el('td', {},
      item.component_id
        ? el('div', {},
          el('div', { class: 'cell-main', text: item.component_value || `#${item.component_id}` }),
          el('div', { class: 'cell-sub', text: [item.type_name, item.subtype_name, item.package_name].filter(Boolean).join(' / ') }))
        : el('span', { class: 'muted', text: '未匹配' }),
      item.match_note ? el('div', { class: 'cell-sub', text: item.match_note }) : null),
    el('td', { text: dash(item.location_label) }),
    el('td', { class: 'num', text: item.component_id ? fmtInt(item.available_qty) : '—' }),
    el('td', {}, badge(meta.label, meta.cls)),
    el('td', { class: 'actions' },
      el('button', {
        class: 'btn tiny', type: 'button', text: '匹配',
        onclick: () => openMatcher(item, row, tbody),
      })));
  return row;
}

function openMatcher(item, row, tbody) {
  const existing = tbody.querySelector('tr.match-editor');
  if (existing) existing.remove();

  const searchInput = el('input', {
    class: 'matcher-search',
    type: 'search',
    placeholder: '按值 / 类型 / 封装 / 位置搜索元器件…',
    value: item.raw_value || '',
  });
  const results = el('div', { class: 'matcher-results' });

  async function search() {
    results.textContent = '';
    const keyword = searchInput.value.trim();
    try {
      const components = await api('/api/components?q=' + encodeURIComponent(keyword));
      if (!components.length) {
        results.append(el('p', { class: 'muted', text: '没有匹配的元器件。' }));
        return;
      }
      for (const component of components.slice(0, 20)) {
        results.append(el('button', {
          class: 'matcher-item',
          type: 'button',
          onclick: () => choose(component),
        },
        el('span', { class: 'cell-main', text: component.value || '（无值）' }),
        el('span', {
          class: 'cell-sub',
          text: [component.type_name, component.subtype_name, component.package_name,
            component.location_label].filter(Boolean).join(' / '),
        }),
        el('span', { class: 'matcher-avail', text: `可用 ${fmtInt(component.available_qty)}` })));
      }
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function choose(component) {
    try {
      await api(`/api/bom/item/${item.bom_item_id}/match`, {
        method: 'POST',
        json: { component_id: component.id },
      });
      toast('已人工指定');
      navigate(`/bom/${item.bom_id}`);
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  const editorRow = el('tr', { class: 'match-editor' },
    el('td', { colspan: 8 },
      el('div', { class: 'matcher' },
        el('div', { class: 'matcher-bar' },
          searchInput,
          button('搜索', search, 'tiny'),
          button('关闭', () => editorRow.remove(), 'tiny ghost')),
        results)));

  row.after(editorRow);
  searchInput.focus();
  search();
  searchInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      search();
    }
  });
}

async function confirmOccupy(bom, pending) {
  let force = false;
  if (pending > 0) {
    force = confirm(`有 ${pending} 行不是绿色（未匹配 / 数量不足 / 封装不符），确认后这些行不会占用库存。\n仍要继续吗？`);
    if (!force) return;
  }
  try {
    const result = await api(`/api/bom/${bom.bom_id}/confirm`, {
      method: 'POST',
      json: { force },
    });
    let message = `已占用 ${result.occupied} 行`;
    if (result.skipped && result.skipped.length) {
      message += `，${result.skipped.length} 行因可用数量变化被跳过`;
    }
    toast(message);
    navigate(`/bom/${bom.bom_id}`);
  } catch (error) {
    toast(error.message, 'error');
  }
}
