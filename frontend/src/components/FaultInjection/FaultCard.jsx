import faultCardStyle from "./FaultCard.module.css";

const FaultCard = ({ value, order, type, isSelected, onClick }) => {
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
        <span className={faultCardStyle.noCheckTag} title="未配置自检，注入后无法验证效果">
          无自检
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