import { createContext ,useContext,useState} from "react";
import {locales,DEFAULT_LANGUAGE} from "./locals";
const  LanguageContext = createContext(DEFAULT_LANGUAGE);

// 取翻译文本；vars 用于替换文案里的 {name} 占位符。
// 当前语言缺这个 key 时回退到中文，再找不到才返回 key（避免英文界面直接露出 key 名）。
const resolveText = (lang, key, vars) => {
    const template = locales[lang]?.[key] ?? locales[DEFAULT_LANGUAGE]?.[key] ?? key;
    if (!vars) return template;
    return String(template).replace(/\{(\w+)\}/g, (match, name) =>
        vars[name] === undefined || vars[name] === null ? match : String(vars[name])
    );
};

// 指定语言取文案（用于把后端中文名反查回 nameKey 等非渲染场景）。
export const translateInLang = (lang, key, vars) => resolveText(lang, key, vars);

// 供非组件模块（如 api 层）使用：按持久化的语言取文案。
export const translateText = (key, vars) => {
    const lang = (typeof localStorage !== 'undefined' && localStorage.getItem('language')) || DEFAULT_LANGUAGE;
    return resolveText(lang, key, vars);
};
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
    const translate = (key, vars) => resolveText(lang, key, vars);
    return (
        <LanguageContext.Provider value={{lang,toggleLanguage,translate}}>
            {children}
        </LanguageContext.Provider>
    )
}

