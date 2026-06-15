{ pkgs, ... }:

{
  packages = with pkgs; [
    clojure
    jj
    jdk21
    rlwrap
    tailscale
  ];

  env = {
    JAVA_HOME = pkgs.jdk21;
  };

  enterShell = ''
    echo "java: $(java -version 2>&1 | head -n 1)"
    echo "clojure: $(clojure -Sdescribe >/dev/null 2>&1 && echo ready || echo missing)"
    echo "tailscale: $(tailscale version 2>/dev/null | head -n 1 || echo missing)"
    echo "run: devenv shell -- clojure -M -m yaw.core"
    echo "serve: devenv shell -- serve"
  '';

  scripts.dev.exec = ''
    clojure -M -m yaw.core
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

    clojure -M -m yaw.core
  '';

  processes.yaw.exec = ''
    serve
  '';

  scripts.repl.exec = ''
    rlwrap clojure -M:repl
  '';

  enterTest = ''
    java -version >/dev/null
    clojure -Sdescribe >/dev/null
  '';
}
