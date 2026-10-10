import React, { useEffect, useRef, useState } from 'react';
import { message, Upload, Button, Alert, UploadFile } from 'antd';
import { v4 as uuid } from 'uuid';
import { createPortal } from 'react-dom';
import { workflowImport, workflowImportPreview, type WorkflowImportPreview } from '@/services/flow';
import useSpaceStore from '@/store/space-store';
import { typeList } from '@/constants';
import { useNavigate } from 'react-router-dom';
import i18next from 'i18next';

import close from '@/assets/imgs/workflow/modal-close.png';
import uploadAct from '@/assets/imgs/knowledge/icon_zhishi_upload_act.png';

const { Dragger } = Upload;

// 定义上传事件类型
interface FileUploadEvent {
  file: File;
  onSuccess?: (response: any, file: File) => void;
  onError?: (error: any, response: any) => void;
  onProgress?: (event: { percent: number }, file: File) => void;
}

// 定义自定义上传文件类型，扩展UploadFile
interface CustomUploadFile extends UploadFile {
  id: string;
  type?: string;
  total?: string;
  progress?: number;
  loaded?: number;
  file?: File;
}

function WorkflowImportModal({
  setWorkflowImportModalVisible,
}: {
  setWorkflowImportModalVisible: (visible: boolean) => void;
}) {
  const navigate = useNavigate();
  // 使用自定义类型替代原始UploadFile类型
  const [uploadList, setUploadList] = useState<CustomUploadFile[]>([]);
  const [pending, setPending] = useState<'preview' | 'confirm' | null>(null);
  const [preview, setPreview] = useState<WorkflowImportPreview | null>(null);
  const [error, setError] = useState('');
  const busy = useRef(false);
  const requestKind = useRef<'preview' | 'confirm' | null>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const previewContext = useRef('');
  const context = () => {
    const space = useSpaceStore.getState();
    return JSON.stringify([localStorage.getItem('accessToken'), space.spaceId, space.spaceType, space.enterpriseId]);
  };
  const invalidate = () => {
    generation.current += 1;
    previewContext.current = '';
    setPreview(null);
    setError('');
  };
  useEffect(() => {
    mounted.current = true;
    const unsubscribe = useSpaceStore.subscribe((next, previous) => {
      if (next.spaceId !== previous.spaceId || next.spaceType !== previous.spaceType || next.enterpriseId !== previous.enterpriseId) invalidate();
    });
    const onStorage = (event: StorageEvent) => { if (event.key === 'accessToken' || event.key === null) invalidate(); };
    window.addEventListener('storage', onStorage);
    return () => {
      mounted.current = false;
      generation.current += 1;
      unsubscribe();
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  function beforeUpload(file: UploadFile) {
    const maxSize = 20 * 1024 * 1024;
    if (!file.size || file.size > maxSize) {
      message.error(
        i18next.t('workflow.promptDebugger.uploadFileSizeExceeded')
      );
      return false;
    }
    const isYml = ['yml', 'yaml'].includes(
      (file?.name?.split('.')?.pop() || '').toLowerCase()
    );
    if (!isYml) {
      message.error(
        i18next.t('workflow.promptDebugger.pleaseUploadYmlYamlFormat')
      );
      return false;
    } else {
      return true;
    }
  }

  const formatFileSize = (sizeInBytes: number) => {
    if (sizeInBytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(sizeInBytes) / Math.log(k));

    return (
      parseFloat((sizeInBytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    );
  };

  const fileUpload = (event: unknown) => {
    if (busy.current) return;
    invalidate();
    const file = (event as FileUploadEvent).file;
    const id = uuid();
    // 使用自定义类型创建文件对象
    const customFile: CustomUploadFile = {
      uid: id,
      id,
      name: file.name,
      type: file.name?.split('.')?.pop()?.toLowerCase(),
      progress: 0,
      status: 'done',
      loaded: 0,
      total: formatFileSize(file.size),
      file,
    };

    setUploadList([customFile]);
    (event as FileUploadEvent).onSuccess?.({}, file);
  };

  const uploadProps = {
    name: 'file',
    showUploadList: false,
    accept: '.yml,.yaml',
    beforeUpload,
    customRequest: fileUpload,
    disabled: pending !== null,
    maxCount: 1,
  };

  const handleOk = async () => {
    const file = uploadList[0]?.file;
    if (!file || busy.current) return;
    const receipt = preview;
    const requestContext = context();
    if (receipt && previewContext.current !== requestContext) {
      invalidate();
      setError(i18next.t('workflow.promptDebugger.importContextChanged'));
      return;
    }
    if (receipt && !receipt.saveable) return;
    const requestGeneration = generation.current;
    busy.current = true;
    requestKind.current = receipt ? 'confirm' : 'preview';
    setPending(receipt ? 'confirm' : 'preview');
    setError('');
    try {
      if (!receipt) {
        const result = await workflowImportPreview(file);
        if (mounted.current && requestGeneration === generation.current && requestContext === context()) {
          previewContext.current = requestContext;
          setPreview(result);
        }
      } else {
        const result = await workflowImport({ file, previewHash: receipt.previewHash });
        if (mounted.current && requestGeneration === generation.current && requestContext === context()) {
          setWorkflowImportModalVisible(false);
          navigate(`/work_flow/${result.flowId}/arrange`);
        }
      }
    } catch (err: unknown) {
      if (mounted.current && requestGeneration === generation.current) {
        setPreview(null);
        previewContext.current = '';
        const detail = err && typeof err === 'object' && 'message' in err && typeof err.message === 'string'
          ? err.message : i18next.t('workflow.promptDebugger.importFailed');
        setError(receipt ? `${detail}。${i18next.t('workflow.promptDebugger.importConfirmUncertain')}` : detail);
      }
    } finally {
      busy.current = false;
      requestKind.current = null;
      if (mounted.current) setPending(null);
    }
  };

  const closeModal = () => { if (requestKind.current !== 'confirm') setWorkflowImportModalVisible(false); };

  return (
    <>
      {createPortal(
        <div
          className="mask"
          style={{
            zIndex: 1201,
          }}
        >
          <div className="modal-container w-[640px]" style={{ maxWidth: 'calc(100vw - 32px)', maxHeight: 'calc(100vh - 40px)', overflowY: 'auto' }} role="dialog" aria-modal="true" aria-label={i18next.t('workflow.promptDebugger.importWorkflow')}>
            <div className="w-full flex items-center justify-between">
              <div className="text-base font-semibold">
                {i18next.t('workflow.promptDebugger.importWorkflow')}
              </div>
              <img
                src={close}
                className="w-3 h-3 cursor-pointer"
                alt=""
                onClick={closeModal}
              />
            </div>
            <div className="mt-6">
              <Dragger {...uploadProps} className="icon-upload">
                <img src={uploadAct} className="w-8 h-8" alt="" />
                <div className="font-medium mt-6">
                  {i18next.t('workflow.promptDebugger.dragFileHereOr')}
                  <span className="text-[#275EFF]">
                    {i18next.t('workflow.promptDebugger.selectFile')}
                  </span>
                </div>
                <p className="text-desc mt-2">
                  {i18next.t('workflow.promptDebugger.fileFormatYmlYaml')}
                </p>
              </Dragger>
            </div>
            {uploadList?.length > 0 && (
              <div className="mt-3">
                {uploadList?.map(item => (
                  <div
                    key={item?.id}
                    className="bg-[#F6F6F6] rounded-lg px-[5px] py-0.5 flex items-center justify-between"
                  >
                    <div className="flex items-center gap-[22px] overflow-hidden">
                      <div
                        className="w-[32px] h-[32px] bg-[#fff] rounded-lg flex items-center justify-center"
                        style={{
                          boxShadow: '0px 2px 4px 0px rgba(46,51,68,0.04)',
                        }}
                      >
                        <img
                          src={typeList.get(item?.type || '')}
                          className="w-[18px] h-[18px]"
                          alt=""
                        />
                      </div>
                      <div className="flex-1 text-overflow" title={item?.name}>
                        {item?.name}
                      </div>
                      <div>{item?.total}</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
            {error && <Alert className="mt-4" type="error" showIcon message={error} />}
            {preview && <div className="mt-4 space-y-3">
              <div>{preview.name} · {i18next.t('workflow.promptDebugger.importGraphSummary', { nodes: preview.nodes, edges: preview.edges })}</div>
              <Alert type={preview.saveable ? 'warning' : 'error'} showIcon
                message={i18next.t('workflow.promptDebugger.importDraftOnly')}
                description={preview.blockingReasons.join('；') || i18next.t('workflow.promptDebugger.importResourceNotice')} />
              {preview.differences.length > 0 && <details open><summary>{i18next.t('workflow.promptDebugger.importDifferences')}</summary>
                <ul className="list-disc pl-5">{preview.differences.map((item, index) => <li key={index}>{item.field}：{item.message}</li>)}</ul>
              </details>}
              {preview.unresolvedResources.length > 0 && <details open><summary>{i18next.t('workflow.promptDebugger.importUnresolved')}</summary>
                <ul className="list-disc pl-5">{preview.unresolvedResources.map((item, index) => <li key={index}>{item.nodeId} / {item.field}：{item.reason}</li>)}</ul>
              </details>}
              {preview.compatibility.issues.length > 0 && <details open><summary>{i18next.t('workflow.promptDebugger.importStructureIssues')}</summary>
                <ul className="list-disc pl-5">{preview.compatibility.issues.map((item, index) => <li key={index}>{item.nodeId} / {item.field}：{item.message}</li>)}</ul>
              </details>}
            </div>}
            <div className="flex justify-end gap-4 mt-6">
              <Button
                type="text"
                className="origin-btn px-[24px]"
                disabled={pending === 'confirm'}
                onClick={closeModal}
              >
                {i18next.t('workflow.promptDebugger.cancel')}
              </Button>
              <Button
                loading={pending !== null}
                type="primary"
                disabled={uploadList.length === 0 || (preview !== null && !preview.saveable)}
                className="px-[24px]"
                onClick={handleOk}
              >
                {i18next.t(preview ? 'workflow.promptDebugger.importConfirmDraft' : 'workflow.promptDebugger.importPreview')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </>
  );
}

export default WorkflowImportModal;
