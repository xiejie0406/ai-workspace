import { CONFIG } from './config.js';
import { extractProduct } from './parser.js';
import { startPicking, stopPicking } from './picker.js';
import { approvedResource, downloadImage } from './resources.js';
import { CaptureSpool, IndexedDbStore, scrubMetadata } from './spool.js';
import { SECTIONS, MAX_REGION_RULES, mergeSections } from './sections.js';
import { watchProductIdentity } from './page-watch.js';
const sectionResults = new Map();
let sectionPage = null;
let sectionResultRevision = 0;
let previewEpoch = 0;
const latestSectionRequest = new Map();
function invalidateSections(reason = '页面或权限已变化，请重新提取') {
    previewEpoch++; latestSectionRequest.clear(); sectionResults.clear(); sectionPage = null;
    chrome.runtime.sendMessage({ event: 'preview.invalidated', reason }).catch(() => {});
}
async function fingerprintHash(value) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
    return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
const spool = new CaptureSpool(new IndexedDbStore());
let identity = null;
const AUTH_ERRORS = new Set(['AUTH_REQUIRED', 'SESSION_REVOKED', 'UNAUTHENTICATED', 'FORBIDDEN']);
async function clearPrivateState() {
    identity = null; job = null; stop = true;
    connectionProof = null; proofSequence = 0n;
    invalidateSections('登录或权限已失效，请重新连接并提取');
    await spool.clear();
    await chrome.storage.session.set({ job: null });
    chrome.runtime.sendMessage({ event: 'revoked' }).catch(() => {});
}
let port;
let connectionProof = null;
let proofSequence = 0n;
let helloPending = null;
const pending = new Map();
let job = null;
let stop = false;
let pickingTabId = null;
const restored = chrome.storage.session.get('job').then(({ job: saved }) => {
    if (saved)
        job = { ...saved, running: false, needsVerification: true, message: '上次采集尚需服务端确认，请连接后查询结果' };
});
async function native(type, payload = {}, retryExpiredProof = true) {
    if (type !== 'hello' && (!connectionProof || Date.parse(connectionProof.expiresAt) - Date.now() < 5000)) {
        const previousIdentity = identity ? identity.principalId + ':' + identity.workspaceId : null;
        if (!helloPending) helloPending = authenticate().finally(() => { helloPending = null; });
        await helloPending;
        if (previousIdentity && previousIdentity !== identity?.principalId + ':' + identity?.workspaceId) throw Object.assign(new Error('账号或业务范围已变化，请重新核对保存结果'), {code:'SESSION_REVOKED'});
    }
    if (type === 'hello')
        payload = { extensionId: chrome.runtime.id };
    if (!port) {
        port = chrome.runtime.connectNative(CONFIG.host);
        port.onMessage.addListener(r => { const p = pending.get(r.messageId); if (p) {
            clearTimeout(p.timer);
            pending.delete(r.messageId);
            if (r.ok) p.resolve(r.data);
            else {
                const error = new Error(r.error?.message || 'Aden 拒绝请求');
                error.code = r.error?.code;
                if (AUTH_ERRORS.has(error.code)) {
                    clearPrivateState().finally(() => p.reject(error));
                } else p.reject(error);
            }
        } });
        port.onDisconnect.addListener(() => { const message = chrome.runtime.lastError?.message || 'Aden 连接断开'; port = null; connectionProof = null; proofSequence = 0n; chrome.runtime.sendMessage({event:'connection.lost',reason:message}).catch(() => {}); for (const p of pending.values()) {
            clearTimeout(p.timer);
            p.reject(new Error(message));
        } pending.clear(); });
    }
    const messageId = crypto.randomUUID();
    const request = { protocolVersion: 1, messageId, type, payload };
    if (type !== 'hello') Object.assign(request, { nonce: connectionProof.nonce, sequence: String(++proofSequence), expiresAt: connectionProof.expiresAt });
    if (new TextEncoder().encode(JSON.stringify(request)).length > 256 * 1024) throw new Error('本次内容超过消息大小上限，请缩小提取范围后重新保存');
    let result;
    try {
        result = await new Promise((resolve, reject) => { const timer = setTimeout(() => { pending.delete(messageId); reject(new Error('Aden 确认超时，结果尚未确认，请查询状态')); }, 60000); pending.set(messageId, { resolve, reject, timer }); port.postMessage(request); });
    } catch (error) {
        if (['COLLECTOR_PROOF_EXPIRED', 'COLLECTOR_SEQUENCE_REJECTED'].includes(error.code)) connectionProof = null;
        // 过期拒绝发生在桥分派之前，允许一次重新配对；不重试结果未知的业务操作。
        if (error.code === 'COLLECTOR_PROOF_EXPIRED' && type !== 'hello' && retryExpiredProof) {
            const previousIdentity = identity ? identity.principalId + ':' + identity.workspaceId : null;
            await authenticate();
            if (previousIdentity && previousIdentity !== identity?.principalId + ':' + identity?.workspaceId) throw Object.assign(new Error('账号或业务范围已变化，请重新核对保存结果'), {code:'SESSION_REVOKED'});
            return native(type, payload, false);
        }
        throw error;
    }
    if (type === 'hello') {
        if (!result || result.proofRequired !== true || typeof result.nonce !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(result.nonce)
            || typeof result.expiresAt !== 'string' || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now()
            || Date.parse(result.expiresAt) > Date.now() + 125000) throw new Error('Aden 连接协议需升级，请更新安装版后重新连接');
        connectionProof = { nonce: result.nonce, expiresAt: result.expiresAt }; proofSequence = 0n;
    }
    return result;
}
async function authenticate() {
    const connection = await native('hello');
    const next = { principalId: String(connection.principalId || ''), workspaceId: connection.workspaceId };
    if (!next.principalId || !next.workspaceId) throw new Error('Aden 未返回已认证的账号标识，请升级桌面端');
    if ((identity && (identity.principalId !== next.principalId || identity.workspaceId !== next.workspaceId)) ||
        (job?.captureId && (job.principalId !== next.principalId || job.workspaceId !== next.workspaceId))) {
        job = null; invalidateSections('账号已变化，请重新提取'); await chrome.storage.session.set({ job: null });
        chrome.runtime.sendMessage({ event: 'revoked' }).catch(() => {});
    }
    await spool.bind(next);
    identity = next;
    return connection;
}
async function status(update) { job = { ...job, ...update }; await chrome.storage.session.set({ job }); chrome.runtime.sendMessage({ event: 'progress', job }).catch(() => { }); }
async function current() { const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true }); if (!tab?.id)
    throw new Error('没有当前标签页'); return tab; }
