# The workbench

The workbench is the whole user interface. It runs entirely in the browser and is where you create
projects, edit files and start runs.

## Layout

A rail on the left switches between four panels:

| Panel | Contents |
| --- | --- |
| **Projects** | Every project you can see, and the actions to create, rename and delete them |
| **Files** | The file tree of the current project |
| **Search** | Full-text search across the project's files |
| **Executions** | Runs started from this project, with their status and results |

Administrators also get a **Settings** entry for user and plugin management. Below the rail are the
account menu and the light/dark toggle.

The main area holds tabs. Each tab is one open file.

## Editing files

New files are created from the file tree. The extension decides the language, and therefore the
editor, the validation and the actions available on the file. Languages whose plugin marks them with
a *new file action* — models and model transformations — open a short dialog on creation, because
they need to know which metamodel they belong to.

### Textual editing

Text editing uses Monaco, driven by the language server running in a web worker. You get the usual
things: syntax highlighting, completion, hover information, diagnostics as you type, go-to-definition
across files, rename, and formatting.

Diagnostics are not just parse errors. Each language brings its own validation — an association
operator that requires a property name on the other end, an objective function with the wrong
signature, a composition that would give an object two parents. Those checks are the same ones the
server applies before an execution starts, so an editor without red squiggles means a file that will
run.

### Graphical editing

Metamodels, models and model transformations also have a diagram editor. It is not a preview: nodes,
edges, labels and the tool palette all edit the underlying file, and the text updates accordingly.
Switch between the two views from the editor's toolbar.

Copy and paste work inside a diagram and between diagrams of the same language.

## Cross-file references

References across files are written as relative paths:

```mm
import "./shapes.mm"
```

```fn
using "./tasks.mm"
import { unassignedEffort } from "./objectives.fn"
```

The language server resolves them inside the project, so completion in a config file offers the
functions actually defined in the script file it imports, and renaming a metamodel class updates the
models that instantiate it.

## Running things

Files whose language declares an action get a run entry. What that means depends on the language:

- a **script** function can be executed against a model;
- a **model transformation** can be applied to a model;
- a **config** file with an executable section — `solver` — starts an optimisation.

Runs appear in the **Executions** panel immediately and update live over a WebSocket. Open one to see
its status and, once it finishes, its result files. See [Reading the results](/guide/results).

## Import and export

A whole project can be exported as a zip and imported again, folders included. This is the easiest
way to move an experiment between instances or to hand one to a colleague.

## Cloning a project with git

Every project is also a real git repository. The project details panel has a **Git** section with
both clone URLs, HTTPS and SSH, ready to copy.

```
git clone https://<host>/git/<project-id>.git
```

You need read access to clone and write access to push - the same permissions that govern the
workbench itself.

There are three ways to authenticate. Signing in through the browser is the one to reach for
unless you have a reason not to; the other two exist for cases it does not cover.

### Signing in from git with a browser

