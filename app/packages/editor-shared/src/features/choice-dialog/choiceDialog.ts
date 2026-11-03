import type { VNode } from "snabbdom";
import type { Action, IActionDispatcher } from "@eclipse-glsp/sprotty";
import { sharedImport } from "../../sharedImport.js";

const { injectable, inject } = sharedImport("inversify");
const { html, TYPES } = sharedImport("@eclipse-glsp/sprotty");
const { init, classModule, propsModule, styleModule, eventListenersModule, attributesModule } =
    sharedImport("snabbdom");

/**
 * Visual weight of a choice in a {@link ChoiceDialog}.
 */
export type ChoiceDialogVariant = "primary" | "destructive" | "outline";

/**
 * One answer the user can give in a {@link ChoiceDialog}.
 */
export interface ChoiceDialogChoice {
    /**
     * The button label.
     */
    label: string;

    /**
     * The visual weight of the button; `outline` by default.
     */
    variant?: ChoiceDialogVariant;

    /**
     * The actions dispatched when the choice is picked.
     */
    actions: Action[];
}

/**
 * Content of a {@link ChoiceDialog}.
 */
export interface ChoiceDialogOptions {
    /**
     * The question asked.
     */
    title: string;

    /**
     * Additional explanation shown below the title.
     */
    message?: string;

    /**
     * The answers, in the order they are shown. The first one has the focus.
     */
    choices: ChoiceDialogChoice[];

    /**
     * Label of the button that closes the dialog without doing anything.
     */
    cancelLabel?: string;
}

/**
 * Modal dialog asking the user to pick one of a few answers, each of which dispatches actions.
 *
 * The dialog covers the diagram it belongs to, not the whole page. Escape, a click next to the
 * dialog, and the cancel button close it without dispatching anything.
 */
@injectable()
export class ChoiceDialog {
    @inject(TYPES.IActionDispatcher)
    protected readonly actionDispatcher!: IActionDispatcher;

    @inject(TYPES.ViewerOptions)
    protected readonly viewerOptions!: { baseDiv: string };

    /**
     * The dialog currently shown, if any.
     */
    protected current: ChoiceDialogOptions | undefined;

    /**
     * The most recently patched VNode of the dialog container.
     */
    protected currentVNode: VNode | undefined;

    /**
     * Snabbdom patcher initialised with the modules the dialog needs.
     */
    private readonly patcher = init([classModule, propsModule, styleModule, eventListenersModule, attributesModule]);

    /**
     * Shows the dialog, replacing one that is open already.
     *
     * @param options The content of the dialog
     */
    show(options: ChoiceDialogOptions): void {
        this.current = options;
        this.render();
    }

    /**
     * Closes the dialog without dispatching anything.
     */
    close(): void {
        if (this.current == undefined) {
            return;
        }
        this.current = undefined;
        this.render();
        this.baseDiv()?.focus();
    }

    /**
     * Whether the dialog is open.
     */
    get isOpen(): boolean {
        return this.current != undefined;
    }

    /**
     * Closes the dialog and dispatches the actions of a choice.
     *
     * @param choice The picked choice
     */
    protected choose(choice: ChoiceDialogChoice): void {
        this.close();
        void this.actionDispatcher.dispatchAll(choice.actions);
    }

    /**
     * Renders the dialog into its container, creating the container on first use.
     */
    protected render(): void {
        if (this.currentVNode == undefined) {
            const baseDiv = this.baseDiv();
            if (baseDiv == undefined) {
                return;
            }
            const placeholder = document.createElement("div");
            baseDiv.appendChild(placeholder);
            this.currentVNode = this.patcher(placeholder, html("div", null));
        }
        this.currentVNode = this.patcher(this.currentVNode, this.generateView());
    }

    /**
     * Returns the element the diagram is rendered into.
     *
     * @returns The base div, or `undefined` before the diagram is mounted
     */
    protected baseDiv(): HTMLElement | undefined {
        return document.getElementById(this.viewerOptions.baseDiv) ?? undefined;
    }

