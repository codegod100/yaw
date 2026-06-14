{ pkgs, ... }:

{
  packages = with pkgs; [
    clojure
    jdk21
    rlwrap
  ];

  env = {
    JAVA_HOME = pkgs.jdk21;
  };

  enterShell = ''
    echo "java: $(java -version 2>&1 | head -n 1)"
    echo "clojure: $(clojure -Sdescribe >/dev/null 2>&1 && echo ready || echo missing)"
    echo "run: devenv shell -- clojure -M -m yaw.core"
  '';

  scripts.dev.exec = ''
    clojure -M -m yaw.core
  '';

  scripts.repl.exec = ''
    rlwrap clojure -M:repl
  '';

  enterTest = ''
    java -version >/dev/null
    clojure -Sdescribe >/dev/null
  '';
}
