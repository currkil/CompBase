/* 元器件列表：搜索、筛选、快捷出入库、批量查看备注、高亮 BOM 关联元件。 */

import {
  state, $, api, el, pageHeader, button, toast, navigate,
  fmtInt, fmtPrice,
} from './core.js';
import { openNotes } from './notes-drawer.js';

export async function renderComponents(container, query) {
  container.textContent = '';
  const [components, types, zones] = await Promise.all([
    api('/api/components'),
    api('/api/types'),
    api('/api/zones'),
  ]);
  state.components = components;
  state.types = types;
  state.zones = zones;

  let highlightIds = new Set();
  let highlightLabel = '';
  const highlightBomId = query.get('highlight');
  if (highlightBomId) {
    try {
      const data = await api(`/api/bom/${encodeURIComponent(highlightBomId)}/components`);
      highlightIds = new Set((data.component_ids || []).map(String));
      highlightLabel = `BOM #${highlightBomId}`;
    } catch (error) {
      // 高亮的 BOM 不存在时忽略
    }
  }

  const tbody = el('tbody');
  const countText = el('span', { class: 'count' });

  const search = el('input', {
    class: 'search',
    type: 'search',
    placeholder: '搜索：值 / 类型 / 子类型 / 封装 / 位置…',
    value: state.filter.q,
    autocomplete: 'off',
    oninput: (event) => {
      state.filter.q = event.target.value;
      renderRows();
    },
  });

  const typeFilter = el('select', { class: 'filter', 'aria-label': '按类型筛选' });
  typeFilter.append(el('option', { value: '', text: '全部类型' }));
  for (const type of types) typeFilter.append(el('option', { value: type.type_id, text: type.type_name }));
  typeFilter.value = state.filter.typeId;
  typeFilter.addEventListener('change', (event) => {
    state.filter.typeId = event.target.value;
    renderRows();
  });

  const zoneFilter = el('select', { class: 'filter', 'aria-label': '按位置区筛选' });
  zoneFilter.append(el('option', { value: '', text: '全部位置区' }));
  for (const zone of zones) zoneFilter.append(el('option', { value: zone.zone_id, text: zone.zone_name }));
  zoneFilter.value = state.filter.zoneId;
  zoneFilter.addEventListener('change', (event) => {
    state.filter.zoneId = event.target.value;
    renderRows();
  });

  const toolbar = el('section', { class: 'toolbar' },
    search,
    typeFilter,
    zoneFilter,
    button('刷新', async () => {
      try {
        await renderComponents(container, new URLSearchParams());
        toast('已刷新');
      } catch (error) {
        toast('刷新失败：' + error.message, 'error');
      }
    }, 'ghost'),
    countText);

  const banner = highlightIds.size
    ? el('div', { class: 'banner' },
      `正在高亮 ${highlightLabel} 关联的 ${highlightIds.size} 个元器件（整行高亮、位置加重）`,
      button('取消高亮', () => navigate('/components'), 'tiny ghost'))
    : null;

  const table = el('table', { class: 'grid' },
    el('thead', {},
      el('tr', {},
        el('th', { text: '类型 / 子类型' }),
        el('th', { text: '值 / 型号' }),
        el('th', { text: '封装' }),
        el('th', { text: '位置' }),
        el('th', { class: 'num', text: '库存' }),
        el('th', { class: 'num', text: '占用' }),
        el('th', { class: 'num', text: '可用' }),
        el('th', { class: 'num', text: '单价' }),
        el('th', { text: '备注' }),
        el('th', { class: 'actions-col', text: '操作' }))),
    tbody);

  container.append(
    pageHeader('元器件', '库存、占用与存放位置一览', button('＋ 新增元器件', () => navigate('/component/new'), 'primary')),
    banner,
    toolbar,
    el('div', { class: 'table-wrap' }, table,
      el('p', { class: 'empty', text: '还没有元器件。点右上角「新增元器件」开始记录。', hidden: true })),
  );

  const empty = $('.empty', container);
  empty.hidden = true;

  function filtered() {
    const keyword = state.filter.q.trim().toLowerCase();
    return state.components.filter((component) => {
      if (state.filter.typeId && String(component.type_id) !== state.filter.typeId) return false;
      if (state.filter.zoneId && String(component.zone_id) !== state.filter.zoneId) return false;
      if (!keyword) return true;
      return [
        component.value, component.type_name, component.subtype_name,
        component.package_name, component.pkg_type_name,
        component.zone_name, component.location_code, component.location_label,
      ].some((text) => text && String(text).toLowerCase().includes(keyword));
    });
  }

  function renderRows() {
    const rows = filtered();
    tbody.textContent = '';
    empty.hidden = rows.length > 0;
    const stock = state.components.reduce((sum, c) => sum + (Number(c.stock_qty) || 0), 0);
    const value = state.components.reduce(
      (sum, c) => sum + (Number(c.available_qty) || 0) * (Number(c.price) || 0), 0);
    countText.textContent = `共 ${rows.length} / ${state.components.length} 种 · 库存 ${fmtInt(stock)} · 估值 ${fmtPrice(value)}`;
    for (const component of rows) tbody.append(rowFor(component));
  }

  function rowFor(component) {
    const related = highlightIds.has(String(component.id));
    const row = el('tr', { dataset: { id: component.id }, class: related ? 'row-related' : '' });
    row.addEventListener('click', (event) => {
      if (event.target.closest('button')) return;
      openNotes(component);
    });

    row.append(
      el('td', {},
        el('div', { class: 'cell-main', text: component.type_name || '未分类' }),
        component.subtype_name ? el('div', { class: 'cell-sub', text: component.subtype_name }) : null),
      el('td', {}, el('span', { class: 'value', text: component.value || '—' })),
      el('td', {},
        component.package_name
          ? el('div', { class: 'cell-main', text: component.package_name })
          : el('span', { class: 'muted', text: '—' }),
        component.pkg_type_name ? el('div', { class: 'cell-sub', text: component.pkg_type_name }) : null),
      el('td', {}, el('span', {
        class: 'loc' + (related ? ' loc-hot' : ''),
        text: component.location_label || '未分配',
      })),
      el('td', { class: 'num', text: fmtInt(component.stock_qty) }),
      el('td', { class: 'num', text: fmtInt(component.occupied_qty) }),
      el('td', { class: 'num' },
        el('span', {
          class: 'pill ' + (Number(component.available_qty) > 0 ? 'ok' : 'zero'),
          text: fmtInt(component.available_qty),
        })),
      el('td', { class: 'num', text: fmtPrice(component.price) }),
      el('td', {},
        el('button', {
          class: 'link-btn', type: 'button', text: component.note_exists ? '查看' : '添加',
          onclick: () => openNotes(component),
        })),
      el('td', { class: 'actions' },
        el('button', {
          class: 'mini-btn', type: 'button', title: '出库 1',
          text: '−', onclick: () => adjustStock(component, -1),
        }),
        el('button', {
          class: 'mini-btn', type: 'button', title: '入库 1',
          text: '＋', onclick: () => adjustStock(component, 1),
        }),
        el('button', {
          class: 'mini-btn', type: 'button', title: '编辑',
          text: '✎', onclick: () => navigate(`/component/${component.id}`),
        }),
        el('button', {
          class: 'mini-btn danger', type: 'button', title: '删除',
          text: '🗑', onclick: () => remove(component),
        })));
    return row;
  }

  async function adjustStock(component, delta) {
    try {
      await api(`/api/components/${component.id}/stock`, { method: 'POST', json: { delta } });
      const updated = await api(`/api/components/${component.id}`);
      const index = state.components.findIndex((item) => item.id === component.id);
      if (index >= 0) state.components[index] = updated;
      renderRows();
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function remove(component) {
    if (!confirm(`确定删除「${component.value || '未命名元器件'}」？对应的备注文件与图片也会一起删除。`)) return;
    try {
      await api(`/api/components/${component.id}`, { method: 'DELETE' });
      state.components = state.components.filter((item) => item.id !== component.id);
      renderRows();
      toast('已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  renderRows();
}
