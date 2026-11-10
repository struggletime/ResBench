import { createContext ,useContext,useState} from "react";
import {locales,DEFAULT_LANGUAGE} from "./locals";
const  LanguageContext = createContext(DEFAULT_LANGUAGE);
// 自定义 Hook
export const useLanguage = () => {
  const context = useContext(LanguageContext);
  if (!context) {
    throw new Error('useLanguage must be used within a LanguageProvider');
  }
  return context;
};
export const LanguageProvider = ({children}) =>{
    const getInitialLang = () => {
        return localStorage.getItem('language') || DEFAULT_LANGUAGE;
    };
    const [lang, setLang] = useState(getInitialLang);
      // 切换语言函数
    const toggleLanguage = () => {
        const newLang = lang === 'zh' ? 'en' : 'zh';
        setLang(newLang);
        localStorage.setItem('language', newLang); // 持久化
    };
    // 获取翻译文本
    const translate = (key) => {
        return locales[lang]?.[key] || key; // 如果找不到 key，返回原 key
    };
    return (
        <LanguageContext.Provider value={{lang,toggleLanguage,translate}}>
            {children}
        </LanguageContext.Provider>
    )
}

