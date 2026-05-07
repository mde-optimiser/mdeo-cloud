import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    plugins: [vue(), tailwindcss()],
    resolve: {
        alias: {
            "@": fileURLToPath(new URL("./src", import.meta.url))
        }
    },
    worker: {
        format: "es"
    },
    server: {
        port: 4242,
        host: "127.0.0.1",
        headers: {
            "Cross-Origin-Opener-Policy": "same-origin",
            "Cross-Origin-Embedder-Policy": "require-corp"
        },
        proxy: {
            "/plugin/model-transformation": {
                target: "http://localhost:3003",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/model-transformation/, "")
            },
            "/plugin/metamodel": {
                target: "http://localhost:3000",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/metamodel/, "")
            },
            "/plugin/model": {
                target: "http://localhost:3001",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/model/, "")
            },
            "/plugin/script": {
                target: "http://localhost:3002",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/script/, "")
            },
            "/plugin/config-optimization": {
                target: "http://localhost:3005",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config-optimization/, "")
            },
            "/plugin/config-mdeo": {
                target: "http://localhost:3006",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config-mdeo/, "")
            },
            "/plugin/config": {
                target: "http://localhost:3004",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config/, "")
            },
            "/api": {
                target: "http://localhost:8080",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewriteWsOrigin: true
            }
        }
    },
    build: {
        rollupOptions: {
            output: {
                format: "es",
                manualChunks: undefined
            }
        }
    }
});
