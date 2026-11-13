/* 从 3D 模型库导入封装：模型文件名自动解析出简称 / 引脚数 / 间距。 */

import { state, api, el, button, toast, loadBasicData } from './core.js';

export async function fetchModels() {
  try {
    return await api('/api/models');
  } catch (error) {
    return [];
  }
}

export function modelSummary(model) {
  const parts = [];
  if (model.pin_count !== null && model.pin_count !== undefined) parts.push(`${model.pin_count} 脚`);
  if (model.pitch !== null && model.pitch !== undefined) parts.push(`间距 ${model.pitch}mm`);
  return parts.join(' · ');
}

/**
 * 构建导入面板。
 * @param {{models: Array, onImported?: Function}} options
 * @returns {{element: HTMLElement, refresh: Function}}
 */
export function buildModelImporter({ models, onImported }) {
  let modelList = models || [];
  let selected = new Set();

  const typeSelect = el('select', { class: 'filter' });
  function fillTypes() {
    typeSelect.textContent = '';
    typeSelect.append(el('option', { value: '', text: '（先选择封装类型）' }));
    for (const type of state.packageTypes) {
      typeSelect.append(el('option', { value: type.pkg_type_id, text: type.pkg_type_name }));
    }
  }
  fillTypes();

  const listBox = el('div', { class: 'model-import-list' });
  const importButton = button('导入选中（0）', () => doImport(), 'primary');
  importButton.disabled = true;

  function importedNames() {
    return new Set(state.packages
      .map((pkg) => pkg.model_file)
      .filter(Boolean));
  }

  function updateButton() {
    importButton.textContent = `导入选中（${selected.size}）`;
    importButton.disabled = selected.size === 0;
  }

  function renderList() {
    listBox.textContent = '';
    selected = new Set();
    if (!modelList.length) {
      listBox.append(el('p', { class: 'muted' },
        'static/models/ 下还没有模型文件。把 .glb / .gltf / .obj 放进去后刷新页面即可。'));
      updateButton();
      return;
    }
    const imported = importedNames();
    for (const model of modelList) {
      const exists = imported.has(model.name);
      const checkbox = el('input', { type: 'checkbox', value: model.name });
      if (exists) checkbox.disabled = true;
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) selected.add(model.name);
        else selected.delete(model.name);
        updateButton();
      });
      listBox.append(el('label', { class: 'model-import-item' + (exists ? ' is-disabled' : '') },
        checkbox,
        el('span', { class: 'model-import-name' },
          el('span', { class: 'cell-main', text: model.name }),
          el('span', { class: 'cell-sub', text: `${model.package_name} · ${modelSummary(model) || '未解析到引脚信息'}` })),
        exists
          ? el('span', { class: 'badge muted', text: '已导入' })
          : el('span', { class: 'badge info', text: model.format })));
    }
    updateButton();
  }

  async function doImport() {
    const pkgTypeId = Number(typeSelect.value);
    if (!pkgTypeId) {
      toast('请先选择封装类型', 'error');
      return;
    }
    importButton.disabled = true;
    try {
      const result = await api('/api/packages/from-models', {
        method: 'POST',
        json: { pkg_type_id: pkgTypeId, model_files: [...selected] },
      });
      const failed = result.failed || [];
      let message = `导入完成：新增 ${result.created.length}，补齐 ${result.updated.length}，已存在 ${result.existing.length}`;
      if (failed.length) message += `，失败 ${failed.length}`;
      toast(message, failed.length ? 'error' : 'ok');
      await loadBasicData();
      if (onImported) onImported(result);
      renderList();
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      updateButton();
    }
  }

  const element = el('div', { class: 'import-panel' },
    el('div', { class: 'import-head' },
      el('label', { class: 'import-target' }, '导入到封装类型', typeSelect),
      el('span', { class: 'muted', text: '按模型文件名自动解析：简称、完整名、引脚数、间距；解析不到的字段留空。' })),
    listBox,
    el('div', { class: 'import-actions' }, importButton));

  renderList();
  return {
    element,
    refresh(nextModels) {
      modelList = nextModels || modelList;
      fillTypes();
      renderList();
    },
  };
}
