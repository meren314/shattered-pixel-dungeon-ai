# SPD AI Probe：破碎的像素地牢 AI 接口

基于 Shattered Pixel Dungeon 4.0.2 的非官方桌面实验版本。游戏进程提供玩家可见的结构化观测，Python 客户端交付地图记忆、状态增量、战斗事件和可靠动作回执，供外部 AI Agent 自行判断和操作。

项目不包含模型服务、API 密钥或自动战斗策略。需要另行提供能够执行本地命令并维护上下文的 Agent 环境。代码仍属实验性质，不保证通关或覆盖所有物品组合。

## 1. 安装与启动

1. 在 Windows 安装 JDK 21 或更新版本，以及 Python 3.10 或更新版本，并将 `java`、`javaw`、`python` 加入 PATH。Gradle Wrapper 需要 JDK 17 以上；游戏源码以 Java 11 兼容级别编译。当前本机验证环境为 JDK 26、Python 3.14；其他版本尚未本机实测。
2. 下载并解压仓库，在仓库根目录运行：

   ```powershell
   .\gradlew.bat :desktop:installDist --console=plain
   ```

3. 双击 `Start-AI-Probe.cmd`。首次构建需要联网下载 Gradle 和 Maven 依赖；不需要 Android SDK。游戏与本地面板地址为 `http://127.0.0.1:18765/`。面板不会自动接入任何模型。
4. 从仓库根目录检查客户端：

   ```powershell
   python ai-probe/player-client/player.py --help
   python ai-probe/player-client/player.py config --type capabilities
   python ai-probe/player-client/player.py config --type saves
   python ai-probe/player-client/player.py brief
   ```

5. 首次游戏的教程、故事和选择界面仍然存在。AI 应根据 `selection`、`story` 和 `allowed_actions` 应答。不要把未进入地牢的状态当作接口失效。

## 2. 接入 AI

1. 让 Agent 先读 [AI 使用规则](ai-probe/player-agent/AGENTS.md) 和 [完整能力报告](ai-probe/player-agent/AI能力报告.md)。以运行时 capabilities、supported_actions 和 selection 为准。
2. `state` 或 `brief` 返回带 `batch_id` 的决策包。读取并合并结果后，用 `ack --batch 批次ID` 确认，或在下一次动作附 `--seen 批次ID`。未确认批次会重放。
3. 动作使用最新 `observation_id`；结果未知时只调用 `resume` 查询。不得删客户端日志来绕过阻塞，不得盲目重发。
4. 观测自动包含当前状态、可见怪物、背包、饥饿、挑战、天赋、事件、已探索地图增量和首次出现的公开说明。`map_items` 保留游戏地图显示的已见物品堆；未知区域、隐藏属性及未打开容器内容不提供。
5. 平静移动可用 `goto`，明确交互可用 `interact-at`；战斗和物品选择由模型决定。具体参数见能力报告。它们是 Python 组合命令，不是同名 HTTP 端点。
6. 正常开局需显式指定职业和挑战，例如 `start --class WARRIOR --challenges 0`。先检查存档；未经用户同意不要覆盖游戏。读档使用 `load --slot 槽位`。
7. 每个客户端日志只允许一个消费者。`view` 是已确认本地状态，不能替代行动前的新观测。上下文轮换需要外部 Agent 真正替换历史，仅生成 brief 不会降低已有历史长度。

JSON 动作参数应由 Agent 使用进程参数数组传递，避免 Windows 多层 shell 引号问题。单步动作示例的参数数组：

```python
["python", "ai-probe/player-client/player.py", "act", "--obs", "123",
 "--seen", "实际批次ID", "--action", '{"type":"wait"}']
```

示例版本号必须替换为当前真实观测值。

## 3. 数据与安全

1. 游戏使用 `SPD AI Probe` 独立配置名称。Windows 存档通常在 `%APPDATA%\.shatteredpixel\SPD AI Probe`；本版本不会自动导入或合并其他安装版存档。
2. `ai-probe/player-client/` 中的协议状态、操作记录、用量日志，以及 `ai-probe/request-receipts/` 中的请求回执都是本地运行数据。它们已被 Git 忽略。发生未知操作时应保留用于恢复，不要随意删除。
3. 服务只监听 IPv4 回环地址，校验 HTTP Host 和浏览器 Origin，无远程身份认证。任何可信本机程序仍可调用接口。不要端口转发或暴露到公网；边界详见 [安全说明](SECURITY.md)。
4. 请使用启动器或 AI 专用 Gradle 配置。`desktop/build.gradle` 等上游平台文件为源码保留，不是本仓库推荐的构建入口。

## 4. 验证与开发

```powershell
python -m unittest discover -s ai-probe/tests -p test_protocol_v2.py
python -m unittest discover -s ai-probe/tests -p test_player_client.py
python -m unittest discover -s ai-probe/tests -p test_context_tools.py
.\gradlew.bat :desktop:installDist --console=plain
```

测试使用临时目录及模拟接口，不操作用户存档。45 项客户端单元测试覆盖增量重建、ACK 重放、未知结果恢复、公开地图导航及交互中断等；不等于完整游戏集成测试。发布前复核范围见 [代码审查记录](docs/RELEASE_REVIEW.md)。

## 5. 开源许可与来源

本仓库基于 [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon)，保留 [GPLv3 许可证原文](LICENSE.txt) 和原作者署名。游戏源码声明适用 GPL-3.0-or-later；本项目新增接口与客户端采用相同许可。分发二进制时同时提供对应版本的完整源码和构建说明。第三方依赖仍遵守各自许可证。

原作者、版本边界和新增修改详见 [来源与许可说明](NOTICE.md)。这是独立实验项目，问题请提交到本仓库，不要将本项目接口问题提交给原游戏作者。上游历史说明见 [UPSTREAM_README](docs/UPSTREAM_README.md)。
