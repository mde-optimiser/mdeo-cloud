import { defineConfig } from "vite";
import { resolve } from "path";

export default defineConfig({
    build: {
        lib: {
            entry: {
                language: resolve(__dirname, "src/served/language.ts")
            },
            formats: ["es"]
        },
        outDir: "static",
        emptyOutDir: true,
        sourcemap: true
    }
});
