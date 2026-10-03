// 在隔离世界中运行；只点选当前页面已加载的 DOM，不执行站点脚本。
export function startPicking() {
    if (globalThis.__adenPickerStop) globalThis.__adenPickerStop();
    const forbidden = 'form,input,button,textarea,select,[data-personal],.recommend,.comment,.comment-root,.everyone-reviews,.page-right-AddressComponent,#captcha';
    const marker = document.createElement('div');
    marker.setAttribute('aria-hidden', 'true');
    Object.assign(marker.style, { position: 'fixed', zIndex: '2147483647', pointerEvents: 'none', border: '3px solid #277556', borderRadius: '5px', background: 'rgba(39,117,86,.12)', display: 'none' });
    document.documentElement.append(marker);
    const known = [
        ['params', '.parameter2,#parameter2,[data-product-parameters]'],
        ['intro', '[data-product-introduction],#product-introduction,.product-introduction'],
        ['detailImages', '#J-detail-content img,#detail .detail-content img,[data-product-description] img'],
        ['detail', '#J-detail-content,#detail .detail-content,[data-product-description]'],
        ['main', '#spec-img,.image-zoom-container.main-img,[data-product-main]'],
        ['gallery', '#spec-list,.image-carousel-track,[data-product-gallery]'],
        ['basic', '.sku-name,.sku-title-name,[data-product-title],.summary-price,.summary-price-wrap,.product-price--main'],
    ];
    function targetOf(node) {
        if (!(node instanceof Element) || node.closest(forbidden)) return null;
        const match = known.find(([, selector]) => node.closest(selector));
        const candidate = node.closest('img,li,tr,dt,dd,p,[data-parameter-row],section,article,dl,table,div') || node;
        if (candidate === document.body || candidate === document.documentElement || candidate.closest(forbidden)) return null;
        if (candidate.querySelector(forbidden)) return null;
        if (match) {
            const root = node.closest(match[1]);
            return { node: root.contains(candidate) ? candidate : root, key: match[0] };
        }
        const productRoot = candidate.closest('[data-product-root],.product-intro,#product-intro,#detail,.page-right-skuname,.page-right-spec,.image-area,.image-carousel');
        const detail = candidate.closest('.left-tabs-item');
        const modernDetail = detail && [...detail.children].some(child => child.matches('.title') && child.textContent?.trim() === '商品详情') && !candidate.closest('.title,.skeleton,[class*="placeholder"],[class*="placeholer"]');
        if ((!productRoot && !modernDetail) || (candidate.localName !== 'img' && !candidate.textContent?.trim())) return null;
        if (modernDetail) return {node:candidate, key:candidate.localName === 'img' ? 'detailImages' : 'detail'};
        return { node: candidate, key: 'custom' };
    }
    function selectorFor(element) {
        if (element.id) return `#${CSS.escape(element.id)}`;
        const parts = [];
        for (let node = element; node && node !== document.body && parts.length < 6; node = node.parentElement) {
            if (node.id) { parts.unshift(`#${CSS.escape(node.id)}`); break; }
            const tag = node.localName;
            const siblings = node.parentElement ? [...node.parentElement.children].filter(child => child.localName === tag) : [];
            parts.unshift(tag + (siblings.length > 1 ? `:nth-of-type(${siblings.indexOf(node) + 1})` : ''));
        }
        return parts.join(' > ');
    }
    function move(event) {
        const item = targetOf(event.target);
        if (!item) { marker.style.display = 'none'; return; }
        const rect = item.node.getBoundingClientRect();
        Object.assign(marker.style, { display: 'block', top: `${rect.top}px`, left: `${rect.left}px`, width: `${rect.width}px`, height: `${rect.height}px` });
    }
    function click(event) {
        const item = targetOf(event.target);
        if (!item) return;
        event.preventDefault(); event.stopImmediatePropagation();
        const sample = String(item.node.textContent || item.node.getAttribute('alt') || '').replace(/\s+/g, ' ').trim().slice(0, 180);
        const selector = selectorFor(item.node);
        let unique = false;
        try { unique = document.querySelectorAll(selector).length === 1; } catch { }
        if (!unique) return;
        const parts = [];
        for (let node = item.node; node && node !== document.body; node = node.parentElement) {
            if (node.id) { parts.unshift('#' + node.id); break; }
            const siblings = node.parentElement ? [...node.parentElement.children].filter(child => child.localName === node.localName) : [];
            parts.unshift(node.localName + ':' + (siblings.indexOf(node) + 1));
        }
        let hash = 2166136261; for (const char of parts.join('/')) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
        chrome.runtime.sendMessage({ action: 'pick.result', result: { key: item.key, name: item.key === 'custom' ? '自选区域' : '', selector, sourceId: 'dom-' + (hash >>> 0).toString(16).padStart(8, '0'), sample, imageCount: item.node.localName === 'img' ? 1 : item.node.querySelectorAll('img').length } }).catch(() => {});
        stop();
    }
    function keydown(event) { if (event.key === 'Escape') { stop(); chrome.runtime.sendMessage({action:'pick.cancel'}).catch(() => {}); } }
    function stop() {
        document.removeEventListener('pointermove', move, true);
        document.removeEventListener('click', click, true);
        document.removeEventListener('keydown', keydown, true);
        marker.remove();
        delete globalThis.__adenPickerStop;
    }
    document.addEventListener('pointermove', move, true);
    document.addEventListener('click', click, true);
    document.addEventListener('keydown', keydown, true);
    globalThis.__adenPickerStop = stop;
    return { ready: true };
}

export function stopPicking() { globalThis.__adenPickerStop?.(); }
