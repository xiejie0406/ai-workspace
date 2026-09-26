export interface RuoYiResult<T> {
  code: number
  msg?: string
  data: T
}

export type ProductStatus = 'draft' | 'active' | 'inactive'

export interface ProductImage {
  imageId: string
  objectKey: string
  sha256: string
  usage: 'main' | 'detail'
  status: string
  width: number
  height: number
  originalFilename: string
}

export interface ProductAttributeField { key: string; label: string; group: string; maxLength: number }

export interface FashionProduct {
  attributes?: Record<string, string>
  id: string
  sourceCode: string
  skuCode: string
  styleCode: string
  name: string
  categoryCode: string
  colorCode: string
  colorName: string
  sizeCode: string
  sizeSystem: string
  unit: string
  salePrice?: number
  currency: string
  taxMode: string
  brand?: string
  material?: string
  season: string
  tags: string[]
  mainImageKey?: string
  images: ProductImage[]
  visualVersion: number
  attributesConfirmed: boolean
  jdItemId?: string
  jdUrl?: string
  status: ProductStatus
  incompleteReasons: string[]
  rowVersion: number
  updateTime: string
}

export interface ImportRowError {
  field: string
  code: string
  valueSummary: string
  message: string
}

export interface ImportDetail {
  id: string
  detailNo: number
  sourceRowNo?: number
  productId?: string
  businessKey: string
  rawData?: Record<string, unknown>
  normalizedData?: Record<string, unknown>
  beforeData?: Record<string, unknown>
  afterData?: Record<string, unknown>
  status: 'pending' | 'valid' | 'invalid' | 'applied'
  errors: ImportRowError[]
  changeType?: 'added' | 'changed' | 'unchanged'
  rowType?: 'input' | 'missing'
}

export interface ImportBatchView {
  batchId: string
  batchNo: string
  status: string
  sourceCode: string
  fileHash: string
  asOf: string
  actualCount: number
  errorCount: number
  rowVersion: number
  details: ImportDetail[]
}

export type CatalogImportKind = 'price' | 'stock'

export interface CatalogImportBatchView {
  batchId: string
  batchNo: string
  importType: CatalogImportKind
  operationType: 'import' | 'restore'
  sourceBatchId?: string
  status: string
  sourceCode: string
  warehouseCode?: string
  fileName?: string
  fileHash?: string
  scope: Record<string, unknown>
  scopeHash: string
  baseDataHash: string
  asOf: string
  expectedCount: number
  actualCount: number
  errorCount: number
  errorMessage?: string
  operatorNote?: string
  rowVersion: number
  details: ImportDetail[]
}

export interface FashionCustomer {
  id: string
  code: string
  name: string
  customerType: 'group_purchase' | 'wholesale'
  contactName?: string
  contactPhone?: string
  region?: string
  salespersonId: string
  collaboratorIds: string[]
  internalNote?: string
  status: 'active' | 'archived'
  updateTime: string
  rowVersion: number
}

export interface RequirementFields {
  audience?: string
  scene?: string
  season?: string
  style?: string
  preferredColors: string[]
  exclusions: string[]
  deliveryDate?: string
}

export interface QuoteTier {
  count: number
  slots: string[]
  candidateCount: number
}

export interface FashionQuote {
  id: string
  quoteNo: string
  versionNo: number
  sourceQuoteId?: string
  customerId: string
  customerName: string
  title: string
  salespersonId: string
  requirementText?: string
  requirement: Record<string, unknown>
  requirementConfirmed: boolean
  requestedQty: number
  budget?: number
  budgetBasis: 'total' | 'per_set'
  quoteMode: string
  progressive: boolean
  comboTemplate: Record<string, unknown>
  warehouseCode: string
  currency: string
  taxMode: string
  status: string
  updateTime: string
  rowVersion: number
  priceFacts: Array<Record<string, unknown>>
}

export interface FashionAgent {
  id: string
  agentCode: string
  name: string
  agentType: string
  description?: string
  currentVersionId?: string
  status: string
  rowVersion: number
}

export interface FashionAgentVersion {
  id: string
  agentId: string
  versionNo: number
  providerCode: string
  modelName: string
  systemInstruction: string
  modelConfig: Record<string, unknown>
  tools: unknown[]
  handoffs: unknown[]
  inputSchema: Record<string, unknown>
  outputSchema: Record<string, unknown>
  guardrails: Record<string, unknown>
  maxSteps: number
  timeoutSeconds: number
  configHash: string
  status: string
  publishedBy?: string
  publishedAt?: string
  rowVersion: number
}

