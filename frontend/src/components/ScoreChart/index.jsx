import { useLanguage } from "../../i18n";
import RadarChart from "./RadarChart";
import RadarTable from "./RadarTable";
import radarScoresData from "../../data/radarScores.json"; 
// ScoreChart.jsx

const ScoreChart = () => {
  const { translate } = useLanguage();

  // const getRadarData = () => {
  //   return [
  //     { name: translate('RadarChart.throughput'), value: 80 },
  //     { name: translate('RadarChart.latency'), value: 60 },
  //     { name: translate('RadarChart.stability'), value: 70 },
  //     { name: translate('RadarChart.resistance'), value: 50 },
  //     { name: translate('RadarChart.recovery'), value: 40 },
  //     { name: translate('RadarChart.adaptability'), value: 90 },
  //     { name: translate('RadarChart.volatility'), value: 75 },
  //     { name: translate('RadarChart.rto'), value: 85 },
  //   ];
  // };
  // ✅ 从 JSON 构造带翻译名称的 scores 数组
  const scores = radarScoresData.map(item => ({
    name: translate(`RadarChart.${item.metric}`), // 用于雷达图（如果还用）
    value: item.value,
    metricKey: item.metric // 用于表格 tooltip 匹配
  }));

  // const radarData = getRadarData(); // 修复拼写：radarDate → radarData

  return (
    <div>
      <RadarChart
        title={translate('RadarChart.title')}
        data={scores}
      />
      <RadarTable scores={scores} />
    </div>
  );
};

export default ScoreChart;