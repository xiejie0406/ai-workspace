// 分区预览仅在当前扩展会话中存在；这里不保存业务数据。
export const SECTIONS = [
    ['basic', '商品信息'], ['params', '规格参数'], ['intro', '商品介绍'],
    ['detail', '详情内容'], ['main', '主图'], ['gallery', '轮播图'], ['detailImages', '详情图'],
];
export const SECTION_NAMES = Object.fromEntries(SECTIONS);
export const MAX_REGION_RULES = 100;
export const SECTION_STATES = { empty: '未提取', reading: '提取中', ready: '已提取', stale: '待重提取', missing: '未识别或未加载' };
export class SectionSession {
    constructor() {
        this.revision = 1;
        this.epoch = 0;
        this.page = null;
        this.sections = Object.fromEntries(SECTIONS.map(([id]) => [id, { selected: true, status: 'empty', rangeRevision: 1, resultRevision: 0, rules: [], excludedRowIds: [], parameterRowContext: null, result: null, request: 0, expanded: false }]));
    }
    selected() { return SECTIONS.map(([id]) => id).filter(id => this.sections[id].selected); }
    select(id, selected) {
        const section = this.sections[id];
        if (!selected && section.selected && this.selected().length === 1) throw new Error('至少保留一个分区');
        if (section.selected === selected) return;
        section.selected = selected;
        if (id === 'params' && !selected) this.clearExcludedRows();
        this.invalidate(id, 'empty');
    }
    invalidate(id, status = 'stale') {
        const section = this.sections[id];
        section.request++; section.rangeRevision++; section.result = null;
        section.status = section.selected ? status : 'empty';
        this.revision++;
    }
    invalidateAll() {
        this.epoch++; this.page = null;
        this.clearExcludedRows();
        for (const [id] of SECTIONS) this.invalidate(id);
    }
    setRule(id, rule) {
        const section = this.sections[id];
        const rules = [...section.rules.filter(old => old.selector !== rule.selector), { ...rule, sectionId: id }];
        if (rules.length > 20) throw new Error('本区最多设置 20 个范围');
        if (SECTIONS.reduce((count, [key]) => count + (key === id ? rules.length : this.sections[key].rules.length), 0) > MAX_REGION_RULES) throw new Error('本次最多设置 100 个范围，请移除已有范围后重试');
        section.rules = rules;
        if (id === 'params') this.clearExcludedRows();
        this.invalidate(id);
    }
    clearExcludedRows() {
        this.sections.params.excludedRowIds = [];
        this.sections.params.parameterRowContext = null;
    }
    excludeRow(id, excluded) {
        const section = this.sections.params;
        const result = section.result;
        if (!result || !['ready', 'missing'].includes(section.status) || !result.parameterRows?.some(row => row.sourceId === id)) throw new Error('参数预览已失效，请重新提取并核对参数');
        const rows = new Set(section.excludedRowIds);
        if (excluded) rows.add(id); else rows.delete(id);
        section.excludedRowIds = [...rows];
        section.parameterRowContext = rows.size ? { documentId: result.documentId, fingerprint: result.fingerprint,
            rows: result.parameterRows.map(row => ({ sourceId: row.sourceId, rawText: row.rawText, name: row.name, value: row.value })) } : null;
        this.invalidate('params');
    }
    begin(id) {
        const section = this.sections[id];
        if (!section.selected) throw new Error('请先选择该分区');
        section.request++; section.status = 'reading'; section.result = null;
        return { epoch: this.epoch, request: section.request, rangeRevision: section.rangeRevision };
    }
    cancel() {
        for (const [id] of SECTIONS) if (this.sections[id].status === 'reading') this.invalidate(id);
    }
    accept(id, token, result) {
        const section = this.sections[id];
        if (token.epoch !== this.epoch || token.request !== section.request || token.rangeRevision !== section.rangeRevision || !section.selected) return false;
        if (this.page && (this.page.documentId !== result.documentId || this.page.fingerprint !== result.fingerprint)) {
            this.invalidateAll(); throw new Error('页面或规格已变化，请重新提取所有所选分区');
        }
        this.page = { documentId: result.documentId, fingerprint: result.fingerprint, fields: result.fields };
        section.result = result; section.resultRevision = result.resultRevision;
        section.status = result.missingReason ? 'missing' : 'ready';
        return true;
    }
    fail(id, token) {
        const section = this.sections[id];
        if (token.epoch !== this.epoch || token.request !== section.request) return false;
        section.status = 'stale'; return true;
    }
    ready() { return !!this.page && this.selected().every(id => ['ready', 'missing'].includes(this.sections[id].status)); }
    missing() {
        const list = this.selected().filter(id => this.sections[id].status === 'missing').map(id => SECTION_NAMES[id] + '：' + this.sections[id].result.missingReason);
        if (this.page && !this.page.fields.sku && !list.some(reason => reason.includes('SKU'))) list.unshift('商品身份：未识别 SKU，仅保存待核对记录');
        return list;
    }
    selection() {
        return { scope: Object.fromEntries(SECTIONS.map(([id]) => [id, this.sections[id].selected])),
            regionRules: SECTIONS.flatMap(([id]) => this.sections[id].rules), excludedRowIds: [...this.sections.params.excludedRowIds],
            parameterRowContext: this.sections.params.parameterRowContext };
    }
    reset() {
        for (const [id] of SECTIONS) {
            const section = this.sections[id];
            const changed = !section.selected || section.rules.length || section.excludedRowIds.length;
            section.selected = true; section.rules = []; section.excludedRowIds = []; section.parameterRowContext = null;
            if (changed) this.invalidate(id);
        }
    }
}

