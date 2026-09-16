import { defineConfig } from "vitepress";
import { mdeoLanguages } from "./languages.js";

const repository = "https://github.com/mde-optimiser/mdeo-cloud";

export default defineConfig({
    title: "MDEO Cloud",
    description: "A cloud-native, plugin-based platform for model-driven engineering optimisation.",
    base: "/mdeo-cloud/",
    cleanUrls: true,
    lastUpdated: true,
    head: [["link", { rel: "icon", href: "/mdeo-cloud/favicon.svg" }]],
    markdown: {
        languages: mdeoLanguages
    },
    themeConfig: {
        logo: "/favicon.svg",
        nav: [
            { text: "Guide", link: "/guide/", activeMatch: "/guide/" },
            { text: "Plugins", link: "/plugins/", activeMatch: "/plugins/" },
            { text: "Develop", link: "/develop/", activeMatch: "/develop/" }
        ],
        sidebar: {
            "/guide/": [
                {
                    text: "Introduction",
                    items: [
                        { text: "What is MDEO Cloud?", link: "/guide/" },
                        { text: "Core concepts", link: "/guide/concepts" },
                        { text: "Architecture", link: "/guide/architecture" }
                    ]
                },
                {
                    text: "Using the platform",
                    items: [
                        { text: "Getting started", link: "/guide/getting-started" },
                        { text: "The workbench", link: "/guide/workbench" },
                        { text: "Projects and plugins", link: "/guide/projects-and-plugins" }
                    ]
                },
                {
                    text: "Walkthrough",
                    items: [
                        { text: "Optimising a task allocation", link: "/guide/walkthrough" },
                        { text: "Reading the results", link: "/guide/results" }
                    ]
                },
                {
                    text: "Operations",
                    items: [{ text: "Deployment", link: "/guide/deployment" }]
                }
            ],
            "/plugins/": [
                {
                    text: "Plugins",
                    items: [
                        { text: "Overview", link: "/plugins/" },
                        { text: "Metamodel", link: "/plugins/metamodel" },
                        { text: "Model", link: "/plugins/model" },
                        { text: "Model Transformation", link: "/plugins/model-transformation" },
                        { text: "Script", link: "/plugins/script" },
                        { text: "Config", link: "/plugins/config" },
                        { text: "Config Optimization", link: "/plugins/config-optimization" },
                        { text: "Config MDEO", link: "/plugins/config-mdeo" },
                        { text: "CSV", link: "/plugins/csv" },
                        { text: "Model CSV", link: "/plugins/model-csv" }
                    ]
                }
            ],
            "/develop/": [
                {
                    text: "The extension model",
                    items: [
                        { text: "Overview", link: "/develop/" },
                        { text: "Anatomy of a plugin", link: "/develop/plugin-anatomy" },
                        { text: "Packages and runtime dependencies", link: "/develop/package-structure" },
                        { text: "Plugin manifest reference", link: "/develop/manifest" },
                        { text: "Extension points", link: "/develop/extension-points" }
                    ]
                },
                {
                    text: "Building a plugin",
                    items: [
                        { text: "Add a plugin", link: "/develop/add-a-plugin" },
                        { text: "Add a language", link: "/develop/add-a-language" },
                        { text: "The grammar DSL", link: "/develop/grammar" },
                        { text: "Graphical editors", link: "/develop/graphical-editors" }
                    ]
                },
                {
                    text: "Extending other languages",
                    items: [
                        { text: "Contribution plugins", link: "/develop/contribution-plugins" },
                        { text: "Config contributions", link: "/develop/config-contributions" },
                        { text: "Script contributions", link: "/develop/script-contributions" }
                    ]
                },
                {
                    text: "Reference",
                    items: [
                        { text: "Language service HTTP API", link: "/develop/service-api" },
                        { text: "Sessions", link: "/develop/sessions" },
                        { text: "Kotlin plugin services", link: "/develop/kotlin-plugin-service" },
                        { text: "The script-functions protocol", link: "/develop/script-functions-protocol" },
                        { text: "Local development", link: "/develop/local-development" }
                    ]
                }
            ]
        },
        socialLinks: [{ icon: "github", link: repository }],
        editLink: {
            pattern: `${repository}/edit/main/website/docs/:path`,
            text: "Edit this page on GitHub"
        },
        search: {
            provider: "local"
        },
        outline: [2, 3],
        footer: {
            message: "Released under the terms of the repository licence.",
            copyright: "MDEO Cloud"
        }
    }
});
