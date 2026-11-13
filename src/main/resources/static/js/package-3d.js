/* 封装 3D 预览：Three.js 动态加载，支持 GLB / glTF 与 OBJ。
 * Three.js 通过 index.html 的 importmap 指向 static/vendor/three/，缺失时这里给出明确提示。 */

import { api, el, pageHeader, button, navigate } from './core.js';

export async function renderPackage3d(container, packageId) {
  container.textContent = '';
  const pkg = await api(`/api/packages/${packageId}`);
  const modelFile = pkg.model_file;
  const format = String(pkg.model_format || '').toUpperCase() || extensionOf(modelFile);

  const stage = el('div', { class: 'viewer-stage' });
  const status = el('p', { class: 'viewer-status muted', text: '正在加载…' });
  const hint = el('p', { class: 'hint' });

  container.append(
    pageHeader(`封装 3D · ${pkg.package_name}`,
      [pkg.full_name,
        pkg.pin_count !== null && pkg.pin_count !== undefined ? `${pkg.pin_count} 引脚` : null,
        pkg.pitch !== null && pkg.pitch !== undefined ? `间距 ${pkg.pitch} mm` : null]
        .filter(Boolean).join(' · ') || '3D 预览',
      button('返回封装列表', () => navigate('/manage/packages'), 'ghost'),
      button('编辑封装', () => navigate(`/package/${pkg.package_id}`), 'ghost')),
    el('section', { class: 'card viewer-card' }, stage, status),
    hint);

  if (!modelFile) {
    status.textContent = '该封装还没有关联 3D 模型，先到编辑页选择模型文件。';
    hint.append('支持的格式：GLB / glTF（首选）、OBJ。模型文件放在 static/models/ 下。');
    return;
  }

  let THREE;
  let OrbitControls;
  let loaderModule;
  const missingVendor = await missingVendorFiles();
  try {
    THREE = await import('three');
    OrbitControls = (await import('three/addons/OrbitControls.js')).OrbitControls;
    loaderModule = format === 'OBJ'
      ? await import('three/addons/OBJLoader.js')
      : await import('three/addons/GLTFLoader.js');
  } catch (error) {
    const detail = error && error.message ? error.message : String(error);
    status.textContent = '未能加载 Three.js：' + detail;
    if (missingVendor.length) {
      hint.append('缺少文件：', el('span', { class: 'error-text', text: missingVendor.join('、') }));
    } else if (error && (error.name === 'SyntaxError' || /Unexpected end of input/i.test(detail))) {
      hint.append('vendor 文件存在但解析失败，几乎都是下载不完整（被截断）导致的。'
        + '请重新下载同版本的完整文件，重点检查 three.module.js、GLTFLoader.js、OBJLoader.js；'
        + '可以按 static/vendor/three/README.md 里的「完整性检查」跑一遍 node --check。');
    } else {
      hint.append('文件都在，但模块加载失败，请按 F12 查看控制台错误；也可以参考 static/vendor/three/README.md。');
    }
    console.error(error);
    return;
  }

  const canvas = el('canvas', { class: 'viewer-canvas' });
  stage.append(canvas);

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  } catch (error) {
    status.textContent = '当前浏览器无法创建 WebGL 上下文，不能显示 3D 模型。';
    return;
  }

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, 1, 0.01, 10000);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.screenSpacePanning = true;

  scene.add(new THREE.HemisphereLight(0xffffff, 0x445066, 2.0));
  const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
  keyLight.position.set(3, 5, 4);
  scene.add(keyLight);
  const fillLight = new THREE.DirectionalLight(0xffffff, 0.8);
  fillLight.position.set(-4, 2, -3);
  scene.add(fillLight);

  status.textContent = `正在加载模型 ${modelFile} …`;

  let root;
  try {
    const url = `/models/${encodeURIComponent(modelFile)}`;
    if (format === 'OBJ') {
      root = await new loaderModule.OBJLoader().loadAsync(url);
    } else {
      const gltf = await new loaderModule.GLTFLoader().loadAsync(url);
      root = gltf.scene || (gltf.scenes && gltf.scenes[0]);
    }
  } catch (error) {
    status.textContent = `模型加载失败：${error && error.message ? error.message : '文件不存在或格式不正确'}`;
    hint.append(`请求地址：/models/${modelFile}。检查文件是否在 static/models/ 下、格式是否与扩展名一致。`);
    return;
  }

  if (!root) {
    status.textContent = '模型文件没有可显示的网格。';
    return;
  }

  const box = new THREE.Box3().setFromObject(root);
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  if (!Number.isFinite(size.x) || size.length() === 0) {
    status.textContent = '模型尺寸为 0，无法预览。';
    return;
  }

  // 居中到原点，按包围盒调整相机距离
  root.position.sub(center);
  root.traverse((child) => {
    if (child.isMesh && !child.material) {
      child.material = new THREE.MeshStandardMaterial({ color: 0x9aa7b8, metalness: 0.15, roughness: 0.6 });
    }
  });
  scene.add(root);

  const maxDim = Math.max(size.x, size.y, size.z) || 1;
  const grid = new THREE.GridHelper(maxDim * 2.2, 20, 0x94a3b8, 0xcbd5e1);
  grid.position.y = -size.y / 2;
  if (grid.material) {
    grid.material.transparent = true;
    grid.material.opacity = 0.5;
  }
  scene.add(grid);

  camera.near = maxDim / 100;
  camera.far = maxDim * 200;
  const distance = maxDim * 2.1;
  camera.position.set(distance * 0.8, distance * 0.55, distance);
  controls.target.set(0, 0, 0);
  controls.update();

  function resize() {
    const width = stage.clientWidth || 640;
    const height = stage.clientHeight || 420;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }

  const observer = typeof ResizeObserver === 'function'
    ? new ResizeObserver(resize)
    : null;
  if (observer) observer.observe(stage);
  window.addEventListener('resize', resize);
  resize();

  // WASD 平移（OrbitControls 默认只处理鼠标与方向键）
  const forward = new THREE.Vector3();
  const right = new THREE.Vector3();
  const up = new THREE.Vector3(0, 1, 0);
  const movement = new THREE.Vector3();
  function onKeyDown(event) {
    if (!['KeyW', 'KeyA', 'KeyS', 'KeyD'].includes(event.code)) return;
    event.preventDefault();
    const step = maxDim * 0.03;
    camera.getWorldDirection(forward);
    forward.y = 0;
    if (forward.lengthSq() === 0) forward.set(0, 0, -1);
    forward.normalize();
    right.crossVectors(forward, up).normalize();
    movement.set(0, 0, 0);
    if (event.code === 'KeyW') movement.add(forward);
    if (event.code === 'KeyS') movement.sub(forward);
    if (event.code === 'KeyD') movement.add(right);
    if (event.code === 'KeyA') movement.sub(right);
    camera.position.addScaledVector(movement, step);
    controls.target.addScaledVector(movement, step);
    controls.update();
  }
  window.addEventListener('keydown', onKeyDown);

  let running = true;
  function frame() {
    if (!running) return;
    controls.update();
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
  frame();

  status.textContent = `${modelFile} · ${format || 'GLB'} · 左键旋转 / 滚轮缩放 / 右键平移 / WASD 平移`;
  hint.append('模型来自 static/models/；OBJ 未附带材质时使用默认灰白材质。');

  function cleanup() {
    running = false;
    window.removeEventListener('keydown', onKeyDown);
    window.removeEventListener('resize', resize);
    window.removeEventListener('hashchange', cleanup);
    if (observer) observer.disconnect();
    controls.dispose();
    scene.traverse((child) => {
      if (child.isMesh) {
        if (child.geometry) child.geometry.dispose();
        const materials = Array.isArray(child.material) ? child.material : [child.material];
        for (const material of materials) {
          if (material && typeof material.dispose === 'function') material.dispose();
        }
      }
    });
    renderer.dispose();
  }
  window.addEventListener('hashchange', cleanup, { once: true });
}

function extensionOf(fileName) {
  if (!fileName) return '';
  const dot = fileName.lastIndexOf('.');
  return dot < 0 ? '' : fileName.slice(dot + 1).toUpperCase();
}

/** 预检 vendor 文件，缺失时给出精确清单（不影响后续真实 import 的结果）。 */
async function missingVendorFiles() {
  const required = [
    '/vendor/three/three.module.js',
    '/vendor/three/three.core.js',
    '/vendor/three/GLTFLoader.js',
    '/vendor/three/OBJLoader.js',
    '/vendor/three/OrbitControls.js',
    '/vendor/three/BufferGeometryUtils.js',
    '/vendor/three/SkeletonUtils.js',
  ];
  const missing = [];
  for (const url of required) {
    try {
      const response = await fetch(url, { method: 'HEAD' });
      if (!response.ok) missing.push(url);
    } catch (error) {
      missing.push(url);
    }
  }
  return missing;
}
