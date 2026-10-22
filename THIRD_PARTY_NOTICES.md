# Third-party code and runtime data

`game-host/src/main/assets/developer_source_loader.py` is copied from EaseCation/mcpelauncher-manifest, commit `8b624579618a121c345dd4ff05748d426a0141fa`. The upstream license is GPL-3.0; its source and license obligations apply on redistribution; see the included `licenses/mcpelauncher-manifest-LICENSE`.

The Gradle wrapper is from Gradle (Apache License 2.0). AndroidX, Kotlin and kotlinx.coroutines are build dependencies with their respective licenses.

Minecraft / NetEase engine code and assets are obtained on the user's device from the official developer channel. They are not included in the source repository or host APK. This is an interoperability host and does not imply permission for any particular commercial distribution or app-store channel.

## Embedded editor and imported grammars

- Sora Editor and language-textmate 0.24.4, Copyright Rosemoe contributors, LGPL-2.1-or-later. Unmodified Maven artifacts. License: `licenses/Sora-LGPL-2.1`. Source archives: https://repo.maven.apache.org/maven2/io/github/rosemoe/editor/0.24.4/editor-0.24.4-sources.jar and https://repo.maven.apache.org/maven2/io/github/rosemoe/language-textmate/0.24.4/language-textmate-0.24.4-sources.jar . The library can be replaced and rebuilt using `feature/editor/build.gradle.kts`; no anti-modification restriction is added by this project.
- Python grammar: MagicStack/MagicPython, MIT, as shipped in microsoft/vscode 1.95.3 (`extensions/python/syntaxes/MagicPython.tmLanguage.json`). Exact upstream revision is retained inside the grammar. See `licenses/MagicPython-LICENSE`.
- JSON grammar: microsoft/vscode 1.95.3, MIT. See `licenses/VSCode-LICENSE`.
- The themes in `feature/editor/src/main/assets/textmate` are original application themes.
- Eclipse JGit 6.10.0.202406032230-r, Eclipse Distribution License 1.0. Source: https://github.com/eclipse-jgit/jgit/tree/v6.10.0.202406032230-r . JGit is used for HTTPS imports only. Its binary dependencies preserve their upstream licenses.
- TextMate support includes Eclipse TM4E (EPL-2.0) and its Maven runtime dependencies (Gson, Joni, JCodings, SnakeYAML). Preserve their upstream notices when distributing source/binary packages.

## Embedded engineering runtime

- mcpywrap-core 0.6.0: MIT, https://pypi.org/project/mcpywrap-core/0.6.0/ . Installed from the published wheel, not a copied fork. Its packaged license files are retained by the Python packaging runtime.
- Chaquopy 17.1.0: https://github.com/chaquo/chaquopy ; embedded CPython 3.13 and its bundled standard-library dependencies retain their upstream notices. See https://chaquo.com/chaquopy/doc/current/license.html .
- click 8.1.8 (BSD-3-Clause), packaging 25.0 (Apache-2.0/BSD-2-Clause), tomli 2.2.1 and tomli-w 1.2.0 (MIT) are pinned tool dependencies. They do not enter exported game packs.
