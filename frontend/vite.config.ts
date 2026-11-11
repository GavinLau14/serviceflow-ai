import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    port: 15173,
    strictPort: true,
    proxy: {
      "/api":
        loadEnv(mode, "..", "SERVICEFLOW_").SERVICEFLOW_PROXY_TARGET ||
        "http://127.0.0.1:18081",
    },
  },
}));