export interface FashionRun {
  id: string
  runNo: string
  conversationId: string
  agentVersionId: string
  quoteId?: string
  quoteRowVersion: number
  requestKey: string
  agentConfigHash: string
  deadlineAt: string
  contextSnapshot: Record<string, unknown>
  outputType?: string
  output?: Record<string, unknown>
  outputHash?: string
  validation?: Record<string, unknown>
  applyStatus: string
  status: string
  currentStepNo: number
  runAttempt: number
  startedAt?: string
  finishedAt?: string
  errorCode?: string
  errorMessage?: string
  rowVersion: number
}

export interface SelectionVariant {
  product_ref: string
  product_row_version: number
  sku_ref: string
  size_code: string
  unit_price_minor: number
  available_qty: number
}

export interface FrozenSelectionCandidate {
  candidate_ref: string
  category_code: string
  source_ref: string
  style_ref: string
  color_code: string
  color_name: string
  product_name: string
  season?: string
  tags: string[]
  conservative_unit_price_minor: number
  total_available_qty: number
  visual_hash: string
  fact_hash: string
  variants: SelectionVariant[]
}

export interface SelectionTierSpec {
  category_count: number
  candidate_count: number
  slots: Array<{ slot_index: number; category: string; required: boolean }>
}

export interface SelectionPreview {
  quote_ref: string
  quote_row_version: number
  selection_mode: 'independent' | 'progressive'
  requested_qty: number
  budget_maximum_per_set_minor?: number
  tiers: SelectionTierSpec[]
  frozen_candidates: FrozenSelectionCandidate[]
  locks: Array<{ slot_index: number; category_code: string; candidate_ref: string; candidate_visual_hash: string }>
  shortages: Array<{ category_code: string; reason: string }>
  candidate_set_hash: string
}

export interface SelectionDetail {
  id: string
  lineNo: number
  slotCode: string
  productId: string
  sourceCode: string
  skuCode: string
  styleCode: string
  productName: string
  categoryCode: string
  colorCode: string
  colorName: string
  sizeCode: string
  qty: number
  sourcePrice: number
  stockQty: number
  stockAsOf: string
  imageKey?: string
  imageHash?: string
  imageVersion?: number
  rowVersion: number
}

export interface SelectionCombo {
  id: string
  quoteId: string
  comboNo: string
  name: string
  categoryCount: number
  setQty: number
  selected: boolean
  sortNo: number
  reason?: string
  lockedSlots: string[]
  visualHash: string
  rowVersion: number
  details: SelectionDetail[]
}

export interface SelectionWorkspace {
  quote: FashionQuote
  preview: SelectionPreview
  combinations: SelectionCombo[]
}

export interface SelectionApplyResult {
  quoteId: string
  quoteRowVersion: number
  combinations: SelectionCombo[]
}

export type QuoteImageSourceMode = 'sample' | 'upload' | 'provider' | 'composition' | 'reuse'
export type QuoteImageStatus = 'queued' | 'running' | 'success' | 'partial' | 'failed' | 'cancel_requested' | 'cancelled' | 'unknown'

export interface QuoteImageResult {
  no: number
  status: 'success' | 'failed'
  object_key?: string
  sha256?: string
  width?: number
  height?: number
  error?: string
  allow_proposal: boolean
  allow_ecommerce: boolean
  reviews: Array<{decision: 'pass' | 'reject'; reviewer: string; time: string; reason?: string; checklist: string[]; input_hash: string}>
}

export interface QuoteImageTask {
  id: string
  quoteId: string
  comboId: string
  imageType: 'model' | 'styling' | 'ecommerce' | 'composition'
  sourceMode: QuoteImageSourceMode
  sourceLabel: string
  inputHash: string
  inputs: {schema_version: string; combo_id: string; combo_visual_hash: string; slots: Array<{slot_code: string; image_key: string; image_hash: string}>}
  parameters: Record<string, unknown>
  requestedCount: number
  results: QuoteImageResult[]
  providerCode?: string
  providerTaskId?: string
  status: QuoteImageStatus
  displayStatus: string
  stale: boolean
  retryCount: number
  estimatedCost?: number
  actualCost?: number
  costCurrency?: string
  billingStatus: 'not_applicable' | 'reserved' | 'unknown' | 'settled'
  errorMessage?: string
  finishedAt?: string
  createTime: string
  rowVersion: number
}

