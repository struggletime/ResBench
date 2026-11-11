import faultCardStyle from "./FaultCard.module.css";
import { useLanguage } from "../../i18n";

const FaultCard = ({ value, order, type, isSelected, onClick }) => {
  const { translate } = useLanguage();
  return (
    <div
      className={`${faultCardStyle.card} ${isSelected ? faultCardStyle.active : ''}`}
      onClick={() => onClick({ fault: value, type, isSelected })}
    >
      <div className={`${faultCardStyle.icon} ${isSelected ? faultCardStyle.iconActive : ''}`}>
        {isSelected ? (
          <span className={faultCardStyle.number}>{order}</span>
        ) : (
          <span className={faultCardStyle.dot}></span>
        )}
      </div>
      <span className={faultCardStyle.text}>{value.name}</span>
      {value.hasCheck === false && (
        <span className={faultCardStyle.noCheckTag} title={translate('faultCard.noCheckHint')}>
          {translate('faultCard.noCheck')}
        </span>
      )}
    </div>
  );
};

export default FaultCard;
// import faultCardStyle from "./FaultCard.module.css";

// const FaultCard = ({ value, order, type, isSelected, onClick }) => {
//   return (
//     <div
//       className={`${faultCardStyle.card} ${isSelected ? faultCardStyle.active : ''}`}
//       onClick={() => onClick({ fault: value, type, isSelected })}
//     >
//       <div className={faultCardStyle.icon}>
//         {isSelected ? (
//           <span className={faultCardStyle.number}>{order}</span>
//         ) : (
//           <span className={faultCardStyle.dot}></span> 
//         )}
//       </div>
//       <span className={faultCardStyle.text}>{value.name}</span>
//     </div>
//   );
// };


// export default FaultCard;