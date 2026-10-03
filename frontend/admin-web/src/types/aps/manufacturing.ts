export type ItemType = 'PRODUCT' | 'SEMI_FINISHED' | 'MATERIAL'
export type DependencyType = 'FINISH' | 'QUANTITY' | 'SAME_START'
export type OperationMode = 'MANUAL' | 'MAN_MACHINE' | 'AUTO' | 'BATCH' | 'WAIT' | 'TRANSPORT'
export type PhaseType = 'SETUP' | 'RUN' | 'UNLOAD' | 'WAIT' | 'TRANSPORT'
export type DurationModel = 'FIXED' | 'PER_UNIT' | 'FIXED_PLUS_UNIT'

export interface ApsItem { id: string; code: string; name: string; type: ItemType; specification?: string; baseUomCode: string; status: 'ACTIVE' | 'INACTIVE'; remark?: string; rowVersion: number }
export interface ApsRequirement { id?: string; requirementNo: number; resourceType: 'PERSON' | 'MACHINE' | 'WORKSTATION' | 'TOOL'; workCenterId?: string; fixedResourceId?: string; seatCount: number; requiredSkillCode?: string; minimumSkillLevel?: number; optional: boolean; holdOnPause?: boolean }
export interface ApsPhase { id?: string; phaseNo: number; phaseType: PhaseType; name: string; durationModel: DurationModel; fixedSeconds: number; secondsPerUnit: number; resourceHoldPolicy: 'PHASE_ONLY' | 'UNTIL_NEXT_PHASE' | 'WHOLE_JOB'; maxSegments?:number; minSegmentSeconds?:number; resumeSetupSeconds?:number; segmentResourcePolicy?:'SAME_RESOURCES'|'RESELECT_ALLOWED'; requirements: ApsRequirement[] }
export interface ApsOperation { id: string; code: string; name: string; mode: OperationMode; outputItemId?: string; interruptible: boolean; qualityGateRequired: boolean; batchCapacity?: number; batchUomCode?: string; status: 'DRAFT' | 'ACTIVE' | 'INACTIVE'; remark?: string; phases: ApsPhase[]; rowVersion: number }
export interface ApsRouteVersion { id: string; itemId: string; routeCode: string; versionNo: string; status: 'DRAFT' | 'ACTIVE' | 'RETIRED'; effectiveFrom?: string; effectiveTo?: string; changeNote?: string; approvedBy?: string; approvedAt?: string; rowVersion: number }
export interface ApsRouteNode { id: string; routeVersionId: string; operationSpecId: string; nodeCode: string; nodeName: string; displayOrder: number; quantityMultiplier: number; terminal: boolean; rowVersion: number }
export interface ApsRouteEdge { id: string; routeVersionId: string; predecessorNodeId: string; successorNodeId: string; dependencyType: DependencyType; thresholdQty?: number; thresholdRatio?: number; transferBatchQty?: number; lagSeconds: number; consumesOutput: boolean; rowVersion: number }
export interface ApsRouteGraph { route: ApsRouteVersion; nodes: ApsRouteNode[]; edges: ApsRouteEdge[] }
export interface ApsValidationIssue { code: string; objectType: string; objectId: string; field?: string; message: string }
export interface ApsRouteValidation { routeVersionId: string; publishable: boolean; issues: ApsValidationIssue[] }

export interface ApsComponentSpec { demandNo: number; targetNodeCode: string; itemId: string; sourceLineNo?: number; sourceNodeCode?: string; demandType: 'COMPONENT' | 'TRANSFER' | 'EXTERNAL'; requiredQtyPerUnit: number; uomCode: string; transferBatchQty?: number }
export interface ApsLineDependency { predecessorLineNo: number; predecessorNodeCode: string; successorNodeCode: string; dependencyType: DependencyType; thresholdQty?: number; thresholdRatio?: number; transferBatchQty?: number; lagSeconds: number; consumesOutput: boolean }
export interface ApsOrderLine { id: string; orderId: string; lineNo: number; itemId: string; routeVersionId: string; demandQty: number; uomCode: string; promisedAt?: string; earliestStartAt?: string; status: string; components: ApsComponentSpec[]; dependencies: ApsLineDependency[]; rowVersion: number }
export interface ApsOrder { id: string; orderNo: string; sourceSystem: string; externalId?: string; customerCode?: string; customerName?: string; priority: number; promisedAt?: string; earliestStartAt?: string; status: string; remark?: string; lines: ApsOrderLine[]; rowVersion: number }
export interface ApsLot { id: string; orderLineId: string; parentLotId?: string; lotNo: string; lotType: 'NORMAL' | 'SPLIT' | 'REWORK' | 'REPLENISH'; plannedQty: number; uomCode: string; status: string }
export interface ApsTask { id: string; productionLotId: string; routeVersionId: string; routeNodeId: string; operationSpecId: string; workCenterId?: string; taskCode: string; taskName: string; taskQty: number; uomCode: string; setupSeconds: number; runSeconds: number; unloadSeconds: number; waitSeconds: number; transportSeconds: number; status: string }
export interface ApsTaskDependency { id: string; predecessorTaskId: string; successorTaskId: string; dependencyType: DependencyType; thresholdQty?: number; thresholdRatio?: number; transferBatchQty?: number; lagSeconds: number; consumesOutput: boolean }
export interface ApsMaterialDemand { id: string; targetTaskId: string; demandNo: number; itemId: string; sourceTaskId?: string; demandType: string; requiredQty: number; uomCode: string; materialStatus: string }
export interface ApsExpansion { order: ApsOrder; lots: ApsLot[]; tasks: ApsTask[]; dependencies: ApsTaskDependency[]; materialDemands: ApsMaterialDemand[]; reused: boolean }
export interface ApsRouteMigrationDiff { orderLineId: string; currentRouteVersionId: string; targetRouteVersionId: string; addedNodes: string[]; removedNodes: string[]; changedOperations: string[]; addedEdges: string[]; removedEdges: string[]; confirmationRequired: true }
