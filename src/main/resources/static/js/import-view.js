/* 基础数据批量导入：选类型 → 选 CSV → 预览 → 确认导入。 */

import { api, el, pageHeader, button, badge, toast, navigate, loadBasicData } from './core.js';

const IMPORT_TYPES = [
  {
    key: 'types', label: '类型', columns: '类型名, 描述',
    sample: '电阻,固定电阻器\n电容,电容器',
  },
  {
    key: 'subtypes', label: '子类型', columns: '类型名, 子类型名, 描述',
    sample: '电容,瓷片电容,陶瓷介质\n电容,MLCC电容,多层陶瓷',
  },
  {
    key: 'package-types', label: '封装类型', columns: '封装类型名',
    sample: '直插\n贴片',
  },
  {
    key: 'packages', label: '封装',
    columns: '封装类型名, 具体封装名, 完整封装名, 引脚数, 引脚间距, 模型文件, 模型格式, 描述',
    sample: '贴片,0603,R_0603_1608Metric,2,0.8,sample_box.glb,GLB,0603 电阻封装\n直插,DIP-8,DIP-8_W7.62mm,8,2.54,sample_box.glb,GLB,8 脚直插',
  },
  {
    key: 'zones', label: '位置区', columns: '位置区名, 描述',
    sample: '3号袋,最小号自封袋\n飞机盒,批量/重型元件',
  },
  {
    key: 'locations', label: '位置', columns: '位置区名, 位置编号, 描述',
    sample: '3号袋,05,电阻区\n飞机盒,01,大模块',
  },
];

const STRATEGIES = [
  ['SKIP', 'SKIP：已存在则跳过（默认）'],
  ['UPDATE', 'UPDATE：已存在则更新描述'],
  ['INSERT_ALL', 'INSERT_ALL：不检查重复，全部插入'],
];

const ACTION_META = {
  insert: { label: '新增', cls: 'ok' },
  update: { label: '更新', cls: 'warn' },
  skip: { label: '跳过', cls: 'muted' },
  error: { label: '失败', cls: 'danger' },
};