    /**
     * Generates the dialog VNode, or an empty container while the dialog is closed.
     *
     * @returns The VNode of the dialog container
     */
    protected generateView(): VNode {
        const options = this.current;
        if (options == undefined) {
            return html("div", { class: { "choice-dialog": true, hidden: true } });
        }

        const choices = options.choices.map((choice, index) =>
            html(
                "button",
                {
                    class: { ...buttonClasses, ...variantClasses[choice.variant ?? "outline"] },
                    style: choice.variant === "destructive" ? { color: "#fff" } : {},
                    attrs: { type: "button", "data-choice-index": index },
                    hook: index === 0 ? { insert: (vnode) => (vnode.elm as HTMLElement).focus() } : undefined,
                    on: { click: () => this.choose(choice) }
                },
                choice.label
            )
        );
        const cancel = html(
            "button",
            {
                class: { ...buttonClasses, ...variantClasses.outline },
                attrs: { type: "button" },
                on: { click: () => this.close() }
            },
            options.cancelLabel ?? "Cancel"
        );

        return html(
            "div",
            {
                class: {
                    "choice-dialog": true,
                    absolute: true,
                    "inset-0": true,
                    flex: true,
                    "items-center": true,
                    "justify-center": true,
                    "pointer-events-auto": true
                },
                // The editors ship Tailwind's utilities without its default theme, so plain
                // colors like black are not available as classes.
                style: { backgroundColor: "rgba(0, 0, 0, 0.3)", zIndex: "60" },
                attrs: { role: "presentation" },
                on: {
                    mousedown: (event: MouseEvent) => {
                        event.stopPropagation();
                        if (event.target === event.currentTarget) {
                            this.close();
                        }
                    },
                    keydown: (event: KeyboardEvent) => {
                        event.stopPropagation();
                        if (event.key === "Escape") {
                            event.preventDefault();
                            this.close();
                        }
                    },
                    wheel: (event: WheelEvent) => event.stopPropagation()
                }
            },
            html(
                "div",
                {
                    class: {
                        flex: true,
                        "flex-col": true,
                        "gap-4": true,
                        "w-[min(28rem,calc(100%-2rem))]": true,
                        "rounded-lg": true,
                        border: true,
                        "border-border": true,
                        "bg-popover": true,
                        "p-5": true,
                        "shadow-[0_8px_16px_rgba(0,0,0,0.12)]": true,
                        "text-foreground": true
                    },
                    attrs: { role: "alertdialog", "aria-modal": "true", "aria-label": options.title }
                },
                html(
                    "div",
                    { class: { flex: true, "flex-col": true, "gap-1.5": true } },
                    html("h2", { class: { "text-sm": true, "font-semibold": true, "m-0": true } }, options.title),
                    options.message != undefined
                        ? html(
                              "p",
                              { class: { "text-sm": true, "text-muted-foreground": true, "m-0": true } },
                              options.message
                          )
                        : undefined
                ),
                html(
                    "div",
                    { class: { flex: true, "flex-wrap": true, "justify-end": true, "gap-2": true } },
                    cancel,
                    ...choices.reverse()
                )
            )
        );
    }
}

/**
 * Classes shared by all dialog buttons.
 */
const buttonClasses = {
    "inline-flex": true,
    "items-center": true,
    "justify-center": true,
    "h-8": true,
    "px-3": true,
    "rounded-md": true,
    "text-sm": true,
    "font-medium": true,
    "cursor-pointer": true,
    "transition-colors": true,
    "focus-visible:outline-none": true,
    "focus-visible:ring-2": true,
    "focus-visible:ring-ring": true,
    "focus-visible:ring-offset-2": true
};

/**
 * Classes of each button variant.
 */
const variantClasses: Record<ChoiceDialogVariant, Record<string, boolean>> = {
    primary: {
        "border-0": true,
        "bg-primary": true,
        "text-background": true,
        "hover:bg-primary/90": true
    },
    destructive: {
        "border-0": true,
        "bg-destructive": true,
        "hover:bg-destructive/90": true
    },
    outline: {
        border: true,
        "border-input": true,
        "bg-transparent": true,
        "text-foreground": true,
        "hover:bg-accent": true,
        "hover:text-accent-foreground": true
    }
};
