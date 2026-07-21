import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Tauri serves the frontend on 5173 in dev and from ui/dist in release.
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    target: "es2021",
  },
});
