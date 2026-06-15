# Yaw

Small Datastar + Gleam site scaffolded for `devenv`.

## Run

```bash
devenv shell -- gleam run
```

Then open `http://localhost:8080`.

## Tailnet Access

Use `tailscale serve` to expose the local app on your Tailnet:

```bash
tailscale up
devenv shell -- serve
```

That helper configures `tailscale serve --http=80 http://127.0.0.1:8080` and then starts the app.

Useful commands:

```bash
tailscale serve status
tailscale serve reset
```

## Notes

- Backend: `mist`
- HTML: server-rendered string templates
- Live updates: `datastar_gleam` over Mist SSE
- Reference docs: `https://datastar-gleam.hexdocs.pm/`
- Visual direction: black on yellow, inspired by `tonsky.me`
- The previous Clojure implementation is still in `src/yaw/core.clj` as a reference while the repo finishes its migration.