async function executeOnPage(injection) {
    try { return await chrome.scripting.executeScript(injection); }
    catch (error) {
        if (/Cannot access contents|manifest must request permission|Missing host permission|Cannot access a chrome:|Cannot access a chrome-extension:/i.test(error?.message || ''))
            throw Object.assign(new Error('尚未授权读取商品页，请先打开京东商品详情页，再点击“授权京东商品页”并允许访问，然后重新提取。'), { code: 'PAGE_PERMISSION_REQUIRED' });
        throw error;
    }
}
async function parse(tabId, customRules = [], options = {}) {
    const [r] = await executeOnPage({ target: { tabId }, func: extractProduct, args: [{ fixtureOrigin: CONFIG.fixtureOrigin, returnErrors: true, customRules, ...options }] });
    if (!r?.result) throw new Error('当前页授权失效，请点击工具栏重新授权');
    if (r.result.error) {
        const message = typeof r.result.error.message === 'string' ? r.result.error.message.slice(0, 300) : '页面商品结构解析失败，请反馈页面模板';
        throw new Error(message);
    }
    if (options.parameterRowContext && options.parameterRowContext.documentId !== r.documentId) throw new Error('参数排除所属页面或规格已变化，请重新提取所有所选分区');
    return { ...r.result, documentId: r.documentId };
}
async function ensurePage(tabId, before) { const after = await parse(tabId); if (after.documentId !== before.documentId || after.fingerprint !== before.fingerprint)
    throw new Error('页面或规格已经变化，请重新采集'); }
