-- Build config for the Teal server (ADR 0002). `./gradlew` runs `cyan build`
-- with it, and the Teal server reads it when this repo is open in the IDE.
return {
   source_dir = "src/main/teal",
   build_dir = "build/generated/teal/teal-server",
   -- Plain Lua 5.1 runs on every interpreter the server supports (5.1 to 5.5,
   -- LuaJIT) without compat53.
   gen_target = "5.1",
   gen_compat = "off",
   warning_error = { "branch", "debug", "hint", "redeclaration", "unknown", "unread", "unused" },
}
