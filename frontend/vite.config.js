import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, the SPA runs on http://localhost:5173 and proxies /api to the Spring app.
// The production build goes to dist/, which Maven packages into the jar as static resources.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8765',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
