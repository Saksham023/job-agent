import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The UI talks to the Spring Boot API through /api. In development Vite forwards it to the running app
// (API_TARGET, default http://localhost:8080), so the browser sees one origin and no CORS setup is needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { '/api': process.env.API_TARGET ?? 'http://localhost:8080' },
  },
})
