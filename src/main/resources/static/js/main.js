/* 路由与启动：哈希路由决定渲染哪个整页视图。 */

import { $, el, parseHash } from './core.js';
import { renderComponents } from './components-view.js';
import { renderComponentForm } from './component-form.js';
import { renderManage } from './manage-view.js';
import { renderBomList, renderBomDetail } from './bom-view.js';
import { renderImport } from './import-view.js';
import { renderLogs } from './logs-view.js';
import { renderPackageForm } from './package-form.js';
import { renderPackage3d } from './package-3d.js';
import { renderPackagesView } from './packages-view.js';
import { initNotesDrawer, hideNotes, notesOpen } from './notes-drawer.js';

function setActiveNav(segments) {
  const first = segments[0] || 'components';
  let section = first;
  if (first === 'component') section = 'components';
  if (first === 'package') section = 'packages';
  for (const link of document.querySelectorAll('.nav-link')) {
    link.classList.toggle('active', link.dataset.section === section);
  }
}

function notFoundView() {
  return el('section', { class: 'card' },
    el('h2', { text: '页面不存在' }),
    el('p', { class: 'muted' }, '返回 ',
      el('a', { href: '#/components', text: '元器件列表' })));
}

async function renderRoute() {
  const { segments, query } = parseHash();
  setActiveNav(segments);
  hideNotes();
  const container = $('#view');
  container.textContent = '';
  window.scrollTo(0, 0);

  try {
    const first = segments[0] || 'components';
    if (first === 'components') {
      await renderComponents(container, query);
    } else if (first === 'component') {
      if (!segments[1] || segments[1] === 'new') {
        await renderComponentForm(container, null);
      } else {
        await renderComponentForm(container, segments[1]);
      }
    } else if (first === 'manage') {
      if (segments[1] === 'packages') {
        // 旧地址兼容：封装管理已升级为一级页面
        location.hash = '#/packages';
        return;
      }
      await renderManage(container, query);
    } else if (first === 'packages') {
      await renderPackagesView(container, query);
    } else if (first === 'package') {
      if (!segments[1] || segments[1] === 'new') {
        await renderPackageForm(container, null);
      } else if (segments[2] === '3d') {
        await renderPackage3d(container, segments[1]);
      } else {
        await renderPackageForm(container, segments[1]);
      }
    } else if (first === 'bom') {
      if (segments[1]) {
        await renderBomDetail(container, segments[1]);
      } else {
        await renderBomList(container);
      }
    } else if (first === 'import') {
      await renderImport(container);
    } else if (first === 'logs') {
      await renderLogs(container, query);
    } else {
      container.append(notFoundView());
    }
  } catch (error) {
    console.error(error);
    container.textContent = '';
    container.append(el('section', { class: 'card' },
      el('h2', { text: '加载失败' }),
      el('p', { class: 'error-text', text: error.message }),
      el('p', { class: 'muted' }, '可以返回 ',
        el('a', { href: '#/components', text: '元器件列表' }), ' 重试。')));
  }
}

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && notesOpen()) hideNotes();
});

initNotesDrawer();
window.addEventListener('hashchange', renderRoute);
renderRoute();
