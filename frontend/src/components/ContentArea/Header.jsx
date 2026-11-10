import { useLanguage } from "../../i18n";
import headerStyle from "./Header.module.css";
const LanguageToggleBtn = ()=>{
    const {toggleLanguage,translate} = useLanguage();

    return (
        <button
            onClick={toggleLanguage}
            className={headerStyle.toggleButton}
            >
            {translate('languageToggle')}
        </button>
    )
}
const GuidedTourBtn = ()=>{
    const {translate} = useLanguage();

    const handleClick = () => {
        if (window.__startGuidedTour) {
            window.__startGuidedTour();
        }
    };

    return (
        <button
            onClick={handleClick}
            className={headerStyle.toggleButton}
            >
            {translate('tour.button.replay')}
        </button>
    )
}

const FaultInjectionTourBtn = ()=>{
    const {translate} = useLanguage();

    const handleClick = () => {
        if (window.__startFaultInjectionTour) {
            window.__startFaultInjectionTour();
        }
    };

    return (
        <button
            onClick={handleClick}
            className={headerStyle.toggleButton}
            >
            {translate('tour.button.faultTour')}
        </button>
    )
}
const Header = ({ topTab, activeTab }) => {
    const {translate} = useLanguage()
    const renderHeaderTitle = (currentTopTab, currentSubTab)=>{
        const topTitle = translate('SidebarItem.' + currentTopTab);
        const subTitle = translate('SidebarItem.' + currentSubTab);

        if (topTitle && subTitle) {
            return `${topTitle} / ${subTitle}`;
        }

        if (subTitle) {
            return subTitle;
        }

        return 'unknown type';
    }
    return (
        <div>
            <div className={headerStyle['header']}>
                <span className={headerStyle['header-title']}>{renderHeaderTitle(topTab, activeTab)}</span>
                <div className={headerStyle['header-actions']}>
                    <GuidedTourBtn/>
                    <FaultInjectionTourBtn/>
                    <LanguageToggleBtn/>
                </div>
            </div>
        </div>
    )
}
export default Header;
