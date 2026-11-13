/* 封装大全：独立整页列表，含搜索、封装类型维护、3D 预览与「从 3D 库导入」。 */

import {
  state, api, el, pageHeader, button, badge, toast, navigate, loadBasicData,
} from './core.js';
import { fetchModels, buildModelImporter } from './model-import.js';

export async function renderPackagesView(container, query) {
  container.textContent = '';
  await loadBasicData();
  let models = await fetchModels();

  let search = query.get('q') || '';
  let typeFilter = query.get('type') || '';
  let importer = null;

  const tbody = el('tbody');
  const countText = el('span', { class: 'count' });
  const typeStrip = el('div', { class: 'type-strip' });
  const importMount = el('div');

  function filtered() {
    const keyword = search.trim().toLowerCase();
    return state.packages.filter((pkg) => {
      if (typeFilter && String(pkg.pkg_type_id) !== typeFilter) return false;
      if (!keyword) return true;
      return [pkg.package_name, pkg.full_name, pkg.model_file, pkg.description, pkg.pkg_type_name]
        .some((text) => text && String(text).toLowerCase().includes(keyword));
    });
  }

  function rowFor(pkg) {
    return el('tr', {},
      el('td', { class: 'muted', text: pkg.pkg_type_name || '—' }),
      el('td', { class: 'cell-main', text: pkg.package_name }),
      el('td', { class: 'mono', text: pkg.full_name || '—' }),
      el('td', { class: 'num', text: pkg.pin_count === null || pkg.pin_count === undefined ? '—' : pkg.pin_count }),
      el('td', { class: 'num', text: pkg.pitch === null || pkg.pitch === undefined ? '—' : pkg.pitch }),
      el('td', {}, pkg.model_file
        ? el('span', { class: 'badge info', text: `${pkg.model_format || '模型'} · ${pkg.model_file}` })
        : el('span', { class: 'muted', text: '未关联' })),
      el('td', { class: 'muted', text: pkg.description || '—' }),
      el('td', { class: 'actions' },
        el('button', {
          class: 'mini-btn', type: 'button', title: '3D 预览',
          text: '◲', onclick: () => navigate(`/package/${pkg.package_id}/3d`),
        }),
        el('button', {
          class: 'mini-btn', type: 'button', title: '编辑封装',
          text: '✎', onclick: () => navigate(`/package/${pkg.package_id}`),
        }),
        el('button', {
          class: 'mini-btn danger', type: 'button', title: '删除封装',
          text: '🗑', onclick: () => removePackage(pkg),
        })));
  }

  function renderTable() {
    const rows = filtered();
    tbody.textContent = '';
    for (const pkg of rows) tbody.append(rowFor(pkg));
    const withModel = state.packages.filter((pkg) => pkg.model_file).length;
    countText.textContent = `共 ${rows.length} / ${state.packages.length} 个封装 · ${withModel} 个已关联模型`;
  }

  function renderTypes() {
    typeStrip.textContent = '';
    typeStrip.append(el('span', { class: 'muted', text: '封装类型：' }));
    for (const type of state.packageTypes) {
      typeStrip.append(el('span', { class: 'chip' },
        type.pkg_type_name,
        el('button', {
          type: 'button', title: '删除封装类型', text: '×',
          onclick: () => removePackageType(type),
        })));
    }
    if (!state.packageTypes.length) {
      typeStrip.append(el('span', { class: 'muted', text: '还没有封装类型' }));
    }
    typeStrip.append(el('form', {
      class: 'inline-form small type-add',
      onsubmit: async (event) => {
        event.preventDefault();
        const form = event.target;
        const input = form.elements.namedItem('pkg_type_name');
        const name = input.value.trim();
        if (!name) return;
        try {
          await api('/api/package-types', { method: 'POST', json: { pkg_type_name: name } });
          input.value = '';
          await loadBasicData();
          renderTypes();
          if (importer) importer.refresh(models);
          toast('封装类型已添加');
        } catch (error) {
          toast(error.message, 'error');
        }
      },
    },
    el('input', { name: 'pkg_type_name', placeholder: '新增封装类型' }),
    el('button', { class: 'btn tiny', type: 'submit', text: '＋' })));

    // 同步类型筛选下拉
    typeFilterSelect.textContent = '';
    typeFilterSelect.append(el('option', { value: '', text: '全部封装类型' }));
    for (const type of state.packageTypes) {
      typeFilterSelect.append(el('option', { value: type.pkg_type_id, text: type.pkg_type_name }));
    }
    typeFilterSelect.value = typeFilter;
  }

  async function removePackage(pkg) {
    if (!confirm(`删除封装「${pkg.package_name}」？`)) return;
    try {
      await api(`/api/packages/${pkg.package_id}`, { method: 'DELETE' });
      await loadBasicData();
      renderTable();
      renderTypes();
      if (importer) importer.refresh(models);
      toast('封装已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  async function removePackageType(type) {
    if (!confirm(`删除封装类型「${type.pkg_type_name}」及其下所有封装？`)) return;
    try {
      await api(`/api/package-types/${type.pkg_type_id}`, { method: 'DELETE' });
      await loadBasicData();
      renderTable();
      renderTypes();
      if (importer) importer.refresh(models);
      toast('封装类型已删除');
    } catch (error) {
      toast(error.message, 'error');
    }
  }

  function toggleImporter() {
    if (importer) {
      importer = null;
      importMount.textContent = '';
      importButton.textContent = '从 3D 库导入';
      return;
    }
    importer = buildModelImporter({
      models,
      onImported: () => {
        renderTable();
        renderTypes();
      },
    });
    importMount.textContent = '';
    importMount.append(importer.element);
    importButton.textContent = '收起模型库';
  }

  const importButton = button('从 3D 库导入', toggleImporter, 'ghost');
  importButton.disabled = !models.length;

  const searchInput = el('input', {
    class: 'search', type: 'search', value: search,
    placeholder: '搜索：简称 / 完整封装名 / 模型文件 / 描述',
    autocomplete: 'off',
    oninput: (event) => {
      search = event.target.value;
      renderTable();
    },
  });
  const typeFilterSelect = el('select', { class: 'filter' });
  typeFilterSelect.addEventListener('change', (event) => {
    typeFilter = event.target.value;
    renderTable();
  });

  const table = el('div', { class: 'table-wrap' },
    el('table', { class: 'grid' },
      el('thead', {}, el('tr', {},
        el('th', { text: '类型' }),
        el('th', { text: '简称' }),
        el('th', { text: '完整封装名' }),
        el('th', { class: 'num', text: '引脚' }),
        el('th', { class: 'num', text: '间距 mm' }),
        el('th', { text: '3D 模型' }),
        el('th', { text: '描述' }),
        el('th', { class: 'actions-col', text: '操作' }))),
      tbody));

  renderTypes();
  renderTable();

  container.append(
    pageHeader('封装大全',
      '维护封装库；每个封装可关联 3D 模型，模型文件放在 static/models/',
      button('＋ 新增封装', () => navigate('/package/new'), 'primary'),
      importButton,
      button('管理元器件', () => navigate('/components'), 'ghost')),
    el('section', { class: 'card' },
      typeStrip,
      importMount,
      el('div', { class: 'toolbar' }, searchInput, typeFilterSelect, countText),
      state.packages.length
        ? table
        : el('p', { class: 'muted', text: '还没有封装。点「新增封装」，或把模型放进 static/models/ 后点「从 3D 库导入」。' })));

  if (query.get('import') === '1') toggleImporter();
}
