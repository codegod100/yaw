{ pkgs, ... }:

{
  packages = with pkgs; [
    erlang
    gleam
    rebar3
    tailscale
  ];

  enterShell = ''
    echo "gleam: $(gleam --version)"
    echo "erlang: $(erl -eval 'io:format(\"~s\", [erlang:system_info(otp_release)]), halt().' -noshell)"
    echo "rebar3: $(rebar3 version | head -n 1)"
    echo "tailscale: $(tailscale version 2>/dev/null | head -n 1 || echo missing)"
    echo "run: devenv shell -- gleam run"
    echo "serve: devenv shell -- serve"
  '';

  scripts.dev.exec = ''
    gleam run
  '';

  scripts.serve.exec = ''
    port=''${PORT:-8080}

    if ! tailscale status >/dev/null 2>&1; then
      echo "tailscale is not connected; run 'tailscale up' first" >&2
      exit 1
    fi

    tailscale serve --bg --http=80 http://127.0.0.1:$port
    echo "tailscale serve forwarding Tailnet HTTP traffic to http://127.0.0.1:$port"
    echo "inspect with: tailscale serve status"

    PORT=$port gleam run
  '';

  processes.yaw.exec = ''
    serve
  '';

  enterTest = ''
    gleam --version >/dev/null
    erl -eval 'halt().' -noshell
  '';
}
