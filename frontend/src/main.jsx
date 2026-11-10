import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { Provider } from 'react-redux'; // 导入 Provider
import { store } from "./store";
import { LanguageProvider } from './i18n';
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Provider store={store}>
      <LanguageProvider>
        <App />
      </LanguageProvider>
    </Provider>
  </StrictMode>,
)
