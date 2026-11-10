import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/suite': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
      '/fault': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
      '/monitoring': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
    },
  },
})
