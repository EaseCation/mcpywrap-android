# Project constraints

- Use official Jetpack Compose Material 3 components. Record the checked release version in the design docs.
- A Mod is a set of ordinary files. The welcome example is edited in client.py. Do not add a greeting parameter model, regenerate Python from a form, or extract source values to pretend the game executed them.
- User work belongs under `/sdcard/mcpy`: projects, Mod versions, worlds, build outputs, exports, and logs. Never silently fall back to app-private or Android/data workspaces. System-required code-loading caches and credentials are not workspaces.
- Treat public files as authoritative. A private working copy plus a public export is not compliance with this layout.
- Label prototype simulations and planned paths honestly. UI state transitions are not proof of actual Mod execution or disk persistence.
- Preserve the complete ordinary template in demo-addon/welcome. Bundle it as an archive so Android packaging does not strip __init__.py. Check byte preservation with tools/check-template.py after building.
- Keep Android runtime/lifecycle concerns separate from desktop mcpy project/dependency/build responsibilities.

# Product verification

- Product UI must use real storage/runtime state; archived prototype code under docs/prototype-source is not a dependency.
- Use tools/verify-device.sh for UI tests. Gradle connectedDebugAndroidTest may uninstall the app and erase its installed runtime; do not use that cleanup flow on a prepared device.
- Run tools/verify-runtime.sh explicitly for official-download and engine integration coverage. Reuse the verified runtime cache after adapter-only changes.
- Only claim syntax highlighting after token colors are checked, and game readiness after real engine/HUD evidence.

# Instance ownership (0.3)

- A test instance belongs to one existing project by stable project ID. No global standalone-world creation or cross-project rebinding.
- New instance directories are `/sdcard/mcpy/worlds/<project>/<instance>/`. Never move an active instance.
- Deleting a project moves its instances with it to trash; restoring a project restores both. Instance deletion preserves source and can be restored to its original owner.
- Keep unassigned legacy worlds intact until the user adopts one from within a project.

# Shared engineering core (0.4)

- Production Run and Export must use EngineeringClient and the released mcpywrap-core API; Kotlin only adapts files and game deployment, never reimplements dependency merge rules.
- Tool Python runs only in :core, separate from editor and :game. Wait for a worker to exit before another operation; never hot-swap imported core modules.
- Download core updates only from the configured HTTPS PyPI metadata/files hosts; use CoreStore verification and health checks. Do not trust update descriptors from shared storage.
- Reject unsupported hooks, dependency acquisition and map projects explicitly. Do not fall back to direct source scanning when core build fails.

# Project creation (0.5)

- New native Mods use the published core's initialize_project and generate_mod_framework. Do not duplicate their templates in Kotlin or introduce gameplay parameter forms.
- Structure previews must come from real generated files in a fresh public staging directory. Creating checks the preview signature; a failed or cancelled preview must not publish a project.
- Preserve manually overridden system names when Mod identifiers change. Generated files become authoritative after creation; never synchronize the form back over edited source.