[Git Credential Manager](https://github.com/git-ecosystem/git-credential-manager) can fetch a token
for you: it opens a browser, you sign in once with the same screen the workbench uses, and it saves
the token in your operating system's credential store. Every later clone, fetch, and push is then
unattended, and your account password never reaches a git client at all.

GCM ships built-in support only for GitHub, GitLab, Bitbucket, and Azure DevOps, and has no generic
discovery mechanism, so a self-hosted server has to be pointed out to it once per host. Two settings
are enough:

```bash
git config --global "credential.https://mdeo.example.com.oauthAuthorizeEndpoint" /oauth/authorize
git config --global "credential.https://mdeo.example.com.oauthTokenEndpoint" /api/oauth/token
```

The panel offers these commands, already filled in for the server you are on, the first time you
copy a clone URL.

Use exactly the origin from your remote URL, including the port when there is one - GCM matches this
setting against the remote it is authenticating, so `http://localhost:4242` and `http://localhost`
are different hosts to it.

> [!WARNING]
> On Windows, run these in **Git Bash or cmd, not PowerShell**, or write the URL out literally as
> above. `$HOST` is a reserved PowerShell automatic variable, so a `credential.$HOST....` key
> silently expands to something like `credential.System.Management.Automation...` and the setting
> never matches your remote. Check what you actually have with
> `git config --global --get-regexp credential`.

Everything else has a working default: the client is public and unauthenticated, so no client id or
secret is needed; the redirect defaults to a loopback address on your own machine; and the username
GCM stores (`OAUTH_USER`) is ignored, because the token identifies its owner on its own.

What you should see is a browser tab asking you to authorize git access, and your terminal
continuing on its own once you approve. The tab is then finished with - GCM listens on a loopback
port only long enough to catch the answer and closes it immediately, so reloading that tab reports
a refused connection even though the sign-in worked. The receipt is the token itself: it appears
under **Access tokens** in the Account dialog, named `git sign-in (<date>)`.

That token is an ordinary personal access token in every other respect. It shows its last use like
any other and is revoked the same way - revoking it simply makes GCM run the browser flow again next
time.

> [!NOTE]
> Over plain HTTP, git prints `warning: use of unencrypted HTTP remote URLs is not recommended`.
> That is GCM warning about the remote, not about the sign-in: the browser flow works over HTTP, and
> the warning is silenced with `git config --global credential.allowUnsafeRemotes true`. The
> underlying advice still stands - and is in fact the argument for this flow, since the alternative
> sends your password over that same connection.

### Personal access tokens

You can also create a token by hand from the **Account** dialog's **Access tokens** section and use
it as the password:

```
git clone https://<username>@<host>/git/<project-id>.git
```

Git prompts for a password on the terminal; paste the token there rather than putting it in the URL
itself. A URL with `<username>:<token>@` embedded in it is saved verbatim in your shell history and
in `.git/config`'s `remote.origin.url`, so anyone who later reads either one recovers the raw token -
pasting it only at the prompt keeps it out of both. For unattended use where there is no prompt to
answer, a [credential helper](https://git-scm.com/docs/gitcredentials) is the right place to store it
instead of the URL.

This is what you want for anything that has no browser to open - a CI job, a container, a script.

A token can be limited to specific projects when you create it. An unscoped token reaches every
project you can, which is what you want for day-to-day use on your own machine; a scoped one is
worth reaching for when the token leaves your hands. Scoping only ever narrows what a token can do:
it never grants access you do not already have, and losing access to a project immediately stops
every token you hold from reaching it, scoped or not.

Your ordinary account password also works, but a token is better in every case: it can be revoked
on its own without changing your password, it can be scoped, and it never puts your password into a
git client or credential store.

### Git over SSH

Git-over-SSH is authenticated by public key instead of a password or token. Register a key from the
**Account** dialog's **SSH keys** section, then use the SSH URL from the project panel:

```
git clone ssh://git@<host>:2222/<project-id>.git
```

The port is not the standard 22, so it cannot be omitted. It is also configurable, and some
deployments do not expose it to clients at all - the panel shows an SSH URL only when this one
does, so if you see no SSH URL, SSH is not reachable here and HTTPS is the way in.

### Keeping track of credentials

The **Access tokens** and **SSH keys** sections both show when each credential was last used, so a
token or key nothing has touched in months is easy to spot and revoke. A key's timestamp only moves
when it actually authenticates a git operation - offering a public key that is never signed for does
not count as use.

### What a clone contains

A clone contains every project file, plus a single `project.mdeo` file at the repository root
listing the project's enabled plugins, so a fresh clone opened as a new project comes up with the
same languages available. Changing that file over a push requires admin permission on the project,
the same bar changing plugins from the workbench itself is held to, not merely write access.

The `.mdeo` extension is reserved for MDEO itself. No project file may use it, in a push or in the
workbench, and no plugin may register a language claiming it - which is what lets MDEO generate the
file above without it ever colliding with something you created.

Diagram layout is not included: it is purely visual and changes on nearly every interaction with a
diagram, so a project opened for the first time from a clone will need its nodes laid out again.

History is not one commit per save. The current state is committed only when someone actually looks -
cloning or fetching - and only if it differs from what is already there, so the log reflects points
where something changed rather than every keystroke.

A push is applied the same way a workbench edit is: the same validation, and the same conflict
handling projects already have for two people editing live. A push that is not a fast-forward is
rejected, exactly as git rejects one anywhere else, and you reconcile it locally with the tools you
already use.

> [!NOTE]
> **For operators.** The two endpoint paths above are the defaults; they are set by
> `GIT_OAUTH_AUTHORIZE_PATH` and `GIT_OAUTH_TOKEN_PATH`, and the workbench reads them back so the
> setup commands it shows always name what the deployment actually serves. SSH is configured with
> `GIT_SSH_PORT`, `GIT_SSH_PUBLIC_HOST` (when SSH is served from a different host than the
> workbench), and `GIT_SSH_PUBLICLY_REACHABLE` (which stays `false` unless the port is actually
> published, so the workbench does not offer a URL nobody can reach).
>
> `TRUSTED_PROXY_HOPS` says how many reverse proxies sit in front of the backend, so that failed
> logins and failed git authentications are counted per client rather than per proxy. It is `0` by
> default, which trusts `X-Forwarded-For` not at all; set it to the real hop count, and never to
> more, since a value above the real count is what would let a caller forge its own address.
