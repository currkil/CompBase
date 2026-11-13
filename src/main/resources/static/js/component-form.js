/* 新增 / 编辑元器件：独立整页表单，新建时用 localStorage 记忆上次的分类、封装、位置。 */

import {
  state, api, el, pageHeader, button, toast, navigate, loadBasicData, numberOrNull,
} from './core.js';
import { fetchModels, buildModelImporter } from './model-import.js';

const PREFS_KEY = 'compbase.form.prefs';

function readPrefs() {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch (error) {
    return {};
  }
}

function writePrefs(payload) {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify({
      type_id: payload.type_id,
      subtype_id: payload.subtype_id,
      package_id: payload.package_id,
      location_id: payload.location_id,
    }));
  } catch (error) {
    // 隐私模式等情况下忽略
  }
}

export async function renderComponentForm(container, componentId) {
  await loadBasicData();
  const existing = componentId ? await api(`/api/components/${componentId}`) : null;
  const prefs = existing ? {} : readPrefs();
  const initial = existing || {
    type_id: prefs.type_id,
    subtype_id: prefs.subtype_id,
    package_id: prefs.package_id,
    location_id: prefs.location_id,
    stock_qty: 0,
    occupied_qty: 0,
    price: null,
  };

  const typeSelect = el('select', { name: 'type_id' });
  typeSelect.append(el('option', { value: '', text: '（未分类）' }));
  for (const type of state.types) {
    typeSelect.append(el('option', { value: type.type_id, text: type.type_name }));
  }
  typeSelect.value = initial.type_id === null || initial.type_id === undefined ? '' : String(initial.type_id);

  const subtypeSelect = el('select', { name: 'subtype_id' });
  function fillSubtypes(selected) {
    const typeId = typeSelect.value;
    const type = state.types.find((item) => String(item.type_id) === typeId);
    subtypeSelect.textContent = '';
    subtypeSelect.append(el('option', { value: '', text: type ? '（无子类型）' : '先选择类型' }));
    if (type && type.subtypes) {
      for (const subtype of type.subtypes) {
        subtypeSelect.append(el('option', { value: subtype.subtype_id, text: subtype.subtype_name }));
      }
    }
    subtypeSelect.value = selected === null || selected === undefined ? '' : String(selected);
  }
  fillSubtypes(initial.subtype_id);

  // ==================== 封装选择（搜索 + 详情 + 模型库导入） ====================

  const packageSearch = el('input', {
    class: 'search small', type: 'search', placeholder: '搜索封装（简称 / 完整名）',
    autocomplete: 'off',
  });
  const packageSelect = el('select', { name: 'package_id' });
  const packageInfo = el('div', { class: 'package-info' });
  const packageImportMount = el('div');
  const packageImportButton = button('从 3D 库导入', () => togglePackageImporter(), 'tiny ghost');
  let packageImporter = null;

  function selectedPackage() {
    return state.packages.find(
      (item) => String(item.package_id) === String(packageSelect.value));
  }

  function infoItem(label, value, extra) {
    return el('span', { class: 'package-info-item' },
      el('span', { class: 'muted', text: label + '：' }),
      el('strong', { text: value === null || value === undefined || value === '' ? '—' : String(value) }),
      extra || null);
  }

  function updatePackageInfo() {
    packageInfo.textContent = '';
    const pkg = selectedPackage();
    if (!pkg) {
      packageInfo.append(el('span', { class: 'muted', text: '未选择封装；可以选择、搜索，或从 3D 模型库导入。' }));
      return;
    }
    packageInfo.append(
      infoItem('完整名', pkg.full_name || '—'),
      infoItem('引脚', pkg.pin_count),
      infoItem('间距', pkg.pitch !== null && pkg.pitch !== undefined ? `${pkg.pitch} mm` : '—'),
      infoItem('模型', pkg.model_file || '未关联'),
      el('a', {
        href: `#/package/${pkg.package_id}`, class: 'link-btn',
        target: '_blank', rel: 'noopener', text: '编辑封装',
      }));
    if (pkg.model_file) {
      packageInfo.append(el('a', {
        href: `#/package/${pkg.package_id}/3d`, class: 'link-btn',
        target: '_blank', rel: 'noopener', text: '查看封装 3D',
      }));
    }
  }

  function rebuildPackageOptions(filter) {
    const keep = packageSelect.value;
    const keyword = (filter || '').trim().toLowerCase();
    packageSelect.textContent = '';
    packageSelect.append(el('option', { value: '', text: '（未指定）' }));
    const available = new Set(['']);
    for (const type of state.packageTypes) {
      const list = (type.packages || []).filter((pkg) => {
        if (!keyword) return true;
        return `${pkg.package_name} ${pkg.full_name || ''}`.toLowerCase().includes(keyword);
      });
      if (!list.length) continue;
      const group = el('optgroup', { label: type.pkg_type_name });
      for (const pkg of list) {
        group.append(el('option', {
          value: pkg.package_id,
          text: pkg.full_name ? `${pkg.package_name} · ${pkg.full_name}` : pkg.package_name,
        }));
        available.add(String(pkg.package_id));
      }
      packageSelect.append(group);
    }
    packageSelect.value = available.has(String(keep)) ? keep : '';
    updatePackageInfo();
  }

  function selectPackage(packageId) {
    packageSelect.value = String(packageId);
    if (packageSelect.value !== String(packageId)) {
      packageSearch.value = '';
      rebuildPackageOptions('');
      packageSelect.value = String(packageId);
    }
    updatePackageInfo();
    remember();
  }

  async function togglePackageImporter() {
    if (packageImporter) {
      packageImporter = null;
      packageImportMount.textContent = '';
      packageImportButton.textContent = '从 3D 库导入';
      return;
    }
    const models = await fetchModels();
    packageImporter = buildModelImporter({
      models,
      onImported: (result) => {
        const first = [...result.created, ...result.updated, ...result.existing][0];
        if (first) {
          packageSearch.value = '';
          rebuildPackageOptions('');
          selectPackage(first.package_id);
        }
        packageImportMount.textContent = '';
        packageImporter = null;
        packageImportButton.textContent = '从 3D 库导入';
      },
    });
    packageImportMount.textContent = '';
    packageImportMount.append(packageImporter.element);
    packageImportButton.textContent = '收起模型库';
  }

  packageSearch.addEventListener('input', () => rebuildPackageOptions(packageSearch.value));
  packageSelect.addEventListener('change', () => {
    updatePackageInfo();
    remember();
  });
  rebuildPackageOptions('');
  if (initial.package_id !== null && initial.package_id !== undefined) {
    selectPackage(initial.package_id);
  }

  const locationSelect = el('select', { name: 'location_id' });
  locationSelect.append(el('option', { value: '', text: '（未分配）' }));
  for (const zone of state.zones) {
    if (!zone.locations || !zone.locations.length) continue;
    const group = el('optgroup', { label: zone.zone_name });
    for (const location of zone.locations) {
      group.append(el('option', {
        value: location.location_id,
        text: location.label || `${zone.zone_name}-${location.location_code}`,
      }));
    }
    locationSelect.append(group);
  }
  locationSelect.value = initial.location_id === null || initial.location_id === undefined
    ? '' : String(initial.location_id);

  const valueInput = el('input', {
    name: 'value', type: 'text', placeholder: '例如 10kΩ、47pF、STM32F103C8T6',
    value: initial.value || '',
  });
  const stockInput = el('input', {
    name: 'stock_qty', type: 'number', min: '0', step: '1', required: true,
    value: initial.stock_qty ?? 0,
  });
  const occupiedInput = el('input', {
    name: 'occupied_qty', type: 'number', min: '0', step: '1', required: true,
    value: initial.occupied_qty ?? 0,
  });
  const priceInput = el('input', {
    name: 'price', type: 'number', min: '0', step: '0.0001', placeholder: '可留空',
    value: initial.price ?? '',
  });

  function remember() {
    if (existing) return;
    writePrefs({
      type_id: numberOrNull(typeSelect.value),
      subtype_id: numberOrNull(subtypeSelect.value),
      package_id: numberOrNull(packageSelect.value),
      location_id: numberOrNull(locationSelect.value),
    });
  }

  typeSelect.addEventListener('change', () => {
    fillSubtypes('');
    remember();
  });
  locationSelect.addEventListener('change', remember);
  const form = el('form', {
    class: 'form-grid',
    onsubmit: async (event) => {
      event.preventDefault();
      const payload = {
        type_id: numberOrNull(typeSelect.value),
        subtype_id: numberOrNull(subtypeSelect.value),
        value: valueInput.value.trim() || null,
        package_id: numberOrNull(packageSelect.value),
        location_id: numberOrNull(locationSelect.value),
        stock_qty: numberOrNull(stockInput.value) ?? 0,
        occupied_qty: numberOrNull(occupiedInput.value) ?? 0,
        price: numberOrNull(priceInput.value),
      };
      if (payload.occupied_qty > payload.stock_qty) {
        toast('占用数量不能大于库存数量', 'error');
        return;
      }
      try {
        if (existing) {
          await api(`/api/components/${existing.id}`, { method: 'PUT', json: payload });
        } else {
          await api('/api/components', { method: 'POST', json: payload });
          writePrefs(payload);
        }
        toast(existing ? '已更新' : '已添加');
        navigate('/components');
      } catch (error) {
        toast(error.message, 'error');
      }
    },
  },
  el('label', {}, '类型', typeSelect),
  el('label', {}, '子类型', subtypeSelect),
  el('label', { class: 'span-2' }, '值 / 型号', valueInput),
  el('label', { class: 'span-2' }, '封装',
    el('div', { class: 'package-picker' },
      el('div', { class: 'package-row' },
        packageSearch,
        packageSelect,
        el('div', { class: 'package-actions' },
          el('a', {
            class: 'btn tiny ghost', href: '#/package/new',
            target: '_blank', rel: 'noopener', text: '＋ 新建封装',
          }),
          packageImportButton)),
      packageInfo,
      packageImportMount)),
  el('label', {}, '位置', locationSelect),
  el('label', {}, '库存数量', stockInput),
  el('label', {}, '占用数量', occupiedInput),
  el('label', {}, '单价（元）', priceInput),
  el('div', { class: 'form-foot span-2' },
    button('取消', () => navigate('/components'), 'ghost'),
    el('button', { class: 'btn primary', type: 'submit', text: '保存' })));

  container.append(...[
    pageHeader(
      existing ? `编辑元器件 #${existing.id}` : '新增元器件',
      existing ? '修改后保存，库存与占用会整体覆盖' : '自动带出上次选择的类型 / 封装 / 位置，保存在本机浏览器',
      button('返回列表', () => navigate('/components'), 'ghost')),
    el('section', { class: 'card' }, form),
    existing
      ? null
      : el('p', { class: 'hint', text: '提示：类型、子类型、封装、位置会被记住，下次新增自动填好；库存与单价不会被记住。' }),
    el('p', { class: 'hint', text: '备注不在这里填写：回列表点「备注」即可编辑 Markdown 与上传图片。' }),
  ].filter(Boolean));
}
