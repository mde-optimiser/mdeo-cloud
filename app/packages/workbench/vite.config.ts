import { fileURLToPath, URL } from "node:url";
import { defineConfig, type ProxyOptions } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

const addCoopCoepHeaders: ProxyOptions["configure"] = (proxy) => {
    proxy.on("proxyRes", (proxyRes) => {
        proxyRes.headers["Cross-Origin-Opener-Policy"] = "same-origin";
        proxyRes.headers["Cross-Origin-Embedder-Policy"] = "require-corp";
    });
};

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
                rewrite: (path) => path.replace(/^\/plugin\/model-transformation/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/metamodel": {
                target: "http://localhost:3000",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/metamodel/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/model": {
                target: "http://localhost:3001",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/model/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/script": {
                target: "http://localhost:3002",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/script/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config-optimization": {
                target: "http://localhost:3005",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config-optimization/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config-mdeo": {
                target: "http://localhost:3006",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config-mdeo/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config": {
                target: "http://localhost:3004",
                changeOrigin: true,
                secure: false,
                rewrite: (path) => path.replace(/^\/plugin\/config/, ""),
                configure: addCoopCoepHeaders
            },
            "/api": {
                target: "http://localhost:8080",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewriteWsOrigin: true,
                configure: addCoopCoepHeaders
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
