import { defineConfig } from "vite";
import { resolve } from "path";

export default defineConfig({
    build: {
        lib: {
            entry: {
                language: resolve(__dirname, "src/served/language.ts"),
                generatedLanguage: resolve(__dirname, "src/served/generatedLanguage.ts"),
                editor: resolve(__dirname, "src/served/editor.ts"),
                gedWorker: resolve(__dirname, "../language-model/src/features/diagram-server/gedWorker.ts")
            },
            formats: ["es"],
            cssFileName: "styles"
        },
        outDir: "static",
        emptyOutDir: true,
        sourcemap: true
    }
});
