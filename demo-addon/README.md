# 普通欢迎语 Mod

这是一套普通的网易 Python Mod 源文件，没有 App 参数表单、占位符替换或与编辑器的运行时绑定。

```text
welcome/behavior_pack/
├── manifest.json
└── WelcomeDemo/
    ├── __init__.py
    ├── modMain.py
    └── client.py
```

修改 [client.py](welcome/behavior_pack/WelcomeDemo/client.py) 第 14 行引号中的文字，保存文件后重新应用 Mod：

```python
game.SetTipMessage("欢迎来到我的世界！")
```

`modMain.py` 注册客户端系统；`client.py` 监听本地玩家完成地形加载，再显示提示。无需服务端系统或资源包。

API/事件已按 ModSDK 3.9 文档核对，3 个 Python 文件与 manifest 均通过静态审查，0 个问题。当前正式项目仍是交互原型，尚未用真实游戏验证这套新示例；不能把原型进入“运行中”视为 Mod 已执行。见[审查记录](../docs/evidence/welcome-template-review.json)。

示例直接作为原型的文件资源输入。编辑器保存完整文件文本，既不会读取欢迎语字段，也不会根据字段生成或重写 Python。打包源码：

```sh
python3 tools/package-welcome-demo.py
```

生成 `artifacts/welcome-demo.zip`。手机正式工作区约定为 `/sdcard/mcpy/projects/...`，不能退回应用私有目录。
