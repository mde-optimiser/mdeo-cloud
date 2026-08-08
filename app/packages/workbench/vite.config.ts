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
        // Matched by prefix in declaration order, so a longer path has to come
        // before any shorter path it starts with: model-transformation and
        // model-csv before model, config-optimization and config-mdeo before
        // config, and plain csv last.
        proxy: {
            "/plugin/model-transformation": {
                target: "http://localhost:3004",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/model-transformation/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/model-csv": {
                target: "http://localhost:3007",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/model-csv/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/metamodel": {
                target: "http://localhost:3001",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/metamodel/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/model": {
                target: "http://localhost:3002",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/model/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/script": {
                target: "http://localhost:3003",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/script/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config-optimization": {
                target: "http://localhost:3006",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/config-optimization/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config-mdeo": {
                target: "http://localhost:3008",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/config-mdeo/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/config": {
                target: "http://localhost:3005",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/config/, ""),
                configure: addCoopCoepHeaders
            },
            "/plugin/csv": {
                target: "http://localhost:3008",
                changeOrigin: true,
                secure: false,
                ws: true,
                rewrite: (path) => path.replace(/^\/plugin\/csv/, ""),
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