async function cleanupConfirmed(captureId, failures) {
    if (failures.length) return '';
    try { await spool.remove(identity, captureId); return ''; }
    catch { return '后端已确认保存；本机暂存清理失败，请稍后清理本机暂存'; }
}
async function saveImages(images, captureId, generation, tabId, captured, cachedOnly = false) {
    let totalBytes = 0;
    const failures = [];
    for (const img of images) {
        try {
            if (stop)
                throw new Error('用户停止了资源保存');
            const stored = (await spool.read(identity, captureId)).find(row => row.kind === 'asset' && row.imageId === img.imageId);
            let resource = stored;
            if (resource) {
                if (resource.documentId !== captured.documentId) throw new Error('暂存页面关联不一致');
                const digest = await crypto.subtle.digest('SHA-256', resource.bytes);
                const hash = [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, '0')).join('');
                if (hash !== resource.sha256) { await spool.remove(identity, captureId, img.imageId); throw new Error('暂存文件摘要校验失败，请重新采集'); }
            } else {
                if (cachedOnly) throw new Error('此图片没有离线字节，请回原页重试或重新采集');
                await ensurePage(tabId, captured);
                const url = approvedResource(img.url, CONFIG);
                if (!(CONFIG.fixtureOrigin && url.origin === CONFIG.fixtureOrigin) && !await chrome.permissions.contains({ origins: [`${url.origin}/*`] })) throw new Error('未授权图片域名，请先点击“授权本页图片域名”');
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 30000);
                try { resource = await downloadImage(img.url, CONFIG, controller.signal); }
                finally { clearTimeout(timer); }
                await ensurePage(tabId, captured);
                await spool.putAsset(identity, captureId, img.imageId, resource, captured.documentId);
            }
            totalBytes += resource.bytes.length;
            if (totalBytes > CONFIG.maxTotalBytes)
                throw new Error('超过本次图片总大小上限');
            for (let offset = 0; offset < resource.bytes.length; offset += 128 * 1024) {
                if (stop)
                    throw new Error('用户停止了资源保存');
                const chunk = resource.bytes.slice(offset, offset + 128 * 1024);
                let binary = '';
                for (const byte of chunk)
                    binary += String.fromCharCode(byte);
                await native('asset.chunk', { captureId, imageId: img.imageId, generation, offset, totalSize: resource.bytes.length, sha256: resource.sha256, mimeType: resource.mimeType, dataBase64: btoa(binary) });
            }
            await spool.remove(identity, captureId, img.imageId);
            await status({ saved: job.saved + 1, message: `内容已保存；图片 ${job.saved + 1}/${job.total} 已保存` });
        }
        catch (error) {
            if (AUTH_ERRORS.has(error.code)) throw error;
            failures.push({ imageId: img.imageId, error: error.message });
            await status({ failures: [...failures] });
            if (/页面或规格|HTTP (403|429)|验证/.test(error.message))
                stop = true;
        }
    }
    return failures;
}
async function retryImages() {
    if (job?.running)
        throw new Error('采集正在进行');
    if (!job?.context || !job.captureId)
        throw new Error('原页面捕获上下文已丢失，请重新采集');
    const connection = await authenticate();
    if (!job) throw new Error('身份已变化，旧捕获已清理');
    if (connection.workspaceId !== job.workspaceId)
        throw new Error('工作空间已变化，不能在新空间重试旧捕获');
    const server = await native('capture.status', { captureId: job.captureId });
    if (server.generation !== job.generation)
        throw new Error('商品保存代次已变化，请重新采集');
    const { tabId, captured, payload } = job.context;
    await ensurePage(tabId, captured);
    const savedIds = new Set((server.images || []).filter(i => i.status === 'SAVED').map(i => i.imageId));
    const remaining = payload.images.filter(i => !savedIds.has(i.imageId));
    if (!remaining.length) {
        await status({ running: false, message: '服务端确认图片均已保存', failures: [] });
        return job;
    }
    stop = false;
    await status({ running: true, saved: savedIds.size, failures: [], message: `正在重试 ${remaining.length} 张未保存图片` });
    try {
        const failures = await saveImages(remaining, job.captureId, job.generation, tabId, captured);
        const result = await native('capture.commit', { captureId: job.captureId, generation: job.generation, failures });
        const cleanupWarning = await cleanupConfirmed(job.captureId, failures);
        await status({ running: false, result, failures, cleanupWarning, message: cleanupWarning || (failures.length ? '部分保存，仍有图片失败' : '重试完成；选定图片已保存，资源清单已追加版本') });
    }
    catch (error) {
        await status({ running: false, message: `重试结果待确认：${error.message}`, needsVerification: true });
        throw error;
    }
    return job;
}
function selectionOf(input, legacyGroups) {
    const defaults = { basic: true, params: true, intro: true, detail: true, main: true, gallery: true, detailImages: true };
    if (!input) return { scope: { ...defaults, main: legacyGroups.includes('MAIN'), gallery: legacyGroups.includes('GALLERY'), detailImages: legacyGroups.includes('DETAIL') }, customRules: [], excludedImageIds: [], regionRules: [], excludedRowIds: [], parameterRowContext: null };
    if (typeof input !== 'object' || !input.scope || typeof input.scope !== 'object') throw new Error('采集范围无效');
    const scope = {};
    for (const key of Object.keys(defaults)) scope[key] = input.scope[key] === true;
    if (!Object.values(scope).some(Boolean)) throw new Error('至少保留一个分区');
    const customRules = Array.isArray(input.customRules) ? input.customRules.filter(rule => rule && rule.enabled === true).slice(0, 5).map(rule => ({ name: String(rule.name || '').slice(0, 40), selector: String(rule.selector || '').slice(0, 300) })) : [];
    const excludedImageIds = Array.isArray(input.excludedImageIds) ? input.excludedImageIds.filter(id => typeof id === 'string').slice(0, 50) : [];
    if (input.regionRules !== undefined && !Array.isArray(input.regionRules)) throw new Error('区域规则无效');
    if (input.regionRules?.length > MAX_REGION_RULES) throw new Error('本次最多设置 100 个范围，请移除已有范围后重试');
    const regionRules = (input.regionRules || []).map(rule => {
        if (!rule || !Object.hasOwn(defaults, rule.sectionId) || !['include', 'exclude'].includes(rule.mode)
            || typeof rule.selector !== 'string' || rule.selector.length > 300 || typeof rule.sourceId !== 'string') throw new Error('区域规则无效');
        return { sectionId: rule.sectionId, selector: rule.selector, sourceId: rule.sourceId, name: String(rule.name || '自选区域').slice(0, 80), mode: rule.mode };
    });
    if (SECTIONS.some(([id]) => regionRules.filter(rule => rule.sectionId === id).length > 20)) throw new Error('本区最多设置 20 个范围');
    const excludedRowIds = input.excludedRowIds || [];
    if (!Array.isArray(excludedRowIds) || excludedRowIds.length > 1000 || new Set(excludedRowIds).size !== excludedRowIds.length || excludedRowIds.some(id => typeof id !== 'string' || !id || id.length > 128)) throw new Error('参数排除标识无效');
    let parameterRowContext = null;
    if (excludedRowIds.length) {
        const context = input.parameterRowContext;
        if (!context || typeof context.documentId !== 'string' || !context.documentId || context.documentId.length > 128 || typeof context.fingerprint !== 'string' || !Array.isArray(context.rows) || context.rows.length > 1000
            || context.rows.some(row => !row || typeof row.sourceId !== 'string' || !row.sourceId || row.sourceId.length > 128 || typeof row.rawText !== 'string' || row.rawText.length > 4000 || typeof row.name !== 'string' || row.name.length > 200 || typeof row.value !== 'string' || row.value.length > 2000)
            || new Set(context.rows.map(row => row.sourceId)).size !== context.rows.length || excludedRowIds.some(id => !context.rows.some(row => row.sourceId === id))) throw new Error('参数排除缺少已核对预览，请重新提取并核对参数');
        parameterRowContext = { documentId: context.documentId, fingerprint: context.fingerprint,
            rows: context.rows.map(({sourceId, rawText, name, value}) => ({sourceId, rawText, name, value})) };
    }
    return { scope, customRules, excludedImageIds, regionRules, excludedRowIds, parameterRowContext };
}
const decimalRevision = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value);
const sectionOptions = (id, selection) => ({ sectionId: id, regionRules: selection.regionRules.filter(rule => rule.sectionId === id), excludedRowIds: id === 'params' ? selection.excludedRowIds : [], parameterRowContext: id === 'params' ? selection.parameterRowContext : null });
async function parseSection(tabId, options, isCurrent) {
    try { return await parse(tabId, [], options); }
    catch (error) {
        if (isCurrent() && options.parameterRowContext && /参数行位置或原文|参数排除缺少/.test(error.message)) {
            sectionResults.delete('params');
            chrome.runtime.sendMessage({event:'section.invalidated',sectionId:'params',clearParameterExclusions:true,reason:error.message}).catch(() => {});
        }
        if (isCurrent() && options.parameterRowContext && /所属页面或规格/.test(error.message)) invalidateSections(error.message);
        throw error;
    }
}
async function extractSection(request) {
    const selection = selectionOf(request.selection, ['MAIN', 'GALLERY', 'DETAIL']);
    if (!SECTIONS.some(([id]) => id === request.sectionId) || !selection.scope[request.sectionId] || !decimalRevision(request.rangeRevision)) throw new Error('分区或范围版本无效');
    const options = sectionOptions(request.sectionId, selection);
    if (options.parameterRowContext) {
        const prior = sectionResults.get('params')?.result;
        const context = options.parameterRowContext;
        if (!prior || prior.documentId !== context.documentId || prior.fingerprint !== context.fingerprint
            || JSON.stringify((prior.parameterRows || []).map(({sourceId,rawText,name,value}) => ({sourceId,rawText,name,value}))) !== JSON.stringify(context.rows)) throw new Error('参数排除缺少已核对预览，请重新提取并核对参数');
    }
    const epoch = previewEpoch;
    const requestId = (latestSectionRequest.get(request.sectionId) || 0) + 1;
    latestSectionRequest.set(request.sectionId, requestId);
    const tab = await current();
    const captured = await parseSection(tab.id, options, () => epoch === previewEpoch && requestId === latestSectionRequest.get(request.sectionId));
    if (epoch !== previewEpoch || requestId !== latestSectionRequest.get(request.sectionId)) throw new Error('本次提取已取消或过期，请重新提取');
    if (sectionPage && (sectionPage.tabId !== tab.id || sectionPage.documentId !== captured.documentId || sectionPage.fingerprint !== captured.fingerprint)) {
        invalidateSections(); throw new Error('页面或规格已变化，请重新提取所有所选分区');
    }
    const newPage = !sectionPage;
    sectionPage = { tabId: tab.id, documentId: captured.documentId, fingerprint: captured.fingerprint };
    const result = { ...captured, sectionId: request.sectionId, rangeRevision: request.rangeRevision,
        resultRevision: String(++sectionResultRevision), pageFingerprint: await fingerprintHash(captured.fingerprint) };
    if (epoch !== previewEpoch || requestId !== latestSectionRequest.get(request.sectionId)) throw new Error('本次提取已取消或过期，请重新提取');
    sectionResults.set(request.sectionId, { result, options, rangeRevision: request.rangeRevision });
    if (newPage) await executeOnPage({ target:{tabId:tab.id}, func:watchProductIdentity,
        args:[{documentId:captured.documentId, fingerprint:captured.fingerprint, fixture:!!CONFIG.fixtureOrigin}] });
    return result;
}
function selectedProduct(captured, selection) {
    const { scope } = selection;
    const fields = { ...captured.fields };
    if (!scope.basic) for (const key of ['price', 'priceText', 'shop']) delete fields[key];
    if (!scope.params) delete fields.parameters;
    const blocks = captured.blocks.filter(block => block.type === 'title' || block.type === 'parameters' && scope.params || block.type === 'introduction' && scope.intro || block.type === 'description' && scope.detail || block.type.startsWith('custom:'));
    const groups = new Set([scope.main && 'MAIN', scope.gallery && 'GALLERY', scope.detailImages && 'DETAIL'].filter(Boolean));
    return { ...captured, fields, blocks, images: captured.images.filter(image => groups.has(image.group) && !selection.excludedImageIds.includes(image.imageId)) };
}
const contentSnapshot = ({ fields, blocks, images }) => JSON.stringify({ fields, blocks, images });
async function capture(groups, selectionInput, expected) {
    if (!Array.isArray(groups) || groups.some(group => !['MAIN', 'GALLERY', 'DETAIL'].includes(group)))
        throw new Error('图片分组参数无效');
    if (job?.running)
        throw new Error('正在采集，请等待或停止');
    const tab = await current();
    const selection = selectionOf(selectionInput, groups);
    const captured = await parse(tab.id, selection.customRules);
    if (expected && (expected.fingerprint !== captured.fingerprint || expected.documentId !== captured.documentId)) {
        invalidateSections(); throw new Error('商品页面或规格已变化，请重新提取并核对预览');
    }
    if (expected?.content && expected.content !== contentSnapshot(selectedProduct(captured, selection)))
        throw new Error('页面已加载内容发生变化，请重新提取并核对预览');
    const connection = await authenticate();
    const captureId = crypto.randomUUID();
    stop = false;
    let selected = selectedProduct(captured, selection);
    if (expected?.sections) {
        if (!sectionPage) { invalidateSections('扩展预览会话已失效，请重新提取所有所选分区'); throw new Error('扩展预览会话已失效，请重新提取所有所选分区'); }
        const selectedIds = SECTIONS.map(([id]) => id).filter(id => selection.scope[id]);
        if (!decimalRevision(expected.selectionRevision) || !decimalRevision(expected.confirmationRevision) || !Array.isArray(expected.sections)
            || expected.sections.length !== selectedIds.length || new Set(expected.sections.map(row => row.sectionId)).size !== selectedIds.length) throw new Error('确认分区或版本无效');
        const valid = [];
        for (const id of selectedIds) {
            const proof = expected.sections.find(row => row.sectionId === id);
            const cached = sectionResults.get(id);
            if (!proof || !cached || !sectionPage || sectionPage.tabId !== tab.id || cached.result.documentId !== captured.documentId || cached.result.fingerprint !== captured.fingerprint
                || proof.rangeRevision !== cached.rangeRevision || proof.resultRevision !== cached.result.resultRevision
                || JSON.stringify(cached.options) !== JSON.stringify(sectionOptions(id, selection))) {
                sectionResults.delete(id); chrome.runtime.sendMessage({event:'section.invalidated',sectionId:id,reason:'本区预览已失效，请重新提取'}).catch(() => {});
                throw new Error('分区预览已失效，请重新提取：' + id);
            }
            if (cached.result.missingReason && expected.missingConfirmed !== true) throw new Error('请明确确认缺失分区后再保存');
            const fresh = await parseSection(tab.id, cached.options, () => sectionResults.get(id) === cached && sectionPage?.documentId === captured.documentId);
            if (fresh.documentId !== cached.result.documentId || fresh.fingerprint !== cached.result.fingerprint) {
                invalidateSections(); throw new Error('页面或规格已变化，请重新提取所有所选分区');
            }
            if (contentSnapshot(fresh) !== contentSnapshot(cached.result) || JSON.stringify(fresh.parameterRows || []) !== JSON.stringify(cached.result.parameterRows || [])) {
                sectionResults.delete(id); chrome.runtime.sendMessage({event:'section.invalidated',sectionId:id,reason:'本区已加载内容发生变化，请重新提取'}).catch(() => {});
                throw new Error('该分区已加载内容变化，请重新提取：' + id);
            }
            valid.push(cached.result);
        }
        selected = mergeSections(valid);
        if (valid.reduce((count, result) => count + result.sourceIds.length, 0) > 3000) throw new Error('所选分区来源总量超限，请缩小本次范围');
        selected.extraction = {
            protocolVersion: 1, documentId: captured.documentId, pageFingerprint: await fingerprintHash(captured.fingerprint),
            selectionRevision: expected.selectionRevision, confirmationRevision: expected.confirmationRevision, selectedSections: selectedIds,
            sections: valid.map(result => ({ sectionId: result.sectionId, documentId: result.documentId, pageFingerprint: result.pageFingerprint,
                selectionRevision: expected.selectionRevision, rangeRevision: result.rangeRevision, extractedRangeRevision: result.rangeRevision,
                resultRevision: result.resultRevision, sourceIds: [...new Set(result.sourceIds || [])],
                ...(result.missingReason ? { missingReason: result.missingReason, missingConfirmed: true } : {}) })),
            parameterRows: selected.parameterRows,
            ranges: selection.regionRules.filter(rule => selectedIds.includes(rule.sectionId)).map(({ sectionId, sourceId, name, mode }) => ({ sectionId, sourceId, name, mode })),
        };
        delete selected.parameterRows; delete selected.sourceIds;
    }
    const selectedImages = selected.images;
    const images = selectedImages.slice(0, CONFIG.maxImages);
    const excludedByChoice = Math.max(0, captured.images.length - selectedImages.length);
    const excludedByLimit = Math.max(0, selectedImages.length - images.length);
    const payload = scrubMetadata({ ...selected, captureId, images, completeness: { ...selected.completeness, selectedScope: selection.scope, excludedByChoice, excludedByLimit } });
    delete payload.fingerprint;
    job = null;
    await status({ captureId, principalId: identity.principalId, workspaceId: identity.workspaceId, running: true, saved: 0, total: images.length, excludedByChoice, excludedByLimit, failures: [], message: '正在保存商品内容', context: { tabId: tab.id, captured, payload } });
    try {
        await spool.putCapture(identity, captureId, payload, { tabId: tab.id, captured });
        await ensurePage(tab.id, captured);
        const saved = await native('capture.prepare', payload);
        await status({ generation: saved.generation, message: '商品内容已保存，正在保存图片', contentSaved: true, workspaceId: connection?.workspaceId });
        const failures = await saveImages(images, captureId, saved.generation, tab.id, captured);
        const result = await native('capture.commit', { captureId, generation: saved.generation, failures });
        const cleanupWarning = await cleanupConfirmed(captureId, failures);
        await status({ running: false, result, cleanupWarning, message: cleanupWarning || ((failures.length || result.status === 'PARTIAL' ? '部分保存，请查看内容或图片缺失清单' : '本次选定内容已保存') + `；主动排除 ${excludedByChoice} 张，超限排除 ${excludedByLimit} 张。仅代表已加载范围。`) });
    }
    catch (error) {
        await status({ running: false, message: `保存结果未完全确认：${error.message}`, error: true });
        throw error;
    }
}
async function resumeOffline(captureId) {
    if (job?.running) throw new Error('当前采集尚未结束');
    await authenticate();
    const rows = await spool.read(identity, captureId);
    const record = rows.find(row => row.kind === 'capture');
    if (!record) throw new Error('暂存不存在或已超过 24 小时，请重新采集');
    // prepare 幂等地确认服务器可访问性、Workspace 和捕获权限，然后查询已保存资源。
    const prepared = await native('capture.prepare', record.payload);
    const server = await native('capture.status', { captureId });
    const savedIds = new Set((server.images || []).filter(i => i.status === 'SAVED').map(i => i.imageId));
    const remaining = record.payload.images.filter(i => !savedIds.has(i.imageId));
    const { tabId, captured } = record.context;
    job = { captureId, principalId: identity.principalId, workspaceId: identity.workspaceId, generation: prepared.generation, context: { tabId, captured, payload: record.payload }, total: record.payload.images.length, saved: savedIds.size, failures: [] };
    stop = false;
    await status({ running: true, contentSaved: true, message: '服务端已确认，正在恢复离线字节' });
    try {
        const failures = await saveImages(remaining, captureId, prepared.generation, tabId, captured, true);
        const result = await native('capture.commit', { captureId, generation: prepared.generation, failures });
        const cleanupWarning = await cleanupConfirmed(captureId, failures);
        await status({ running: false, result, failures, cleanupWarning, message: cleanupWarning || (failures.length ? '离线字节已恢复，仍有未暂存图片需回原页重采' : '离线暂存已全部保存到采集库') });
        return job;
    } catch (error) { await status({ running: false, message: '恢复尚未确认，请稍后查询服务端结果' }); throw error; }
}
spool.sweep().catch(() => {});
chrome.alarms.create('aden-spool-expiry', { periodInMinutes: 60 });
chrome.alarms.onAlarm.addListener(alarm => { if (alarm.name === 'aden-spool-expiry') void spool.sweep(); });
chrome.action.onClicked.addListener(tab => chrome.sidePanel.open({ tabId: tab.id }));
chrome.runtime.onMessage.addListener((request, sender, reply) => {
    if (request.action === 'pick.cancel' && sender.id === chrome.runtime.id && sender.tab?.id === pickingTabId) {
        pickingTabId = null; chrome.runtime.sendMessage({event:'pick.cancelled'}).catch(() => {}); reply({ok:true}); return;
    }
    if (request.action === 'page.changed' && sender.id === chrome.runtime.id && sender.tab?.id === sectionPage?.tabId && request.documentId === sectionPage?.documentId) {
        invalidateSections('商品页面、规格或登录条件已变化，请重新提取'); reply({ok:true}); return;
    }
    if (request.action === 'pick.result' && sender.id === chrome.runtime.id && sender.tab?.id === pickingTabId) {
        pickingTabId = null;
        const result = request.result || {};
        chrome.runtime.sendMessage({ event: 'pick.result', result: { key: String(result.key || '').slice(0, 30), name: String(result.name || '').slice(0, 40), selector: String(result.selector || '').slice(0, 300), sourceId: String(result.sourceId || '').slice(0, 128), imageCount: Number(result.imageCount) || 0, sample: String(result.sample || '').slice(0, 180) } }).catch(() => {});
        reply({ ok: true });
        return;
    }
    // 唯一入口是扩展自己的侧栏，拒绝内容脚本和外部页面转发。
    if (sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL('panel.html'))
        return;
    const run = async () => {
        await restored;
        if (request.action === 'section.extract') return extractSection(request);
        if (request.action === 'preview.clear') { invalidateSections('已清除当前预览'); return { cleared: true }; }
        if (request.action === 'preview') {
            const tab = await current();
            const selection = selectionOf(request.selection, ['MAIN', 'GALLERY', 'DETAIL']);
            return selectedProduct(await parse(tab.id, selection.customRules), selection);
        }
        if (request.action === 'pick.start') {
            const tab = await current();
            await parse(tab.id);
            pickingTabId = tab.id;
            await executeOnPage({ target: { tabId: tab.id }, func: startPicking });
            return { ready: true };
        }
        if (request.action === 'pick.stop') {
            const tabId = pickingTabId;
            pickingTabId = null;
            if (tabId) await chrome.scripting.executeScript({ target: { tabId }, func: stopPicking }).catch(() => {});
            return { stopped: true };
        }
        if (request.action === 'connect')
            return authenticate();
        if (request.action === 'lookup') {
            const tab = await current();
            const item = await parse(tab.id);
            await authenticate();
            if (!item.fields.sku) return {exists:false,unidentified:true};
            return native('capture.lookup', { sku: item.fields.sku });
        }
        if (request.action === 'capture') {
            await capture(request.groups, request.selection, request.expected);
            return job;
        }
        if (request.action === 'stop') {
            stop = true;
            return { stopped: true };
        }
        if (request.action === 'retry')
            return retryImages();
        if (request.action === 'local')
            return { running: false, message: '请连接 Aden 后查询保存结果与离线暂存' };
        if (request.action === 'spool.list') {
            await authenticate();
            return (await spool.read(identity)).filter(row => row.kind === 'capture').map(row => ({ captureId: row.captureId, title: row.payload.fields.title, expiresAt: row.expiresAt }));
        }
        if (request.action === 'spool.resume') return resumeOffline(request.captureId);
        if (request.action === 'spool.clear') { await authenticate(); await spool.clear(); return { cleared: true }; }
        if (request.action === 'status') {
            if (!job?.captureId)
                return job;
            await authenticate();
            if (!job) throw new Error('身份已变化，旧捕获已清理');
            const server = await native('capture.status', { captureId: job.captureId });
            await status({ needsVerification: false, server });
            return { local: job, server };
        }
        if (request.action === 'library')
            return native('library.open', job?.captureId ? { view: 'task', captureId: job.captureId, ...(job.result?.coreTaskId ? { taskId: job.result.coreTaskId } : {}) } : {});
        throw new Error('不支持的操作');
    };
    run().then(data => reply({ ok: true, data })).catch(error => reply({ ok: false, error: error.message, errorCode: error.code }));
    return true;
});
chrome.tabs.onUpdated?.addListener((tabId, changes) => {
    if (sectionPage?.tabId === tabId && (changes.url || changes.status === 'loading')) invalidateSections();
});
chrome.tabs.onActivated?.addListener(({ tabId }) => { if (sectionPage && sectionPage.tabId !== tabId) invalidateSections('当前标签页已变化，请重新提取'); });
chrome.permissions.onRemoved?.addListener(() => invalidateSections('页面或图片权限已撤销，请重新提取'));
