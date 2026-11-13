/* 数据管理：整页维护类型/子类型、位置区/位置。封装相关已移到「封装大全」页。 */

import {
  state, api, el, pageHeader, button, toast, navigate, loadBasicData,
} from './core.js';

export async function renderManage(container, query) {
  await loadBasicData();
  let tab = query.get('tab') || 'types';

  const body = el('div', { class: 'manage-body' });
  const tabs = el('nav', { class: 'tabs' });
  const tabDefs = [['types', '类型'], ['zones', '位置']];

  function renderTabs() {
    tabs.textContent = '';
    for (const [key, label] of tabDefs) {
      tabs.append(el('button', {
        class: 'tab' + (tab === key ? ' is-active' : ''),
        type: 'button',
        text: label,
        onclick: () => {
          tab = key;
          renderTabs();
          renderBody();
        },
      }));
    }
  }

  function renderBody() {
    body.textContent = '';
    if (tab === 'zones') renderZones();
    else renderTypes();
  }

  async function reload() {
    await loadBasicData();
    renderBody();
  }

  function renderTypes() {
    body.append(el('form', {
      class: 'inline-form',
      onsubmit: async (event) => {
        event.preventDefault();
        const form = event.target;
        const name = form.elements.namedItem('type_name').value.trim();
        const description = form.elements.namedItem('description').value.trim();
        if (!name) return;
        try {
          await api('/api/types', { method: 'POST', json: { type_name: name, description: description || null } });
          form.reset();
          await reload();
          toast('类型已添加');
        } catch (error) {
          toast(error.message, 'error');
        }
      },
    },
    el('input', { name: 'type_name', placeholder: '类型名称，例如 电阻', required: true }),
    el('input', { name: 'description', placeholder: '描述（可选）' }),
    el('button', { class: 'btn tiny primary', type: 'submit', text: '添加类型' })));

    if (!state.types.length) {
      body.append(el('p', { class: 'muted', text: '还没有类型，先添加一个，或者去「基础数据导入」批量导入。' }));
      return;
    }

    for (const type of state.types) {
      const chips = el('div', { class: 'chips' });
      for (const subtype of type.subtypes) {
        chips.append(el('span', { class: 'chip' }, subtype.subtype_name,
          el('button', {
            type: 'button', title: '删除子类型', text: '×',
            onclick: () => removeSubtype(subtype),
          })));
      }
      if (!type.subtypes.length) chips.append(el('span', { class: 'muted', text: '暂无子类型' }));

      body.append(el('div', { class: 'manage-item' },
        el('div', { class: 'manage-main' },
          el('strong', { text: type.type_name }),
          type.description ? el('span', { class: 'muted', text: type.description }) : null,
          el('button', {
            class: 'mini-btn danger push', type: 'button', title: '删除类型',
            text: '×', onclick: () => removeType(type),
          })),
        chips,
        el('form', {
          class: 'inline-form small',
          onsubmit: async (event) => {
            event.preventDefault();
            const form = event.target;
            const name = form.elements.namedItem('subtype_name').value.trim();
            if (!name) return;
            try {
              await api(`/api/types/${type.type_id}/subtypes`, {
                method: 'POST', json: { subtype_name: name },
              });
              await reload();
              toast('子类型已添加');
            } catch (error) {
              toast(error.message, 'error');
            }
          },
        },
        el('input', { name: 'subtype_name', placeholder: `为「${type.type_name}」添加子类型` }),
        el('button', { class: 'btn tiny', type: 'submit', text: '添加子类型' }))));
    }
  }

  function renderZones() {
    body.append(el('form', {
      class: 'inline-form',
      onsubmit: async (event) => {
        event.preventDefault();
        const form = event.target;
        const name = form.elements.namedItem('zone_name').value.trim();
        const description = form.elements.namedItem('description').value.trim();
        if (!name) return;
        try {
          await api('/api/zones', { method: 'POST', json: { zone_name: name, description: description || null } });
          form.reset();
          await reload();
          toast('位置区已添加');
        } catch (error) {
          toast(error.message, 'error');
        }
      },
    },
    el('input', { name: 'zone_name', placeholder: '位置区名称，例如 3号袋', required: true }),
    el('input', { name: 'description', placeholder: '描述（可选）' }),
    el('button', { class: 'btn tiny primary', type: 'submit', text: '添加位置区' })));

    if (!state.zones.length) {
      body.append(el('p', { class: 'muted', text: '还没有位置区，先添加一个，或者去「基础数据导入」批量导入。' }));
      return;
    }

    for (const zone of state.zones) {
      const chips = el('div', { class: 'chips' });
      for (const location of zone.locations) {
        chips.append(el('span', { class: 'chip' },
          location.label || `${zone.zone_name}-${location.location_code}`,
          el('button', {
            type: 'button', title: '删除位置', text: '×',
            onclick: () => removeLocation(zone, location),
          })));
      }
      if (!zone.locations.length) chips.append(el('span', { class: 'muted', text: '暂无位置' }));

      body.append(el('div', { class: 'manage-item' },
        el('div', { class: 'manage-main' },
          el('strong', { text: zone.zone_name }),
          zone.description ? el('span', { class: 'muted', text: zone.description }) : null,
          el('button', {
            class: 'mini-btn danger push', type: 'button', title: '删除位置区',
            text: '×', onclick: () => removeZone(zone),
          })),
        chips,
        el('form', {
          class: 'inline-form small',
          onsubmit: async (event) => {
            event.preventDefault();
            const form = event.target;
            const code = form.elements.namedItem('location_code').value.trim();
            if (!code) return;
            try {
              await api(`/api/zones/${zone.zone_id}/locations`, {
                method: 'POST', json: { location_code: code },
              });
              await reload();
              toast('位置已添加');
            } catch (error) {
              toast(error.message, 'error');
            }
          },
        },
        el('input', { name: 'location_code', placeholder: '区内编号，例如 05', required: true }),
        el('button', { class: 'btn tiny', type: 'submit', text: '添加位置' }))));
    }
  }

  async function removeType(type) {
    if (!confirm(`删除类型「${type.type_name}」及其子类型？`)) return;
    try {
      await api(`/api/types/${type.type_id}`, { method: 'DELETE' });
      await reload();
      toast('类型已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function removeSubtype(subtype) {
    if (!confirm(`删除子类型「${subtype.subtype_name}」？`)) return;
    try {
      await api(`/api/subtypes/${subtype.subtype_id}`, { method: 'DELETE' });
      await reload();
      toast('子类型已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function removeZone(zone) {
    if (!confirm(`删除位置区「${zone.zone_name}」及其下所有位置？`)) return;
    try {
      await api(`/api/zones/${zone.zone_id}`, { method: 'DELETE' });
      await reload();
      toast('位置区已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function removeLocation(zone, location) {
    const label = location.label || `${zone.zone_name}-${location.location_code}`;
    if (!confirm(`删除位置「${label}」？`)) return;
    try {
      await api(`/api/locations/${location.location_id}`, { method: 'DELETE' });
      await reload();
      toast('位置已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  renderTabs();
  renderBody();

  container.append(
    pageHeader('数据管理', '维护元器件类型与存放位置；封装请到「封装大全」',
      button('封装大全', () => navigate('/packages'), 'ghost'),
      button('批量导入', () => navigate('/import'), 'primary')),
    el('section', { class: 'card' }, tabs, body));
}
