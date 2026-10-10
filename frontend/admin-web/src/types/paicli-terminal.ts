/** 原 CLI 终端协议；业务会话仍使用既有 PaiCLI 类型。 */
export type PcTerminalRenderer = 'inline' | 'lanterna' | 'plain'
export type PcTerminalState = 'STARTING' | 'RUNNING' | 'STOPPING' | 'EXITED' | 'FAILED' | 'LOST'
export interface PcTerminal {
  id: string; terminalId: string; sessionId: string; directoryContextId: string; runnerId: string
  state: PcTerminalState; hostEpoch: number; writerEpoch: number; renderer: PcTerminalRenderer
  cols: number; rows: number; outputSeq: number; inputSeq: number; reason?: string; createdAt?: string
}
export interface PcTerminalTicket {
  ticket: string; protocols: string[]; terminalId: string; hostEpoch: number; writerEpoch: number
  clientId: string; canWrite: boolean; expiresAt: string
}
export interface PcTerminalAttachmentRequest { requestId: string; writerEpoch: number; source?: string }
export interface PcTerminalAuthorizationRequest { requestId: string; writerEpoch: number; operation: string; command: string; arguments: string; reason: string; approvalId?: string; directoryContextId?: string; version?: number; preview?: unknown; decision?: string; feedback?: string; revisedArguments?: unknown; inputHash?: string; frozenTargets?: unknown[]; taskId?: string; taskSessionId?: string; taskVersion?: number }
export interface PcTerminalExportArtifact { id: string; fileName: string }
export interface PcTerminalFrame {
  type: string; terminalId?: string; hostEpoch?: number; writerEpoch?: number; outputSeq?: number
  inputSeq?: number; bytes?: number; data?: string; state?: PcTerminalState; canWrite?: boolean
  clientId?: string; renderer?: PcTerminalRenderer; cols?: number; rows?: number; reason?: string
  requestId?: string; source?: string; message?: string; operation?: string; command?: string; arguments?: string; sessionId?: string; artifacts?: PcTerminalExportArtifact[]; approvalId?: string; directoryContextId?: string; version?: number; preview?: unknown; decision?: string; feedback?: string; revisedArguments?: unknown; inputHash?: string
  frozenTargets?: unknown[]; taskId?: string; taskSessionId?: string; taskVersion?: number
}
