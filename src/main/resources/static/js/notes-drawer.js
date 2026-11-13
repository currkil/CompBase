/* 备注抽屉：从元器件列表打开，编辑 notes/comp_{id}.md 与图片。 */

import { $, api, el, toast, renderMarkdown } from './core.js';

let current = null;
let previewing = false;

export function initNotesDrawer() {
  $('#drawer-close').addEventListener('click', hideNotes);
  $('#btn-preview').addEventListener('click', () => setPreview(!previewing));
  $('#btn-save-note').addEventListener('click', saveNote);
  $('#note-image').addEventListener('change', async (event) => {
    const files = Array.from(event.target.files || []);
    event.target.value = '';
    await uploadImages(files);
  });
}

export async function openNotes(component) {
  current = component;
  $('#drawer-title').textContent = `备注 · ${component.value || '未命名'}`;
  const parts = [component.type_name, component.subtype_name].filter(Boolean);
  $('#drawer-sub').textContent = `#${component.id}` + (parts.length ? ' · ' + parts.join(' / ') : '');
  $('#note-editor').value = '';
  setPreview(false);
  $('#drawer').hidden = false;
  document.body.classList.add('no-scroll');

  try {
    const content = await api(`/notes/comp_${component.id}.md`);
    if (current && current.id === component.id) $('#note-editor').value = content;
  } catch (error) {
    // 备注文件不存在时保持空白
  }
  await loadImages(component.id);
}

export function hideNotes() {
  current = null;
  $('#drawer').hidden = true;
  document.body.classList.remove('no-scroll');
}

export function notesOpen() {
  return !$('#drawer').hidden;
}

async function saveNote() {
  if (!current) return;
  try {
    await api(`/notes/comp_${current.id}.md`, { method: 'PUT', body: $('#note-editor').value });
    toast('备注已保存');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function setPreview(on) {
  previewing = on;
  const editor = $('#note-editor');
  const preview = $('#note-preview');
  if (on) {
    preview.innerHTML = renderMarkdown(editor.value);
    preview.hidden = false;
    editor.hidden = true;
    $('#btn-preview').textContent = '编辑';
  } else {
    preview.hidden = true;
    editor.hidden = false;
    $('#btn-preview').textContent = '预览';
  }
}

async function loadImages(componentId) {
  const container = $('#note-images');
  container.textContent = '';
  let images = [];
  try {
    images = await api('/notes/images');
  } catch (error) {
    return;
  }
  const own = images.filter((image) => image.name.startsWith(`comp_${componentId}_`));
  for (const image of own) {
    container.append(el('div', { class: 'note-thumb' },
      el('img', {
        src: `${image.url}?t=${image.modified}`,
        alt: image.name,
        title: '点击插入到备注',
        onclick: () => insertImage(image),
      }),
      el('div', { class: 'note-thumb-actions' },
        el('button', { type: 'button', text: '插入', onclick: () => insertImage(image) }),
        el('button', {
          class: 'danger', type: 'button', text: '删除', onclick: () => deleteImage(image),
        }))));
  }
}

function insertImage(image) {
  const editor = $('#note-editor');
  const snippet = `\n![${image.name}](${image.url})\n`;
  const start = editor.selectionStart ?? editor.value.length;
  const end = editor.selectionEnd ?? editor.value.length;
  editor.value = editor.value.slice(0, start) + snippet + editor.value.slice(end);
  const caret = start + snippet.length;
  editor.focus();
  editor.selectionStart = caret;
  editor.selectionEnd = caret;
  if (previewing) setPreview(true);
}

async function uploadImages(files) {
  if (!current || !files.length) return;
  for (const file of files) {
    const extension = (file.name.split('.').pop() || 'jpg').toLowerCase()
      .replace(/[^a-z0-9]/g, '') || 'jpg';
    const stamp = Date.now() + Math.floor(Math.random() * 1000);
    const name = `comp_${current.id}_${stamp}.${extension}`;
    try {
      await api(`/notes/images/${name}`, { method: 'PUT', body: file });
      insertImage({ name, url: `/notes/images/${name}` });
    } catch (error) {
      toast('图片上传失败：' + error.message, 'error');
    }
  }
  await loadImages(current.id);
  toast('图片已上传，记得保存备注');
}

async function deleteImage(image) {
  if (!confirm(`删除图片 ${image.name}？`)) return;
  try {
    await api(image.url, { method: 'DELETE' });
    if (current) await loadImages(current.id);
    toast('图片已删除');
  } catch (error) {
    toast(error.message, 'error');
  }
}
