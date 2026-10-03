// 仅读取当前已加载的商品区域；注入隔离世界，不调用站点函数。
export function extractProduct(options = {}) {
    try {
        const url = new URL(location.href);
        const fixture = options.fixtureOrigin && url.origin === options.fixtureOrigin;
        if (!(url.protocol === 'https:' && url.hostname === 'item.jd.com' && /^\/(\d+)\.html$/.test(url.pathname)) && !fixture) throw new Error('请打开京东商品详情页');
        const clean = (value, max = 60000) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);
        const text = selector => clean(document.querySelector(selector)?.textContent);
        const hash = value => { let result = 2166136261; for (const char of value) result = Math.imul(result ^ char.charCodeAt(0), 16777619); return (result >>> 0).toString(16).padStart(8, '0'); };
        const pathOf = element => {
            if (element.id) return '#' + element.id;
            const parts = [];
            for (let node = element; node && node !== document.body; node = node.parentElement) {
                if (node.id) { parts.unshift('#' + node.id); break; }
                const siblings = node.parentElement ? [...node.parentElement.children].filter(child => child.localName === node.localName) : [];
                parts.unshift(node.localName + ':' + (siblings.indexOf(node) + 1));
            }
            return parts.join('/');
        };
        const sourceId = element => 'dom-' + hash(pathOf(element));
        const forbidden = 'form,[data-personal],.recommend,.comment,.comment-root,.everyone-reviews,.page-right-AddressComponent,#captcha';
        if (document.querySelector('#JDJRV-wrap,.JDJRV-wrap,#captcha,[data-captcha],form[action*="login"]') || /验证|访问受限|安全检查/.test(document.title)) throw new Error('页面需要登录或验证，请手动处理后重新采集');
        const sku = fixture ? document.querySelector('[data-product-sku]')?.getAttribute('data-product-sku') : url.pathname.match(/^\/(\d+)\.html$/)?.[1];
        const title = text('.sku-name,.sku-title-name,[data-product-title]');
        if (sku && !/^\d{1,32}$/.test(sku) || !title) throw new Error('无法确认商品身份或标题，请反馈页面模板');
        const selected = [...document.querySelectorAll('#choose-attrs .selected,#choose .selected,.specification-series-item--selected,.specification-item-sku--selected,[data-selected-spec]')].map(node => clean(node.getAttribute('data-value') || node.textContent)).filter(Boolean).join(' / ');
        const fingerprint = JSON.stringify([url.origin, url.pathname, sku, selected]);
        const declaredSku = document.querySelector('[data-current-sku]')?.getAttribute('data-current-sku');
        if (declaredSku && declaredSku !== sku) throw new Error('当前规格与页面 SKU 不一致，请等待页面完成后重试');
        const definitions = {
            basic: '.sku-name,.sku-title-name,[data-product-title],.summary-price,.summary-price-wrap,.product-price--main,[data-product-price]',
            params: '.parameter2,#parameter2,[data-product-parameters]',
            intro: '[data-product-introduction],#product-introduction,.product-introduction',
            detail: '#J-detail-content,#detail .detail-content,[data-product-description]',
            main: '#spec-img,.image-zoom-container.main-img,[data-product-main]', gallery: '#spec-list,.image-carousel-track,[data-product-gallery]',
            detailImages: '#J-detail-content,#detail .detail-content,[data-product-description]',
        };
        // 新版详情只取明确“商品详情”下的内容，不取整个左侧栏（包含评价、店铺）。
        const modernDetail = [...document.querySelectorAll('.left-tabs-item')].filter(node =>
            [...node.children].some(child => child.matches('.title') && clean(child.textContent) === '商品详情'));
        const modernDetailRoots = modernDetail.flatMap(node => [...node.children].filter(child =>
            !child.matches('.title,.skeleton,[class*="placeholder"],[class*="placeholer"]')));
        const sectionId = options.sectionId;
        if (sectionId && !definitions[sectionId]) throw new Error('分区无效');
        if (options.regionRules !== undefined && !Array.isArray(options.regionRules)) throw new Error('区域规则无效');
        const rules = options.regionRules || [];
        if (rules.length > 100) throw new Error('本次最多设置 100 个范围，请移除已有范围后重试');
        const ruleElements = rule => {
            if (!rule || !definitions[rule.sectionId] || !['include', 'exclude'].includes(rule.mode) || typeof rule.selector !== 'string' || rule.selector.length > 300 || !/^[\w\s#.[\]>:+()=\"'\\-]+$/.test(rule.selector)) return [];
            try {
                const matches = [...document.querySelectorAll(rule.selector)];
                if (matches.length !== 1 || matches[0].closest(forbidden) || matches[0] === document.body || matches[0] === document.documentElement) return [];
                const productRoot = matches[0].closest('[data-product-root],.product-intro,#product-intro,#detail,.page-right-skuname,.page-right-spec,.image-area,.image-carousel');
                if (!matches[0].closest(Object.values(definitions).join(',')) && !productRoot && !modernDetailRoots.some(root => root.contains(matches[0]))) return [];
                return matches;
            } catch { return []; }
        };
        const excludes = id => rules.filter(rule => rule.sectionId === id && rule.mode === 'exclude').flatMap(ruleElements);
        const roots = id => {
            const defaults = [...document.querySelectorAll(definitions[id]), ...(['detail', 'detailImages'].includes(id) ? modernDetailRoots : [])].filter(node => !node.closest(forbidden));
            const includes = rules.filter(rule => rule.sectionId === id && rule.mode === 'include').flatMap(ruleElements);
            const all = [...new Set([...defaults, ...includes])];
            return all.filter(node => !all.some(parent => parent !== node && parent.contains(node)));
        };
        const excluded = (node, id) => excludes(id).some(root => root === node || root.contains(node));
        const safeText = (element, id) => {
            if (excluded(element, id)) return '';
            const copy = element.cloneNode(true);
            const remove = [];
            for (const rule of rules.filter(rule => rule.sectionId === id && rule.mode === 'exclude')) {
                for (const original of ruleElements(rule)) {
                    if (!element.contains(original) || original === element) continue;
                    const lineage = [];
                    for (let current = original; current !== element; current = current.parentElement) lineage.unshift([...current.parentElement.children].indexOf(current));
                    let target = copy; for (const index of lineage) target = target?.children[index];
                    if (target) remove.push(target);
                }
            }
            remove.forEach(node => node.remove());
            copy.querySelectorAll('script,style,iframe,input,button,textarea,select,' + forbidden).forEach(node => node.remove());
            return clean(copy.textContent);
        };
        const fields = { title, ...(sku ? {sku} : {}), sourceUrl: url.origin + url.pathname, specification: selected };
        const blocks = [], images = [], parameterRows = [], sources = new Set();
        const active = id => !sectionId || sectionId === id;
        for (const rule of rules.filter(rule => active(rule.sectionId))) if (!ruleElements(rule).length) throw new Error('已选区域无法唯一定位或超出商品范围，请调整后重提取');
        const priceNode = document.querySelector('.summary-price .p-price,.summary-price-wrap .p-price,.product-price--main,[data-product-price]');
        const priceCondition = priceNode?.matches('.product-price--main') ? clean(priceNode.parentElement?.querySelector('.product-price--activity-item--tag')?.textContent) : '';
        const priceText = priceNode && !excluded(priceNode, 'basic') ? clean([priceCondition, priceNode.textContent].filter(Boolean).join(' ')) : '';
        if (active('basic')) {
            const shopNode = document.querySelector('.J-hove-wrap .name,.seller-infor .name,[data-product-shop]');
            fields.priceText = priceText; fields.shop = shopNode && !excluded(shopNode, 'basic') ? clean(shopNode.textContent) : '';
            if (shopNode && !excluded(shopNode, 'basic')) sources.add(sourceId(shopNode));
            const match = priceText.match(/^[￥¥]?\s*(\d+(?:\.\d{1,2})?)$/); if (match) fields.price = match[1];
            for (const node of roots('basic')) sources.add(sourceId(node));
        }
        if (active('params')) {
            const excludedRows = new Set(Array.isArray(options.excludedRowIds) ? options.excludedRowIds : []);
            const context = options.parameterRowContext;
            if (excludedRows.size && (!context || !Array.isArray(context.rows))) throw new Error('参数排除缺少已核对预览，请重新提取并核对参数');
            if (excludedRows.size && context.fingerprint !== fingerprint) throw new Error('参数排除所属页面或规格已变化，请重新提取所有所选分区');
            for (const root of roots('params')) {
                const candidates = [...root.querySelectorAll('li,tr,dt,[data-parameter-row]')];
                const rows = candidates.length ? candidates.filter(node => !candidates.some(other => other !== node && node.contains(other))) : [root];
                for (const node of rows) {
                    if (node.closest(forbidden) || excluded(node, 'params')) continue;
                    const rawText = safeText(node, 'params').slice(0, 4000); if (!rawText) continue;
                    const cells = [...node.querySelectorAll('th,td')].map(cell => clean(cell.textContent));
                    const match = rawText.match(/^([^：:]{1,200})[：:]\s*(.*)$/);
                    const name = (cells.length > 1 ? cells[0] : match ? match[1].trim() : rawText).slice(0, 200);
                    const value = (cells.length > 1 ? cells.slice(1).join(' / ') : match ? match[2] : '').slice(0, 2000);
                    const id = 'param-' + hash(JSON.stringify([fingerprint, pathOf(node), rawText, name, value])); sources.add(id);
                    const unknown = !name || !value || /^(未知|未显示|暂无|[-—])$/.test(value);
                    const unit = value.match(/(?:\d(?:[\d.]*))\s*(ml|mL|L|kg|g|mm|cm|m|毫升|升|克|千克|厘米|毫米)(?:\b|$)/)?.[1] || '';
                    parameterRows.push({ name, value, rawText, unit, ...(unknown ? { unknownReason: '页面未提供明确参数值' } : {}), sourceId: id, sectionId: 'params', excluded: excludedRows.has(id) });
                }
            }
            // 不能仅凭 DOM 序号把旧排除应用到新行；整个有序来源快照漂移就重新核对。
            if (excludedRows.size) {
                const currentRows = parameterRows.map(row => ({ sourceId: row.sourceId, rawText: row.rawText, name: row.name, value: row.value }));
                if (JSON.stringify(context.rows) !== JSON.stringify(currentRows) || [...excludedRows].some(id => !parameterRows.some(row => row.sourceId === id))) throw new Error('参数行位置或原文已变化，原排除已失效，请重新提取并核对参数');
            }
            fields.parameters = parameterRows.filter(row => !row.excluded).map(row => row.rawText).join('；');
            if (fields.parameters) { const id = 'parameters-' + hash(parameterRows.filter(row => !row.excluded).map(row => row.sourceId).join(',')); sources.add(id); blocks.push({ type: 'parameters', text: fields.parameters, sourceId: id }); }
        }
        for (const [id, type] of [['intro', 'introduction'], ['detail', 'description']]) if (active(id)) {
            for (const node of roots(id)) { const value = safeText(node, id); const idOfSource = sourceId(node); if (value) { sources.add(idOfSource); blocks.push({ type, text: value, sourceId: idOfSource }); } }
        }
        const seen = new Set();
        for (const [id, group] of [['main', 'MAIN'], ['gallery', 'GALLERY'], ['detailImages', 'DETAIL']]) if (active(id)) {
            for (const root of roots(id)) {
                const nodes = root.localName === 'img' ? [root] : [...root.querySelectorAll('img')];
                for (const img of nodes) {
                    if (img.closest(forbidden) || excluded(img, id) || !img.complete || !img.naturalWidth || !(img.currentSrc || img.getAttribute('src'))) continue;
                    let imageUrl; try { imageUrl = new URL(img.currentSrc || img.getAttribute('src'), url).href; } catch { continue; }
                    if (imageUrl.length > 4096 || seen.has(imageUrl)) continue;
                    seen.add(imageUrl); const idOfSource = 'image-' + hash(imageUrl); sources.add(idOfSource);
                    images.push({ imageId: idOfSource, sourceId: idOfSource, url: imageUrl, group, order: images.length, width: img.naturalWidth, height: img.naturalHeight });
                }
            }
        }
        for (const rule of rules.filter(rule => active(rule.sectionId))) for (const node of ruleElements(rule)) sources.add(rule.sourceId || sourceId(node));
        if (sources.size > 1000 || parameterRows.length > 1000) throw new Error('当前分区内容超出读取上限，请缩小范围');
        // 旧消费者的自选文本兼容；新版仅通过带分区归属的区域规则调用。
        if (!sectionId) for (const rule of (options.customRules || []).slice(0, 5)) {
            const nodes = ruleElements({ ...rule, sectionId: 'detail', mode: 'include' });
            for (const node of nodes) { const value = safeText(node, 'detail'); if (value) blocks.push({ type: 'custom:' + clean(rule.name, 40), text: value, sourceId: sourceId(node) }); }
        }
        if (!sectionId) blocks.unshift({ type: 'title', text: title });
        const embeddedImageCount = ['intro', 'detail'].includes(sectionId) ? roots(sectionId).flatMap(node => [...node.querySelectorAll('img')]).filter(node => !excluded(node, sectionId) && !node.closest(forbidden) && node.complete && node.naturalWidth).length : 0;
        const meaningful = sectionId === 'params' ? parameterRows.some(row => !row.excluded) : ['main', 'gallery', 'detailImages'].includes(sectionId) ? images.length : sectionId === 'basic' ? !!title : blocks.length;
        return { source: 'JD', fields, blocks, images, parameterRows, sourceIds: [...sources], sectionId,
            embeddedImageCount, missingReason: sectionId === 'basic' && !sku ? '当前页未识别 SKU，仅保存为待核对采集记录' : sectionId && !meaningful ? '当前所选范围未识别内容或尚未加载' : '', parserVersion: 'jd-dom-0.3.1',
            fingerprint,
            completeness: { fixture: !!fixture, scope: 'LOADED_PRODUCT_BLOCKS', introduction: blocks.some(block => block.type === 'introduction') ? 'LOADED_SCOPE_ONLY' : 'NOT_LOADED', description: blocks.some(block => block.type === 'description') ? 'LOADED_SCOPE_ONLY' : 'NOT_LOADED', images: 'DISCOVERED_ONLY', totalDiscovered: images.length } };
    } catch (error) {
        if (!options.returnErrors) throw error;
        const known = ['请打开京东商品详情页', '页面需要登录或验证，请手动处理后重新采集', '无法确认商品身份或标题，请反馈页面模板', '当前规格与页面 SKU 不一致，请等待页面完成后重试', '已选区域无法唯一定位或超出商品范围，请调整后重提取', '当前分区内容超出读取上限，请缩小范围', '区域规则无效', '本次最多设置 100 个范围，请移除已有范围后重试', '参数排除缺少已核对预览，请重新提取并核对参数', '参数排除所属页面或规格已变化，请重新提取所有所选分区', '参数行位置或原文已变化，原排除已失效，请重新提取并核对参数'];
        return { error: { code: 'PAGE_PARSE_REJECTED', message: known.includes(error?.message) ? error.message : '页面商品结构解析失败，请反馈页面模板' } };
    }
}
