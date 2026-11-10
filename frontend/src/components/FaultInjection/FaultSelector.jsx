import styles from './FaultSelector.module.css';
import { FAULT_CATEGORIES } from '../../config/faultConfig';

// 分类 tab 标签（未知/新增分类直接展示分类名）
const CATEGORY_LABELS = {
  CPU: 'CPU',
  network: 'Network',
  memory: 'Memory',
  IO: 'I/O',
  process: 'Process',
  database: 'Database',
};

// 目录驱动的分类 tab（设计 §8）：只渲染 FAULT_CATEGORIES 中非空的分类。
// yaml 清空某分类（如全注释故障）→ 该 tab 自动消失，前端的故障列表完全由 yaml 决定。
const FaultSelector = ({ onClick, selectedType }) => {
  const options = Object.keys(FAULT_CATEGORIES)
    .filter((c) => Array.isArray(FAULT_CATEGORIES[c]) && FAULT_CATEGORIES[c].length > 0)
    .map((c) => ({ value: c, label: CATEGORY_LABELS[c] || c }));

  return (
    <div className={styles.container}>
      <ul className={styles.tabs}>
        {options.map((item) => (
          <li
            key={item.value}
            className={`${styles.tabItem} ${selectedType === item.value ? styles.active : ''}`}
            onClick={() => onClick(item.value)}
          >
            {item.label}
          </li>
        ))}
      </ul>
    </div>
  );
};

export default FaultSelector;
// import "./faultInjection.css"
// const FaultSelector = ({onClick,selectedType})=>{
//     return (<div className="fault-container">
//         <ul className="tabs">
//             <li
//                 id="CPU"
//                 onClick={()=>onClick('CPU')}
//                 className="tab-item active"
//             >
//             CPU
//             </li>
//             <li
//                 className="tab-item active"
//                 id="network"
//                 value='network'
//                 onClick={()=>onClick('network')}
//             >
//             network
//             </li>
//             <li
//                 id="memory"
//                 value='memory'
//                 className="tab-item active"
//                 onClick={()=>onClick('memory')}
//             >
//             memory
//             </li>
//             <li
//                 id="disk"
//                 value='disk'
//                 onClick={()=>onClick('disk')}
//                 className="tab-item active"
//             >
//             disk
//             </li>
//         </ul>
//     </div>
//     )
// }
// export default FaultSelector;