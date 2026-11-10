import { useSelector } from 'react-redux';
import RealTimeThroughputChart from "./RealTimeThroughputChart";
import VerificationBadges from "../History/VerificationBadges";

const RealTimeChart = () => {
    const faultInjectInfo = useSelector((state) => state.monitor?.faultInjectInfo || []);
    return (
        <div>
            <VerificationBadges faultInjectInfo={faultInjectInfo} showEmpty={false} />
            <RealTimeThroughputChart />
        </div>
    )
}
export default RealTimeChart