export function mergeSections(results) {
    if (!results.length) throw new Error('至少提取一个分区');
    const first = results[0];
    const fields = { title: first.fields.title, sku: first.fields.sku, sourceUrl: first.fields.sourceUrl, specification: first.fields.specification };
    const blocks = new Map(), images = new Map(), sourceIds = new Set();
    let parameterRows = [];
    for (const result of results) {
        if (result.documentId !== first.documentId || result.fingerprint !== first.fingerprint) throw new Error('分区不属于同一商品页面');
        if (result.sectionId === 'basic') Object.assign(fields, result.fields);
        if (result.sectionId === 'params') { fields.parameters = result.fields.parameters; parameterRows = result.parameterRows || []; }
        for (const block of result.blocks || []) {
            const key = block.sourceId || block.type + ':' + block.text;
            const existing = blocks.get(key);
            if (existing) {
                if (existing.text !== block.text || existing.imageId !== block.imageId) throw new Error('同一来源内容发生冲突，请重新提取');
                existing.sectionIds = [...new Set([...existing.sectionIds, result.sectionId])];
            }
            else blocks.set(key, { ...block, sectionIds: [result.sectionId] });
        }
        for (const image of result.images || []) {
            const key = image.sourceId || image.url;
            const existing = images.get(key);
            if (existing) { if (existing.url !== image.url) throw new Error('同一图片来源发生冲突，请重新提取'); existing.sectionIds = [...new Set([...existing.sectionIds, result.sectionId])]; }
            else images.set(key, { ...image, sectionIds: [result.sectionId] });
        }
        for (const sourceId of result.sourceIds || []) sourceIds.add(sourceId);
    }
    return { fields, blocks: [...blocks.values()], images: [...images.values()].map((image, order) => ({ ...image, order })), parameterRows, sourceIds: [...sourceIds],
        documentId: first.documentId, fingerprint: first.fingerprint, source: 'JD', parserVersion: first.parserVersion,
        completeness: { scope: 'LOADED_PRODUCT_BLOCKS', selectedSections: results.map(result => result.sectionId), missingSections: results.filter(result => result.missingReason).map(result => ({ sectionId: result.sectionId, reason: result.missingReason })) } };
}
