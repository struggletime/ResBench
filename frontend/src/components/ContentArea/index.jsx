// src/components/ContentArea.jsx
import React from 'react';
import { useSelector } from 'react-redux';
import { useLanguage } from '../../i18n';
import DatabaseConfig from '../DatabaseConfig'; // 导入新组件
import  "./ContentArea.css";
import WorkloadConfig from "../WorkLoadConfig";
import FaultInjection from "../FaultInjection";
import HistoryTable from "../History";
import ComparisonPage from "../ComparisonPage";
import MonitoringPage from "../MonitoringPage";
import Header from "./Header";

const ContentArea = () => {
  const { topTab, activeTab } = useSelector((state) => state.tab);
  const { translate } = useLanguage();

  // 根据 activeTab 渲染不同的组件
  const renderContent = () => {
    switch (activeTab) {
      case 'database':
        return <DatabaseConfig />;
      case 'workload':
        return <WorkloadConfig />;
      case 'fault':
          return <FaultInjection/>;
      case 'monitoring':
          return <MonitoringPage />;
      case 'history':
          return <HistoryTable />;
      case 'compare':
          return <ComparisonPage />;
        // return <FaultInjectionConfig />;
      // ... 其他 case
      default:
        return <div className="placeholder">{translate('ContentArea.placeholder')}</div>;
    }
  };

  return (
    <div className="content-area">
      <Header topTab={topTab} activeTab={activeTab}/>
      <div className='content-area-content'>
        {renderContent()}
      </div>
    </div>
  )
};

export default ContentArea;