export interface QuoteImageWorkspace {
  quoteId: string
  quoteRowVersion: number
  quoteStatus: string
  providerEnabled: boolean
  providerReason?: string
  monthlyBudget: number
  monthSettledCost: number
  combinations: SelectionCombo[]
  tasks: QuoteImageTask[]
}

export interface QuotePricingIssue { code: string; message: string }
export interface QuotePricingLine {
  id: string; slotCode: string; productId: string; skuCode: string; productName: string
  categoryCode: string; colorName: string; sizeCode: string; unit: string; qty: number
  sourcePrice?: string; quotePrice?: string; amount?: string; stockQty?: number; stockAsOf?: string
  priceBatchId?: string; stockBatchId?: string; priceChanged: boolean; stockChanged: boolean
  imageChanged: boolean; rowVersion: number
}
export interface QuotePricingCombo {
  id: string; comboNo: string; name: string; categoryCount: number; setQty: number; selected: boolean
  allocationConfirmed: boolean; selectedImageId?: string; selectedImageNo?: number; selectedImageValid: boolean
  subtotal?: string; discountAmount?: string; freight?: string; taxAmount?: string; totalAmount?: string
  rowVersion: number; lines: QuotePricingLine[]
}
export interface QuoteCalculatedCombo {
  id: string; subtotal: string; discountAmount: string; freight: string; taxAmount: string
  totalAmount: string; averagePerSet: string; maximumAvailableSets?: number
}
export interface QuotePricingWorkspace {
  quoteId: string; quoteNo: string; versionNo: number; customerName: string; requestedQty: number; status: string; rowVersion: number
  mode: 'alternatives' | 'combined'; warehouseCode: string; currency: 'CNY'; taxMode: 'included' | 'excluded'
  taxRate?: string; feeTaxable: boolean; discountType: 'percent' | 'fixed'; discountRate: string
  fixedDiscount: string; freight: string; validDays: number; validUntil?: string; publicNote?: string
  inputHash: string; approvalRequired: boolean; approvalValid: boolean; approval?: Record<string, unknown>
  subtotal?: string; discountAmount?: string; taxAmount?: string; totalAmount?: string; maximumAvailableSets?: number
  calculatedCombos: QuoteCalculatedCombo[]; issues: QuotePricingIssue[]; combos: QuotePricingCombo[]
}

export type DeliveryFileType = 'pptx' | 'csv' | 'image_zip' | 'jpg'
export type DeliveryFileStatus = 'queued' | 'running' | 'success' | 'failed'
export interface DeliveryArtifact {
  fileName: string; objectKey: string; contentType: string; sha256: string; byteSize: number
  pageCount?: number; role: string; skuCodes: string[]; sourceMode: string; reviewStatus: string
  retainUntil?: string
}
export interface DeliveryFile {
  id: string; fileType: DeliveryFileType; purpose: 'customer' | 'internal_expired'; rendererVersion: string
  requestKey: string; status: DeliveryFileStatus; files: DeliveryArtifact[]; retryCount: number
  nextRetryAt?: string; leaseUntil?: string; errorMessage?: string; lastDownloadAt?: string
  downloadCount: number; createTime: string; rowVersion: number
}
export interface DeliveryWorkspace {
  quoteId: string; quoteNo: string; versionNo: number; quoteTitle: string; quoteHash: string
  status: 'confirmed'; files: DeliveryFile[]
}
export interface OperationsMetric { code: string; value: string; unit: string; window: string }
export interface AlertCandidate { code: string; thresholdReached: boolean; currentValue: string; thresholdValue: string; description: string }
export interface OperationsExceptionItem { type: 'import'|'image'|'delivery'; id: string; correlationId: string; status: string; occurredAt: string; errorSummary: string }
export interface FashionOperationsSnapshot {
  generatedAt: string; importFailureRate: OperationsMetric; imageFailedOrUnknown: OperationsMetric
  expiredStock: OperationsMetric; deliveryFailureRate: OperationsMetric; deliveryAverageDurationMs: OperationsMetric
  alertCandidates: AlertCandidate[]; exceptions: OperationsExceptionItem[]; notificationStatus: string
}
export interface RetentionCandidate {
  category: 'import_file'|'failed_image'|'quote_file'; recordId: string; objectKey?: string
  createdAt: string; eligibleAt: string; decision: 'cleanable'|'protected'|'extended'|'not_due'
  reason: string; quoteId?: string
}
export interface RetentionDryRun {
  generatedAt: string; deletionEnabled: false; quoteDays: number; importFileDays: number
  failedImageDays: number; candidates: RetentionCandidate[]
}
