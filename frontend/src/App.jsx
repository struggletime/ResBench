import  Sidebar from "./components/Sidebar";
import ContentArea from "./components/ContentArea";
import AppWrapper from "./components/AppWrapper";
import MonitorPoller from "./components/MonitorPoller";
import ObservabilityPoller from "./components/ObservabilityPoller";
import GuidedTour from "./components/GuidedTour";
import FaultInjectionTour from "./components/GuidedTour/FaultInjectionTour";
import { useEffect } from "react";
import { hydrateFaultCatalog } from "./config/faultConfig";
import './App.css'

function App() {
  // 启动时从后端 yaml 故障目录动态合并故障定义（后端不可用时静默保持内置列表）
  useEffect(() => {
    hydrateFaultCatalog();
  }, []);

  return (
    <AppWrapper>
      <MonitorPoller />
      <ObservabilityPoller />
      <Sidebar />
      <ContentArea />
      <GuidedTour />
      <FaultInjectionTour />
    </AppWrapper>
  )
}

export default App
