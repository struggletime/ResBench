// src/components/SelectedRecordsList/SelectedRecordsList.jsx
import React from 'react';
import styles from './SelectedRecordsList.module.css';
import { useLanguage } from '../../i18n';

const SelectedRecordsList = ({ selectedRecords, onRemove }) => {
  const { translate } = useLanguage();

  if (selectedRecords.length === 0) {
    return null;
  }

  // ✅ 方案一：如果你的 translate 支持参数（推荐）
  // const title = translate('comparison.selectedRecords.title', {
  //   count: selectedRecords.length
  // });

  // ✅ 方案二：如果不支持参数，使用前后缀
  const title = `${translate('comparison.selectedRecords.titlePrefix')} ${selectedRecords.length} ${translate('comparison.selectedRecords.titleSuffix')}`;

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>
        {title}
      </h3>
      <div className={styles.list}>
        {selectedRecords.map((record) => (
          <div key={record.id} className={styles.card}>
            <span>{record.id}</span>
            <span>·</span>
            <span>{record.testName || translate('comparison.selectedRecords.noName')}</span>
            <button
              className={styles.removeBtn}
              onClick={() => onRemove(record.id)}
              aria-label={`${translate('comparison.selectedRecords.removeAriaPrefix')} ${record.id}`}
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default SelectedRecordsList;
// src/components/SelectedRecordsList/SelectedRecordsList.jsx
// import React from 'react';
// import styles from './SelectedRecordsList.module.css';

// const SelectedRecordsList = ({ selectedRecords, onRemove }) => {
//   if (selectedRecords.length === 0) {
//     return null;
//   }

//   return (
//     <div className={styles.container}>
//       <h3 className={styles.title}>
//         已选测试（共 {selectedRecords.length} 条）
//       </h3>
//       <div className={styles.list}>
//         {selectedRecords.map((record) => (
//           <div key={record.id} className={styles.card}>
//             <span>{record.id}</span>
//             <span>·</span>
//             <span>{record.testName || '无名称'}</span>
//             <button
//               className={styles.removeBtn}
//               onClick={() => onRemove(record.id)}
//               aria-label={`移除 ${record.id}`}
//             >
//               ×
//             </button>
//           </div>
//         ))}
//       </div>
//     </div>
//   );
// };

// export default SelectedRecordsList;