/* 新增 / 编辑封装：整页表单，含 KiCad 完整名、引脚数、间距与 3D 模型关联。 */

import {
  state, api, el, pageHeader, button, toast, navigate, loadBasicData, numberOrNull,
} from './core.js';

const MODEL_FORMATS = [
  ['', '按扩展名自动判断'],
  ['GLB', 'GLB'],
  ['GLTF', 'glTF'],
  ['OBJ', 'OBJ'],
];

export async function renderPackageForm(container, packageId) {
  container.textContent = '';
  await loadBasicData();
  const models = await api('/api/models').catch(() => []);
  const existing = packageId ? await api(`/api/packages/${packageId}`) : null;
  const hashQuery = new URLSearchParams((location.hash.split('?')[1] || ''));
  const initial = existing || {
    pkg_type_id: hashQuery.get('pkg_type_id'),
    package_name: '',
    full_name: '',
    pin_count: null,
    pitch: null,
    model_file: '',
    model_format: '',
    description: '',
  };

  const pkgTypeSelect = el('select', { name: 'pkg_type_id', required: true });
  pkgTypeSelect.append(el('option', { value: '', text: '（选择封装类型）' }));
  for (const type of state.packageTypes) {
    pkgTypeSelect.append(el('option', { value: type.pkg_type_id, text: type.pkg_type_name }));
  }
  pkgTypeSelect.value = initial.pkg_type_id === null || initial.pkg_type_id === undefined
    ? '' : String(initial.pkg_type_id);

  const nameInput = el('input', {
    name: 'package_name', type: 'text', required: true,
    placeholder: '简称，例如 DIP-8 / 0603', value: initial.package_name || '',
  });
  const fullNameInput = el('input', {
    name: 'full_name', type: 'text',
    placeholder: 'KiCad 原名，例如 DIP-8_W7.62mm', value: initial.full_name || '',
  });
  const pinInput = el('input', {
    name: 'pin_count', type: 'number', min: '0', step: '1', placeholder: '可空',
    value: initial.pin_count ?? '',
  });
  const pitchInput = el('input', {
    name: 'pitch', type: 'number', min: '0', step: '0.01', placeholder: '可空，单位 mm',
    value: initial.pitch ?? '',
  });
  const modelInput = el('input', {
    name: 'model_file', type: 'text', list: 'model-options',
    placeholder: '例如 DIP-8_W7.62mm.glb（放在 static/models/）',
    value: initial.model_file || '',
  });
  const modelList = el('datalist', { id: 'model-options' });
  for (const model of models) {
    modelList.append(el('option', {
      value: model.name,
      label: `${model.format} · ${Math.max(1, Math.round((model.size || 0) / 1024))} KB`,
    }));
  }
  const formatSelect = el('select', { name: 'model_format' });
  for (const [value, label] of MODEL_FORMATS) {
    formatSelect.append(el('option', { value, text: label }));
  }
  formatSelect.value = (initial.model_format || '').toUpperCase();
  const descriptionInput = el('textarea', {
    name: 'description', rows: '3',
    placeholder: '可选：封装说明、焊接注意事项等',
  });
  descriptionInput.value = initial.description || '';

  // 输入模型文件名时如果扩展名明确，自动带出格式（仍可手动改）
  modelInput.addEventListener('change', () => {
    const value = modelInput.value.trim().toLowerCase();
    if (!value) return;
    if (value.endsWith('.glb') || value.endsWith('.gltf')) formatSelect.value = 'GLB';
    else if (value.endsWith('.obj')) formatSelect.value = 'OBJ';
  });

  const modelHint = el('p', { class: 'hint' });
  function updateModelHint() {
    const name = modelInput.value.trim();
    if (!name) {
      modelHint.textContent = '未关联模型时，3D 预览页会提示先选择模型文件。';
      return;
    }
    const known = models.find((model) => model.name === name);
    modelHint.textContent = known
      ? `文件大小 ${Math.max(1, Math.round(known.size / 1024))} KB，预览地址 /models/${name}`
      : '注意：static/models/ 下没有找到这个文件，保存后 3D 预览会加载失败。';
  }
  modelInput.addEventListener('input', updateModelHint);
  updateModelHint();

  const form = el('form', {
    class: 'form-grid',
    onsubmit: async (event) => {
      event.preventDefault();
      const pkgTypeId = numberOrNull(pkgTypeSelect.value);
      const name = nameInput.value.trim();
      if (!pkgTypeId) {
        toast('请选择封装类型', 'error');
        return;
      }
      if (!name) {
        toast('请填写封装简称', 'error');
        return;
      }
      const pinCount = numberOrNull(pinInput.value);
      const pitch = numberOrNull(pitchInput.value);
      if (pinCount !== null && (pinCount < 0 || !Number.isInteger(pinCount))) {
        toast('引脚数必须是非负整数', 'error');
        return;
      }
      if (pitch !== null && pitch < 0) {
        toast('引脚间距不能为负数', 'error');
        return;
      }
      const payload = {
        pkg_type_id: pkgTypeId,
        package_name: name,
        full_name: fullNameInput.value.trim() || null,
        pin_count: pinCount,
        pitch,
        model_file: modelInput.value.trim() || null,
        model_format: formatSelect.value || null,
        description: descriptionInput.value.trim() || null,
      };
      try {
        if (existing) {
          await api(`/api/packages/${existing.package_id}`, { method: 'PUT', json: payload });
        } else {
          await api('/api/packages', { method: 'POST', json: payload });
        }
        toast(existing ? '封装已更新' : '封装已添加');
        navigate('/manage/packages');
      } catch (error) {
        toast(error.message, 'error');
      }
    },
  },
  el('label', {}, '封装类型', pkgTypeSelect),
  el('label', {}, '简称 package_name', nameInput),
  el('label', {}, '完整封装名 full_name', fullNameInput),
  el('label', {}, '引脚数 pin_count', pinInput),
  el('label', {}, '引脚间距 pitch（mm）', pitchInput),
  el('label', {}, '3D 模型文件 model_file', modelInput, modelList),
  el('label', {}, '模型格式 model_format', formatSelect),
  el('label', { class: 'span-2' }, '描述', descriptionInput),
  el('div', { class: 'form-foot span-2' },
    button('取消', () => navigate('/manage/packages'), 'ghost'),
    el('button', { class: 'btn primary', type: 'submit', text: '保存' })));

  container.append(...[
    pageHeader(
      existing ? `编辑封装 #${existing.package_id}` : '新增封装',
      '模型文件放在 static/models/，数据库只存文件名；GLB 优先，OBJ 也可',
      button('返回封装列表', () => navigate('/manage/packages'), 'ghost'),
      existing ? button('3D 预览', () => navigate(`/package/${existing.package_id}/3d`), 'ghost') : null),
    el('section', { class: 'card' }, form),
    modelHint,
    el('p', { class: 'hint' }, '可用模型：',
      models.length
        ? el('span', { text: models.map((model) => model.name).join('、') })
        : el('span', { text: '暂无（把 .glb / .obj 放进 src/main/resources/static/models/ 后刷新）' })),
  ].filter(Boolean));
}