export async function renderImport(container) {
  container.textContent = '';
  let selected = IMPORT_TYPES[0];
  let mappingFields = null;

  const typeSelect = el('select', { class: 'filter' });
  for (const type of IMPORT_TYPES) {
    typeSelect.append(el('option', { value: type.key, text: type.label }));
  }
  const strategySelect = el('select', { class: 'filter' });
  for (const [value, label] of STRATEGIES) {
    strategySelect.append(el('option', { value, text: label }));
  }
  const fileInput = el('input', {
    type: 'file',
    accept: '.csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const hint = el('p', { class: 'muted' });
  const mappingBox = el('div', { class: 'mapping-box', hidden: true });
  const resultBox = el('div', { class: 'import-result' });

  const previewButton = el('button', { class: 'btn primary', type: 'submit', text: '预览' });
  const confirmButton = button('确认导入', () => run(false), 'primary');
  confirmButton.hidden = true;

  function updateHint() {
    hint.textContent = `支持 CSV 与 xlsx（第一个工作表）。需要的列：${selected.columns}。示例：${selected.sample.split('\n')[0]}`;
  }
  updateHint();
  typeSelect.addEventListener('change', () => {
    selected = IMPORT_TYPES.find((type) => type.key === typeSelect.value) || IMPORT_TYPES[0];
    updateHint();
    mappingBox.hidden = true;
    mappingFields = null;
    confirmButton.hidden = true;
    resultBox.textContent = '';
  });

  function mappingParams() {
    if (!mappingFields) return {};
    const params = {};
    for (const [key, select] of Object.entries(mappingFields)) {
      if (select.value !== '') params[key] = select.value;
    }
    return params;
  }

  function renderMapping(payload) {
    mappingBox.textContent = '';
    mappingBox.hidden = false;
    mappingFields = {};
    for (const field of payload.fields || []) {
      const select = el('select', { name: field.key });
      select.append(el('option', { value: '', text: '（不指定）' }));
      (payload.headers || []).forEach((header, index) => {
        select.append(el('option', { value: String(index), text: `${index + 1}. ${header}` }));
      });
      if (payload.values && payload.values[field.key] !== undefined) {
        select.value = String(payload.values[field.key]);
      }
      mappingFields[field.key] = select;
      mappingBox.append(el('label', { class: 'mapping-field' },
        el('span', { text: field.label }),
        field.required ? el('span', { class: 'required', text: '*' }) : null,
        select));
    }
    mappingBox.append(el('div', { class: 'mapping-actions' },
      el('p', { class: 'muted', text: '表头无法自动识别，请手动指定列后重新预览。' }),
      button('按指定列预览', () => run(true), 'tiny primary')));
  }

  async function run(preview) {
    const file = fileInput.files && fileInput.files[0];
    if (!file) {
      toast('请选择 CSV 文件', 'error');
      return;
    }
    const params = new URLSearchParams({
      file_name: file.name,
      strategy: strategySelect.value,
      preview: preview ? 'true' : 'false',
    });
    for (const [key, value] of Object.entries(mappingParams())) params.set(key, value);

    previewButton.disabled = true;
    try {
      const result = await api(`/api/import/${selected.key}?${params.toString()}`, {
        method: 'POST',
        body: file,
      });
      mappingBox.hidden = true;
      renderResult(result, preview);
      if (!preview) {
        await loadBasicData();
        toast(`导入完成：成功 ${result.success}，跳过 ${result.skipped}，失败 ${result.failed}`);
      }
    } catch (error) {
      if (error.payload && error.payload.code === 'MAPPING_REQUIRED') {
        renderMapping(error.payload);
      } else {
        toast('导入失败：' + error.message, 'error');
      }
    } finally {
      previewButton.disabled = false;
    }
  }

  function renderResult(result, preview) {
    resultBox.textContent = '';
    confirmButton.hidden = !preview;

    resultBox.append(el('div', { class: 'bom-summary' },
      chip('总行数', result.total),
      chip('成功', result.success, 'ok'),
      chip('跳过', result.skipped, 'warn'),
      chip('失败', result.failed, 'danger')));

    if (preview) {
      resultBox.append(el('p', { class: 'muted', text: '以上为预览结果，数据尚未写入。确认无误后点「确认导入」。' }));
    }

    const tbody = el('tbody');
    for (const row of result.rows || []) {
      const meta = ACTION_META[row.action] || { label: row.action, cls: 'muted' };
      tbody.append(el('tr', {},
        el('td', { class: 'num', text: String(row.line) }),
        el('td', {}, badge(meta.label, meta.cls)),
        el('td', { class: 'mono', text: row.key || '—' }),
        el('td', { class: 'muted', text: row.message || '' })));
    }
    resultBox.append(el('div', { class: 'table-wrap' },
      el('table', { class: 'grid' },
        el('thead', {}, el('tr', {},
          el('th', { class: 'num', text: '行号' }),
          el('th', { text: '动作' }),
          el('th', { text: '数据' }),
          el('th', { text: '说明' }))),
        tbody)));

    if (!preview && result.errors && result.errors.length) {
      resultBox.append(el('div', { class: 'error-box' },
        el('h3', { text: '失败详情' }),
        el('ul', {}, ...result.errors.map((message) => el('li', { text: message })))));
    }
    if (!preview && result.log_id) {
      resultBox.append(el('p', { class: 'muted' }, '已写入导入日志 ',
        el('a', { href: '#/logs', text: '查看日志' })));
    }
  }

  function chip(label, value, cls = '') {
    return el('span', { class: 'summary-chip ' + cls },
      el('span', { class: 'summary-label', text: label }),
      el('strong', { text: String(value) }));
  }

  const form = el('form', {
    class: 'inline-form import-form',
    onsubmit: (event) => {
      event.preventDefault();
      run(true);
    },
  }, typeSelect, strategySelect, fileInput, previewButton, confirmButton);

  container.append(
    pageHeader('基础数据导入',
      '按依赖顺序导入：类型 → 子类型 → 封装类型 → 封装 → 位置区 → 位置',
      button('去数据管理', () => navigate('/manage'), 'ghost'),
      button('查看导入日志', () => navigate('/logs'), 'ghost')),
    el('section', { class: 'card' },
      el('h2', { class: 'card-title', text: '选择文件并预览' }),
      hint,
      form,
      mappingBox),
    resultBox,
    el('p', { class: 'hint', text: 'CSV 编码支持 UTF-8（含 BOM）与 GBK，xlsx 读取第一个工作表；第一行必须是表头（前面有标题行时会自动跳过）。重复判断依据：类型名、类型+子类型、封装类型名、封装类型+封装名、位置区名、位置区+编号。' }));
}
