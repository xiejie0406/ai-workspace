import { CONFIG } from './config.js';
import { approvedResource } from './resources.js';
import { SECTIONS, SECTION_NAMES, SECTION_STATES, SectionSession, mergeSections } from './sections.js';
const $ = id => document.getElementById(id);
const session = new SectionSession();
let connected = false, running = false, extracting = false, extractionCancelled = false, picked = null, activeSection = 'params', confirmationRevision = 0;
async function call(action, extra = {}) {
    const response = await chrome.runtime.sendMessage({ action, ...extra });
    if (!response?.ok) { const error = new Error(response?.error || '连接中断'); error.code = response?.errorCode; throw error; }
    return response.data;
}
function show(name) {
    for (const id of ['ready', 'settings', 'pick', 'preview', 'result', 'advanced']) $(id + 'View').hidden = id !== name;
    $('captureActions').hidden = !['ready', 'preview'].includes(name);
}
function setStatus(message) { $('status').textContent = message; }
function action(id, fn) { $(id).onclick = async () => { try { await fn(); } catch (error) { setStatus(error.message); } }; }
function element(tag, text, className) { const item = document.createElement(tag); if (text !== undefined) item.textContent = text; if (className) item.className = className; return item; }
function button(text, fn, disabled = false) { const item = element('button', text); item.type = 'button'; item.disabled = disabled; item.onclick = async () => { try { await fn(); } catch (error) { setStatus(error.message); } }; return item; }
function aggregate() { return session.ready() ? mergeSections(session.selected().map(id => session.sections[id].result)) : null; }
function updateActions() {
    const ready = session.ready();
    $('capture').disabled = !ready || !connected || running || extracting;
    $('permission').disabled = !ready || running;
    $('preview').disabled = extracting || running;
    $('cancelExtract').hidden = !extracting;
    const blocked = session.selected().filter(id => !['ready', 'missing'].includes(session.sections[id].status));
    $('saveHint').textContent = blocked.length ? '保存前请提取：' + blocked.map(id => SECTION_NAMES[id]).join('、') : session.missing().length ? '有缺失分区，确认清单后可保存部分结果' : '所选分区已提取，核对后统一保存';
}
function previewContent(id, result) {
    const content = element('div', undefined, 'section-preview');
    content.append(element('p', '来源范围：当前已加载页面；加入 ' + session.sections[id].rules.filter(rule => rule.mode === 'include').length + ' 处，排除 ' + session.sections[id].rules.filter(rule => rule.mode === 'exclude').length + ' 处', 'minor'));
    if (!result) { content.append(element('p', '本区尚无有效预览，请提取本区。', 'help')); return content; }
    if (id === 'basic') {
        const dl = element('dl');
        for (const [label, value] of [['标题', result.fields.title], ['SKU', result.fields.sku], ['规格', result.fields.specification], ['页面价格', result.fields.priceText], ['店铺', result.fields.shop]]) dl.append(element('dt', label), element('dd', value || '未知'));
        content.append(dl);
    } else if (id === 'params') {
        const table = element('table'); table.className = 'parameters';
        const header = element('tr'); for (const label of ['保留', '参数', '页面原值']) header.append(element('th', label)); table.append(header);
        const names = new Map(); for (const row of result.parameterRows || []) names.set(row.name, (names.get(row.name) || 0) + 1);
        for (const row of result.parameterRows || []) {
            const tr = element('tr'); const checkCell = element('td'); const check = element('input'); check.type = 'checkbox'; check.checked = !row.excluded; check.setAttribute('aria-label', '保留参数 ' + (row.name || '未识别名称'));
            check.onchange = () => { try { session.excludeRow(row.sourceId, !check.checked); drawScopes(); setStatus('只调整规格参数范围，请重新提取本区'); } catch (error) { check.checked = !row.excluded; setStatus(error.message); } };
            checkCell.append(check); const name = element('td', (row.name || '未知参数') + (names.get(row.name) > 1 ? '（重名）' : ''));
            const value = element('td', row.value || '未知'); value.append(element('small', '原文：' + row.rawText));
            if (row.unit) value.append(element('small', '单位：' + row.unit)); if (row.unknownReason) value.append(element('small', row.unknownReason));
            tr.append(checkCell, name, value); table.append(tr);
        }
        content.append(table);
        if (session.sections.params.excludedRowIds.length) content.append(button('恢复本区排除的参数行', () => { session.clearExcludedRows(); session.invalidate('params'); drawScopes(); }));
    } else if (['main', 'gallery', 'detailImages'].includes(id)) {
        const grid = element('div', undefined, 'image-grid');
        for (const image of result.images || []) { const figure = element('figure'); const img = document.createElement('img'); img.alt = SECTION_NAMES[id] + '候选'; try { img.src = approvedResource(image.url, CONFIG).href; } catch { img.alt = '图片来源未获准'; } figure.append(img, element('figcaption', '候选 ' + (image.order + 1))); grid.append(figure); }
        content.append(grid, element('p', '缩略图是预览；实际保存图片以后台确认数量为准。', 'minor'));
    } else { for (const block of result.blocks || []) content.append(element('p', block.text, 'sample')); content.append(element('p', '区内已加载图片：' + (result.embeddedImageCount || 0) + ' 张', 'minor')); }
    if (result.missingReason) content.append(element('p', result.missingReason, 'notice'));
    return content;
}
function scopeRow(id, name, simple = false) {
    const state = session.sections[id]; const row = element('div', undefined, 'scope-section');
    const heading = element('div', undefined, 'scope-heading'); const label = element('label', undefined, 'scope-label');
    const check = element('input'); check.type = 'checkbox'; check.checked = state.selected; check.dataset.scope = id;
    check.onchange = () => { try { session.select(id, check.checked); drawScopes(); } catch (error) { check.checked = true; setStatus(error.message); } };
    label.append(check, element('strong', name)); heading.append(label);
    if (simple) { row.append(heading, element('small', '仅仅读取页面已加载内容；勾选状态与首页一致')); return row; }
    const result = state.result; const summary = result ? id === 'params' ? result.parameterRows.filter(item => !item.excluded).length + ' 个参数' : result.images.length ? result.images.length + ' 张图片候选' : result.blocks.length + ' 块内容' : '';
    heading.append(element('span', state.selected ? SECTION_STATES[state.status] : '本次不采集', 'section-status'));
    row.append(heading); if (summary) row.append(element('p', summary, 'minor'));
    const actions = element('div', undefined, 'section-buttons');
    const expand = button(state.expanded ? '收起' : '展开', () => { state.expanded = !state.expanded; drawScopes(); }); expand.dataset.sectionExpand = id;
    const extract = button('提取本区', () => extractOne(id), !state.selected || state.status === 'reading' || running || extracting); extract.dataset.sectionExtract = id;
    expand.setAttribute('aria-label', (state.expanded ? '收起' : '展开') + name); expand.setAttribute('aria-expanded', String(state.expanded)); extract.setAttribute('aria-label', '提取' + name);
    actions.append(expand, extract,
        button('调整区域', () => { activeSection = id; $('pickedSection').value = id; clearPick(); show('pick'); }));
    row.append(actions); if (state.expanded) row.append(previewContent(id, result)); return row;
}
function drawScopes() {
    const focused = document.activeElement;
    const focusKey = ['scope', 'sectionExpand', 'sectionExtract'].find(key => focused?.dataset?.[key]);
    const focusValue = focusKey ? focused.dataset[focusKey] : null;
    const focusContainer = focused?.closest('#settingsScopes') ? 'settingsScopes' : 'scopeList';
    $('scopeList').replaceChildren(...SECTIONS.map(([id, name]) => scopeRow(id, name)));
    $('settingsScopes').replaceChildren(...SECTIONS.map(([id, name]) => scopeRow(id, name, true)));
    $('scopeCount').textContent = '已选 ' + session.selected().length + ' 项';
    if (session.page) { $('title').textContent = session.page.fields.title; $('spec').textContent = 'SKU ' + (session.page.fields.sku || '未识别，待核对') + ' · ' + (session.page.fields.specification || '规格未显示'); }
    updateActions();
    if (focusKey) {
        const target = [...$(focusContainer).querySelectorAll('[data-' + focusKey.replace(/[A-Z]/g, value => '-' + value.toLowerCase()) + ']')].find(item => item.dataset[focusKey] === focusValue);
        if (target && !target.disabled) target.focus();
    }
}
async function extractOne(id) {
    const token = session.begin(id); drawScopes();
    try {
        const result = await call('section.extract', { sectionId: id, rangeRevision: String(token.rangeRevision), selection: session.selection() });
        if (session.accept(id, token, result)) setStatus(SECTION_NAMES[id] + '已提取，尚未保存');
    } catch (error) { if (!session.fail(id, token)) return; if (/参数行位置或原文|参数排除缺少/.test(error.message)) session.clearExcludedRows(); if (/页面|规格|授权|权限|登录|验证/.test(error.message)) { session.invalidateAll(); await call('preview.clear').catch(() => {}); } throw error; }
    finally { drawScopes(); }
}
function clearPick() { picked = null; $('includePick').disabled = true; $('excludePick').disabled = true; $('pickedLabel').textContent = '尚未选择区域'; $('pickedSample').textContent = '开始点选后，点击网页中的一个业务区域'; }
function applyPick(mode) {
    if (!picked || !picked.sourceId || !picked.selector) throw new Error('请重新点选唯一的商品区域');
    const id = $('pickedSection').value;
    const name = $('customName').value.trim() || SECTION_NAMES[id] + '自选区域';
    session.setRule(id, { selector: picked.selector, sourceId: picked.sourceId, name, mode });
    $('pickFeedback').hidden = false; $('pickFeedback').textContent = (mode === 'include' ? '已加入' : '已排除') + '：' + name + '。仅' + SECTION_NAMES[id] + '需重新提取；其他分区保留。' + (id === 'params' ? '参数范围变化后请重新核对参数行排除。' : '');
    clearPick(); drawScopes(); setStatus($('pickFeedback').textContent);
}
function render(job = {}) {
    let message = job.message || '等待保存结果';
    if (Number.isSafeInteger(job.saved) && Number.isSafeInteger(job.total) && job.saved >= 0 && job.total >= job.saved) {
        const progress = '图片 ' + job.saved + '/' + job.total + ' 已保存'; if (!message.includes(progress)) message += '；' + progress;
    }
    setStatus(message); running = !!job.running;
    $('failures').replaceChildren(...(job.failures || []).map(failure => element('li', failure.imageId + '：' + failure.error)));
    updateActions();
    if (job.captureId) { show('result'); $('resultTitle').textContent = running ? '正在保存' : job.error || job.needsVerification ? '结果待确认' : job.failures?.length || job.result?.status === 'PARTIAL' ? '部分保存' : '保存结果'; $('resultSummary').textContent = message; }
}
async function startCapture(missingConfirmed) {
    if (!session.ready()) throw new Error('请提取所有所选分区并核对预览');
    const expected = { documentId: session.page.documentId, fingerprint: session.page.fingerprint,
        selectionRevision: String(session.revision), confirmationRevision: String(++confirmationRevision), missingConfirmed,
        sections: session.selected().map(id => ({ sectionId: id, rangeRevision: String(session.sections[id].rangeRevision), resultRevision: String(session.sections[id].resultRevision) })) };
    const selection = session.selection(); running = true; updateActions(); show('result'); $('resultTitle').textContent = '正在保存'; $('resultSummary').textContent = '等待后台确认内容和图片';
    try { render(await call('capture', { groups: ['MAIN', 'GALLERY', 'DETAIL'], selection, expected })); }
    catch (error) { $('resultTitle').textContent = '保存未确认'; $('resultSummary').textContent = error.message; throw error; }
    finally { running = false; updateActions(); }
}
let pendingSave = null;
action('capture', async () => {
    if (!session.ready()) throw new Error('请提取所有所选分区');
    const existing = await call('lookup');
    if (existing.deleted) throw new Error('该商品当前不可保存，请联系管理员核对原记录');
    const missing = session.missing();
    pendingSave = { revision: session.revision, missingConfirmed: missing.length > 0 };
    if (existing.exists || missing.length) {
        $('confirmTitle').textContent = existing.exists ? '保存为新的采集版本' : '保存部分结果';
        $('confirmMessage').textContent = (existing.exists ? '此 SKU 已存在，本次新增版本，旧版本保留。' : '') + (missing.length ? '\n缺失：\n' + missing.join('\n') + '\n继续将保存为部分结果。' : '');
        $('captureDialog').showModal();
    } else await startCapture(false);
});
action('confirmCapture', async () => { if (!pendingSave || pendingSave.revision !== session.revision || !session.ready()) throw new Error('确认期间内容已变化，请重新核对'); $('captureDialog').close(); await startCapture(pendingSave.missingConfirmed); });
action('viewExisting', async () => { $('captureDialog').close(); await call('library'); });
action('cancelCapture', () => { pendingSave = null; $('captureDialog').close(); $('capture').focus(); });
action('preview', async () => {
    extracting = true; extractionCancelled = false; drawScopes();
    try { for (const id of session.selected()) { if (extractionCancelled) break; await extractOne(id); } }
    finally { extracting = false; drawScopes(); }
});
action('cancelExtract', () => { extractionCancelled = true; session.cancel(); drawScopes(); setStatus('已取消尚未完成的提取，已有效分区保留'); });
action('connect', async () => { await call('connect'); connected = true; updateActions(); setStatus('已连接 Aden，可核对并统一保存'); });
action('pagePermission', async () => {
    // 必须直接由按钮点击发起请求，保留 Chrome 要求的用户手势。
    const granted = await chrome.permissions.request({ origins: ['https://item.jd.com/*'] });
    setStatus(granted ? '已授权京东商品页，请重新提取所选内容。' : '未授权京东商品页，暂时无法读取；可再次点击授权并允许访问。');
});
action('permission', async () => {
    const preview = aggregate(); if (!preview) return;
    const origins = [...new Set(preview.images.flatMap(image => { try { return [approvedResource(image.url, CONFIG).origin + '/*']; } catch { return []; } }))];
    const granted = !origins.length || await chrome.permissions.request({ origins }); setStatus(granted ? '已授权当前候选图片域名' : '未授权的图片将记录为缺失');
});
action('settingsButton', () => show('settings')); action('settingsBack', () => show('ready'));
action('pickButton', () => { clearPick(); show('pick'); }); action('settingsPick', () => { clearPick(); show('pick'); });
action('pickBack', async () => { await call('pick.stop'); clearPick(); show('ready'); });
action('startPick', async () => { clearPick(); await call('pick.start'); setStatus('请在网页点选一块商品信息，Esc 可退出'); });
action('includePick', () => applyPick('include')); action('excludePick', () => applyPick('exclude'));
action('resetButton', () => { if (!confirm('恢复默认全选并清除本次加入、排除范围？受影响分区需要重新提取。')) return; session.reset(); drawScopes(); setStatus('已恢复默认，受影响分区请重新提取'); });
action('previewBack', () => show('ready')); action('again', () => { show('ready'); drawScopes(); });
action('stop', async () => { await call('stop'); setStatus('已请求停止后续图片，等待在途确认'); });
action('refresh', async () => { const data = await call('status'); if (data?.local) render({ ...data.local, message: '服务端状态：' + (data.server?.status || '未知') }); else setStatus('没有可查询的保存记录'); });
action('retry', async () => render(await call('retry'))); action('library', () => call('library'));
action('advancedButton', () => show('advanced')); action('advancedBack', () => show('ready'));
action('pending', async () => { const rows = await call('spool.list'); $('pendingList').replaceChildren(...rows.map(row => { const option = element('option', row.title + ' · 到期 ' + new Date(row.expiresAt).toLocaleString()); option.value = row.captureId; return option; })); setStatus('当前账号有 ' + rows.length + ' 个待恢复捕获'); });
action('resume', async () => { if (!$('pendingList').value) throw new Error('请选择待恢复记录'); render(await call('spool.resume', { captureId: $('pendingList').value })); });
action('clearSpool', async () => { if (confirm('清理本机尚未完成的离线暂存？后台已保存结果保持不变。')) { await call('spool.clear'); $('pendingList').replaceChildren(); setStatus('本机暂存已清理'); } });
chrome.runtime.onMessage.addListener(message => {
    if (message.event === 'connection.lost') { connected = false; updateActions(); setStatus(message.reason || 'Aden 连接断开，请重新连接'); }
    if (message.event === 'section.invalidated' && SECTION_NAMES[message.sectionId]) { if (message.clearParameterExclusions) session.clearExcludedRows(); session.invalidate(message.sectionId); drawScopes(); setStatus(message.reason || '本区需重新提取'); }
    if (message.event === 'progress') render(message.job);
    if (message.event === 'pick.result') {
        picked = message.result;
        if (!SECTION_NAMES[picked.key] && picked.key !== 'custom') { clearPick(); return; }
        if (SECTION_NAMES[picked.key]) activeSection = picked.key;
        $('pickedSection').value = activeSection; $('pickedLabel').textContent = SECTION_NAMES[picked.key] || '自选区域';
        $('pickedSample').textContent = (picked.sample || '图片区域') + ' · 图片 ' + (picked.imageCount || 0) + ' 张';
        $('customName').value = SECTION_NAMES[picked.key] || '自选区域'; $('includePick').disabled = false; $('excludePick').disabled = false; show('pick');
    }
    if (message.event === 'pick.cancelled') { clearPick(); setStatus('已退出点选，尚未确认的选择已丢弃'); }
    if (message.event === 'preview.invalidated' || message.event === 'revoked') {
        session.invalidateAll(); extractionCancelled = true; clearPick(); pendingSave = null;
        if (message.event === 'revoked') { connected = false; $('pendingList').replaceChildren(); }
        drawScopes(); setStatus(message.reason || '登录已变化，请重新连接并提取');
    }
});
drawScopes(); show('ready'); call('local').then(saved => { if (saved) setStatus(saved.message || '请连接 Aden'); }).catch(error => setStatus(error.message));
window.addEventListener('keydown', event => { if (event.key === 'Escape' && !$('pickView').hidden) { clearPick(); void call('pick.stop').catch(() => {}); show('ready'); setStatus('已退出点选，尚未确认的选择已丢弃'); } });
