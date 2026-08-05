import { defineConfig } from "vite";
import { noderaVite } from "nodera-ui/vite";

// React, Tailwind and the platform seam all come from the shared kit, so the desktop and the website
// cannot drift on which React plugin, which Tailwind version, or which host they build against. What
// stays here is the two facts true of this application and of nothing else: Tauri serves the
// frontend on 5173 in dev and reads `ui/dist` in release.
export default defineConfig({
  plugins: noderaVite({ platform: "tauri" }),
  server: { port: 5173, strictPort: true },
  build: { outDir: "dist" },
});
