import React, { memo } from 'react';
import { Tree } from 'antd';
import type { TreeProps } from 'antd';
import type { BasicDataNode, DataNode, AntTreeNodeProps } from 'antd/es/tree';

import flowArrowDown from '@/assets/imgs/workflow/flow-arrow-down.png';

function FLowTree<Node extends object = DataNode>({
  treeData = [],
  showLine = true,
  ...reset
}: TreeProps<Node & BasicDataNode>): React.ReactElement {
  return (
    <Tree<Node & BasicDataNode>
      showLine={showLine}
      switcherIcon={({ expanded }: Pick<AntTreeNodeProps, 'expanded'>) => (
        <img
          src={flowArrowDown}
          className="w-[8px] h-[7px]"
          style={{
            transform: expanded ? 'rotate(0deg)' : 'rotate(-90deg)',
            transition: 'transform 0.3s ease',
          }}
        />
      )}
      defaultExpandAll
      treeData={treeData}
      {...reset}
    />
  );
}

// memo 保留原运行包装，并恢复 TreeProps 的泛型调用契约。
export default memo(FLowTree) as typeof FLowTree;
