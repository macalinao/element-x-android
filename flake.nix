{
  # mtrix fork: dev shell for building Element X Android with Nix.
  #   nix develop -c ./gradlew :app:assembleFdroidDebug
  # See README.mtrix.md.
  description = "Element X Android (mtrix fork) build environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    { nixpkgs, android-nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
      # Keep in sync with plugins/src/main/kotlin/Versions.kt (COMPILE_SDK, BUILD_TOOLS_VERSION).
      androidSdk = android-nixpkgs.sdk.${system} (
        sdk: with sdk; [
          cmdline-tools-latest
          platform-tools
          build-tools-37-0-0
          # AGP 9.3's default build-tools version.
          build-tools-36-0-0
          platforms-android-37-0
        ]
      );
      # Versions.kt: JAVA_VERSION = 21.
      jdk = pkgs.jdk21;
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          jdk
          androidSdk
          pkgs.git
        ];
        JAVA_HOME = jdk.home;
        ANDROID_HOME = "${androidSdk}/share/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
        # aapt2 from Maven is a dynamically linked binary; use the SDK's (Nix-patched) one.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/share/android-sdk/build-tools/37.0.0/aapt2";
        shellHook = ''
          # Gradle's toolchain detection must find the Nix JDK, not try to download one.
          export ORG_GRADLE_PROJECT_org_gradle_java_installations_paths="$JAVA_HOME"
        '';
      };
    };
}
