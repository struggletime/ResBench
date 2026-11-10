import React, { useRef, useState } from 'react';
import { useDrag, useDrop } from 'react-dnd';
import styles from './FaultList.module.css';
import GroupCard from './GroupCard';
import { useLanguage } from '../../i18n';

const FaultListItem = ({ onClick, onDelete, fault, paramValue, index, moveCard, hoveredIndex, setHoveredIndex }) => {
  const ref = useRef(null);
  const dragHandleRef = useRef(null);
  const { translate } = useLanguage();

  // 判断当前卡片是否是被悬停的目标
  const isOver = hoveredIndex === index;

  // 拖拽配置
  const [{ isDragging }, drag] = useDrag({
    type: 'FAULT_CARD',
    item: { index },
    collect: (monitor) => ({
      isDragging: monitor.isDragging(),
    }),
  });

  // 放置配置
  const [, drop] = useDrop({
    accept: 'FAULT_CARD',
    canDrop: (item) => {
      return item.index !== index;
    },
    hover(item) {
      if (!ref.current) return;

      const dragIndex = item.index;
      const hoverIndex = index;

      if (dragIndex === hoverIndex) return;

      // 设置当前悬停目标
      setHoveredIndex(hoverIndex);
    },
    drop(item) {
      const dragIndex = item.index;
      const hoverIndex = index;

      if (dragIndex !== hoverIndex) {
        moveCard(dragIndex, hoverIndex);
      }

      // 清除悬停状态
      setHoveredIndex(null);
    },
    leave() {
      // 鼠标离开时清除悬停状态
      setHoveredIndex(null);
    },
  });

  // 绑定拖拽和放置
  drag(drop(ref));

  // 卡片样式
  const opacity = isDragging ? 0.7 : 1;
  const transform = isDragging ? 'scale(1.05)' : 'none';
  const boxShadow = isDragging ? '0 4px 16px rgba(0, 0, 0, 0.2)' : '0 1px 4px rgba(0, 0, 0, 0.08)';
  const border = isOver ? '2px solid #1890ff' : '1px solid #d9d9d9';

  const paramEntries = paramValue ? Object.entries(paramValue) : [];

  return (
    <div
      ref={ref}
      className={styles.card}
      onClick={onClick}
      style={{
        opacity,
        transform,
        boxShadow,
        border,
        transition: 'all 0.2s ease',
        cursor: isDragging ? 'grabbing' : 'grab',
      }}
    >
      {/* 删除按钮 */}
      <button
        className={styles.deleteBtn}
        onClick={(e) => {
          e.stopPropagation();
          onDelete(index);
        }}
      >
        ×
      </button>

      {/* 拖拽手柄 - 左上角 */}
      <div
        ref={dragHandleRef}
        className={styles.dragHandle}
        draggable="true"
        onDragStart={(e) => {
          e.preventDefault();
        }}
      >
        ≡
      </div>

      {/* 序号 + 标题 */}
      <div className={styles.header}>
        <span className={styles.order}>[{index + 1}]</span>
        <h3 className={styles.title}>{fault.name}</h3>
      </div>

      {/* 参数列表 */}
      <div className={styles.params}>
        {paramEntries.length > 0 ? (
          paramEntries.map(([key, value], idx) => (
            <div key={idx} className={styles.paramLine}>
              {key}: {value}
            </div>
          ))
        ) : (
          <div className={styles.paramLine}>{translate('FaultInjection.noParams')}</div>
        )}
      </div>
    </div>
  );
};

const FaultsList = ({ faults, paramsValue, onClick, onDelete, onDeleteGroup, onReorder, onInnerReorder, expandedGroups, onToggleExpand, onUpdateGroupDuration }) => {
  const [hoveredIndex, setHoveredIndex] = useState(null);

  const moveCard = (dragIndex, hoverIndex) => {
    const draggedItem = faults[dragIndex];
    const newFaults = [...faults];
    newFaults.splice(dragIndex, 1);
    newFaults.splice(hoverIndex, 0, draggedItem);

    const newParams = {};
    newFaults.forEach((fault) => {
      newParams[fault.id] = paramsValue[fault.id] || {};
    });

    onReorder(newFaults, newParams);
  };

  const handleInnerReorder = (groupId, dragIndex, hoverIndex) => {
    onInnerReorder && onInnerReorder(groupId, dragIndex, hoverIndex);
  };

  const handleClickFault = (type, groupId, faultId) => {
    onClick && onClick(type, groupId, faultId);
  };

  const handleDeleteFault = (type, groupId, faultId) => {
    onDelete && onDelete(type, groupId, faultId);
  };

  return (
    <div className={styles.container}>
      {faults.map((fault, index) => {
        // 根据类型渲染不同的组件
        if (fault.type === 'group') {
          return (
            <GroupCard
              key={fault.id}
              groupId={fault.id}
              name={fault.name}
              color={fault.color}
              faults={fault.faults}
              groupDuration={paramsValue?.[fault.id]?.['__groupDuration__']}
              isExpanded={expandedGroups && expandedGroups.includes(fault.id)}
              onToggleExpand={() => onToggleExpand && onToggleExpand(fault.id)}
              onClickFault={(groupId, faultId) => handleClickFault('inner', groupId, faultId)}
              onDeleteFault={(groupId, faultId) => handleDeleteFault('inner', groupId, faultId)}
              onDeleteGroup={(groupId) => onDeleteGroup && onDeleteGroup(groupId)}
              onInnerReorder={handleInnerReorder}
              onUpdateGroupDuration={(groupId, value) => {
                if (onUpdateGroupDuration) onUpdateGroupDuration(groupId, value);
              }}
              index={index}
              moveCard={moveCard}
            />
          );
        }

        // 单个故障
        return (
          <FaultListItem
            key={fault.id}
            onClick={() => onClick('single', index)}
            onDelete={(faultIndex) => handleDeleteFault('single', faultIndex)}
            fault={fault}
            paramValue={paramsValue[fault.id] || {}}
            index={index}
            moveCard={moveCard}
            hoveredIndex={hoveredIndex}
            setHoveredIndex={setHoveredIndex}
          />
        );
      })}
    </div>
  );
};

