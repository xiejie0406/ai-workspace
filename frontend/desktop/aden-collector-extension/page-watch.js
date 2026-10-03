// 只观察商品身份变化；不导航、滚动、切规格或调用站点方法。
export function watchProductIdentity(expected) {
    globalThis.__adenIdentityWatchStop?.();
    const clean = value => String(value || '').replace(/\s+/g, ' ').trim();
    const fingerprint = () => {
        const url = new URL(location.href);
        const sku = expected.fixture ? document.querySelector('[data-product-sku]')?.getAttribute('data-product-sku') : url.pathname.match(/^\/(\d+)\.html$/)?.[1];
        const selected = [...document.querySelectorAll('#choose-attrs .selected,#choose .selected,.specification-series-item--selected,.specification-item-sku--selected,[data-selected-spec]')].map(node => clean(node.getAttribute('data-value') || node.textContent)).filter(Boolean).join(' / ');
        const declaredSku = document.querySelector('[data-current-sku]')?.getAttribute('data-current-sku');
        if (declaredSku && declaredSku !== sku || document.querySelector('#captcha,[data-captcha],#JDJRV-wrap,.JDJRV-wrap,form[action*="login"]')) return 'invalid';
        return JSON.stringify([url.origin, url.pathname, sku, selected]);
    };
    let timer;
    const stop = () => { observer.disconnect(); clearTimeout(timer); delete globalThis.__adenIdentityWatchStop; };
    const check = () => {
        if (fingerprint() !== expected.fingerprint) {
            stop(); chrome.runtime.sendMessage({ action:'page.changed', documentId:expected.documentId }).catch(() => {});
        }
    };
    const observer = new MutationObserver(() => { clearTimeout(timer); timer = setTimeout(check, 40); });
    observer.observe(document.documentElement, { subtree:true, childList:true, attributes:true, characterData:true });
    globalThis.__adenIdentityWatchStop = stop; check(); return { watching:true };
}
