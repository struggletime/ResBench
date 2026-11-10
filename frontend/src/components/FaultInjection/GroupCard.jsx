import React, { useRef, useState } from 'react';
import { useDrag, useDrop } from 'react-dnd';
import styles from './FaultList.module.css';

const GroupCard = ({
  groupId,
  name,
  color,
  faults,
  groupDuration,
  isExpanded,
  onToggleExpand,
  onClickFault,
  onDeleteFault,
  onDeleteGroup,
  onInnerReorder,
  onUpdateGroupDuration,
  index,
  moveCard,
}) => {
  const ref = useRef(null);
  const [hoveredInnerIndex, setHoveredInnerIndex] = useState(null);

  // 整个组合卡片的拖拽
  const [{ isDragging }, drag] = useDrag({
    type: 'FAULT_CARD',
    item: { index, type: 'group', id: groupId },
    collect: (monitor) => ({
      isDragging: monitor.isDragging(),
    }),
  });

  const [, drop] = useDrop({
    accept: 'FAULT_CARD',
    canDrop: (item) => item.index !== index,
    hover(item) {
      if (!ref.current) return;
      const dragIndex = item.index;
      const hoverIndex = index;
      if (dragIndex === hoverIndex) return;
    },
    drop(item) {
      const dragIndex = item.index;
      const hoverIndex = index;
      if (dragIndex !== hoverIndex) {
        moveCard(dragIndex, hoverIndex, item.type);
      }
    },
  });

  drag(drop(ref));

  const paramEntries = faults.map((fault, idx) => {
    return `${idx + 1}: ${fault.name}`;
  });

  return (
    <div
      ref={ref}
      className={styles.card}
      style={{
        opacity: isDragging ? 0.7 : 1,
        transform: isDragging ? 'scale(1.05)' : 'none',
        boxShadow: isDragging ? '0 4px 16px rgba(0, 0, 0, 0.2)' : '0 1px 4px rgba(0, 0, 0, 0.08)',
        borderLeft: `4px solid ${color || '#1890ff'}`,
        cursor: isDragging ? 'grabbing' : 'grab',
      }}
    >
      <div className={styles.header} style={{ cursor: 'pointer' }} onClick={onToggleExpand}>
        <span className={styles.order}>[{index + 1}]</span>
        <h3 className={styles.title}>{name}</h3>
        <span style={{ marginLeft: 8, color: '#666', fontSize: 12 }}>({faults.length})</span>
        <span style={{ marginLeft: 'auto', fontSize: 12, marginRight: 8 }}>{isExpanded ? '▼' : '▶'}</span>
        <button
          onClick={(e) => { e.stopPropagation(); onDeleteGroup(groupId); }}
          style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 14, padding: '2px 6px' }}
        >×</button>
      </div>

      <div className={styles.params}>
        {isExpanded ? (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0' }}>
              <span style={{ fontSize: 12, color: '#555' }}>Duration</span>
              <input
                type="number"
                value={groupDuration ?? '60'}
                onChange={(e) => onUpdateGroupDuration && onUpdateGroupDuration(groupId, e.target.value)}
                style={{ width: 60, padding: '2px 6px', fontSize: 13, border: '1px solid #d9d9d9', borderRadius: 4 }}
              />
              <span style={{ fontSize: 12, color: '#666' }}>s</span>
            </div>
            {faults.map((fault, innerIndex) => (
            <InnerFaultItem
              key={fault.id}
              fault={fault}
              innerIndex={innerIndex}
              groupId={groupId}
              onClickFault={onClickFault}
              onDeleteFault={onDeleteFault}
              onInnerReorder={onInnerReorder}
              hoveredInnerIndex={hoveredInnerIndex}
              setHoveredInnerIndex={setHoveredInnerIndex}
            />
          ))}
          </>
        ) : (
          paramEntries.map((line, idx) => (
            <div key={idx} className={styles.paramLine}>{line}</div>
          ))
        )}
      </div>
    </div>
  );
};

// 内部故障项组件
const InnerFaultItem = ({
  fault,
  innerIndex,
  groupId,
  onClickFault,
  onDeleteFault,
  onInnerReorder,
  hoveredInnerIndex,
  setHoveredInnerIndex,
}) => {
  const innerRef = useRef(null);

  const [{ isInnerDragging }, innerDrag] = useDrag({
    type: 'INNER_FAULT_CARD',
    item: { innerIndex, groupId },
    collect: (monitor) => ({
      isInnerDragging: monitor.isDragging(),
    }),
  });

  const [, innerDrop] = useDrop({
    accept: 'INNER_FAULT_CARD',
    canDrop: (item) => item.groupId === groupId && item.innerIndex !== innerIndex,
    hover(item) {
      if (item.groupId !== groupId) return;
      const dragIndex = item.innerIndex;
      const hoverIndex = innerIndex;
      if (dragIndex === hoverIndex) return;
      setHoveredInnerIndex(hoverIndex);
    },
    drop(item) {
      if (item.groupId !== groupId) return;
      const dragIndex = item.innerIndex;
      const hoverIndex = innerIndex;
      if (dragIndex !== hoverIndex) {
        onInnerReorder(groupId, dragIndex, hoverIndex);
      }
      setHoveredInnerIndex(null);
    },
    leave() {
      setHoveredInnerIndex(null);
    },
  });

  innerDrag(innerDrop(innerRef));

  return (
    <div
      ref={innerRef}
      className={styles.paramLine}
      style={{
        display: 'flex',
        alignItems: 'center',
        padding: '4px 8px',
        margin: '4px 0',
        background: isInnerDragging ? '#e6f7ff' : hoveredInnerIndex === innerIndex ? '#f0f0f0' : '#f5f5f5',
        borderRadius: 4,
        cursor: 'pointer',
        opacity: isInnerDragging ? 0.5 : 1,
      }}
      onClick={() => onClickFault(groupId, fault.id)}
    >
      <span style={{
        width: 18,
        height: 18,
        background: '#1890ff',
        color: 'white',
        borderRadius: '50%',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: 11,
        marginRight: 8
      }}>
        {innerIndex + 1}
      </span>
      <span style={{ flex: 1 }}>{fault.name}</span>
      <button
        style={{
          background: 'none',
          border: 'none',
          color: '#999',
          cursor: 'pointer',
          fontSize: 14,
          padding: '0 4px',
        }}
        onClick={(e) => {
          e.stopPropagation();
          onDeleteFault(groupId, fault.id);
        }}
      >
        ×
      </button>
    </div>
  );
};

export default GroupCard;