export default FaultsList;
// import React, { useRef, useState } from 'react';
// import { useDrag, useDrop } from 'react-dnd';
// import styles from './FaultList.module.css';

// const FaultListItem = ({ onClick, onDelete, fault, paramValue, index, moveCard }) => {
//   const ref = useRef(null);
//   const dragHandleRef = useRef(null);

//   // 本地状态：是否被拖拽到上方（用于高亮）
//   const [isOver, setIsOver] = useState(false);

//   // 拖拽配置
//   const [{ isDragging }, drag, preview] = useDrag({
//     type: 'FAULT_CARD',
//     item: { index },
//     collect: (monitor) => ({
//       isDragging: monitor.isDragging(),
//     }),
//   });

//   // 放置配置
//   const [, drop] = useDrop({
//     accept: 'FAULT_CARD',
//     canDrop: (item, monitor) => {
//       // 只允许拖到其他卡片上（不是自己）
//       return item.index !== index;
//     },
//     hover(item, monitor) {
//       if (!ref.current) return;

//       const dragIndex = item.index;
//       const hoverIndex = index;

//       // 不要拖到自己身上
//       if (dragIndex === hoverIndex) return;

//       // 设置高亮状态
//       setIsOver(true);
//     },
//     drop(item, monitor) {
//       // 松开鼠标时执行交换
//       const dragIndex = item.index;
//       const hoverIndex = index;

//       if (dragIndex !== hoverIndex) {
//         moveCard(dragIndex, hoverIndex);
//       }

//       // 清除高亮
//       setIsOver(false);
//     },
//     // 离开时清除高亮
//     leave() {
//       setIsOver(false);
//     },
//   });

//   // 绑定拖拽和放置
//   drag(drop(ref));

//   // 卡片样式：拖拽时半透明 + 放大 + 阴影
//   const opacity = isDragging ? 0.7 : 1;
//   const transform = isDragging ? 'scale(1.05)' : 'none';
//   const boxShadow = isDragging ? '0 4px 16px rgba(0, 0, 0, 0.2)' : '0 1px 4px rgba(0, 0, 0, 0.08)';
//   const border = isOver ? '2px solid #1890ff' : '1px solid #d9d9d9'; // 高亮边框

//   const paramEntries = paramValue ? Object.entries(paramValue) : [];

//   return (
//     <div
//       ref={ref}
//       className={styles.card}
//       onClick={onClick}
//       style={{
//         opacity,
//         transform,
//         boxShadow,
//         border,
//         transition: 'all 0.2s ease',
//         cursor: isDragging ? 'grabbing' : 'grab',
//       }}
//     >
//       {/* 删除按钮 */}
//       <button
//         className={styles.deleteBtn}
//         onClick={(e) => {
//           e.stopPropagation();
//           onDelete(index);
//         }}
//       >
//         ×
//       </button>

//       {/* 拖拽手柄 - 左上角 */}
//       <div
//         ref={dragHandleRef}
//         className={styles.dragHandle}
//         draggable="true"
//         onDragStart={(e) => {
//           e.preventDefault();
//         }}
//       >
//         ≡
//       </div>

//       {/* 序号 + 标题 */}
//       <div className={styles.header}>
//         <span className={styles.order}>[{index + 1}]</span>
//         <h3 className={styles.title}>{fault.name}</h3>
//       </div>

//       {/* 参数列表 */}
//       <div className={styles.params}>
//         {paramEntries.length > 0 ? (
//           paramEntries.map(([key, value], idx) => (
//             <div key={idx} className={styles.paramLine}>
//               {key}: {value}
//             </div>
//           ))
//         ) : (
//           <div className={styles.paramLine}>无参数</div>
//         )}
//       </div>
//     </div>
//   );
// };

// const FaultsList = ({ faults, paramsValue, onClick, onDelete, onReorder }) => {
//   // 移动卡片的函数
//   const moveCard = (dragIndex, hoverIndex) => {
//     const draggedItem = faults[dragIndex];
//     const newFaults = [...faults];
//     newFaults.splice(dragIndex, 1);
//     newFaults.splice(hoverIndex, 0, draggedItem);

//     // 同步 paramsValue 顺序
//     const newParams = {};
//     newFaults.forEach((fault, idx) => {
//       newParams[fault.id] = paramsValue[fault.id] || {};
//     });

//     onReorder(newFaults, newParams);
//   };

//   return (
//     <div className={styles.container}>
//       {faults.map((fault, index) => (
//         <FaultListItem
//           key={fault.id}
//           onClick={() => onClick(index)}
//           onDelete={onDelete}
//           fault={fault}
//           paramValue={paramsValue[fault.id] || {}}
//           index={index}
//           moveCard={moveCard}
//         />
//       ))}
//     </div>
//   );
// };

// export default FaultsList;
