# 地牢 AI 能力报告

更新时间：2026-10-14。面向实际游玩Agent，列出已实现能力及调用方式；不是战术经验或未来计划。在仓库根目录使用 `python ai-probe/player-client/player.py`；不要同时启动两个消费者。

## 1. 先了解操作流程

1. 首次执行 `state` 或 `brief`，读取批次、当前版本、状态变化、事件、提醒和知识。判断下一步行动由你负责。
2. 下一次动作附 `--seen 上一批次ID --obs observation_id`；也可先 `ack --batch 批次ID`。未确认结果会原样重放，不能绕过去执行新动作。
3. 每次动作结束自动返回决策包。正常情况下不需要额外读取地图或检查新对象说明。valid=false时先等待刷新，不能据不稳定状态行动。
4. 先查看 `config --type capabilities`：返回原生HTTP能力及客户端组合命令。物品具体可用动作以当前背包supported_actions为准，界面选择以当前selection为准。
5. 示例中的数字和BATCH都要替换为最新真实值。JSON应通过进程参数数组传递，避免Windows多层shell引号被破坏。

## 2. 你可以执行哪些游戏操作

1. 目标移动：`goto --obs 123 --x 20 --y 15 --seen BATCH`。沿当前楼层已探索地图走向目标，可以超出初始视野；进入每一格前重新检查当前视野。未知路线、阻碍或新危险会停止。不会自动攻击、拾取或上下楼。
2. 明确交互：`interact-at --obs 123 --x 20 --y 15 --intent pickup --seen BATCH`。自动走到目标或合法邻格，再执行pickup、open、unlock、talk、descend、ascend、buy、alchemy之一。物品、买卖与楼梯走到脚下；其他按合法邻格操作。不会代选购买或对话选项。
3. 指定短路径：`path --obs 123 --cells '[[20,15],[21,15]]' --max 2 --budget 2 --seen BATCH`。适合明确要求的路线，最多5步/5时间预算。普通平静移动优先goto，不要逐格调用。
4. 单格移动、攻击、等待、搜索：`act --obs 123 --seen BATCH --action '{"type":"move","dx":1,"dy":0}'`。其他type为attack（dx/dy）、attack_target（x/y）、wait、search。攻击不会自动追杀，也没有自动战斗循环。
5. 原子交互：act的pickup、open_container、unlock、interact、buy、alchemy、jump、descend、ascend仍存在，坐标/站位要求由原生capabilities给出。jump涉及深渊等危险，需你明确决定，不包含在自动寻路中。
6. 背包物品：`act --obs 123 --seen BATCH --action '{"type":"use_item","item_id":"item_3","item_action":"THROW","x":20,"y":15}'`。EAT、DRINK、READ、ZAP、DROP、EQUIP、UNEQUIP、PLANT等仅在当前supported_actions包含时可用；装备、神器与武器技能同样遵循实际菜单。未鉴定物品不提供隐藏真效果。
7. 远程投掷已有单次指定坐标；ZAP等动作可能先打开选格界面，再用choose_cell。不要假定所有use_item动作都会自动使用传入x/y，也不要把进入选择界面当成技能已释放。
8. 界面应答：act的choose_cell（x/y）、choose_item（item_id）、choose_option/choose_icon（index）、choose_keep_slot（slot）、cancel_selection、continue，以及终局choose_scene_option。依据selection提示和实际候选选择；可见楼层故事continue可省略obs。
9. 天赋：upgrade_talent（talent_id），查看实际可用点及零点天赋不生效。不要把天赋列表中所有条目当成已学能力。
10. 炼金：alchemy进入；alchemy_select_slot、alchemy_add、alchemy_clear、alchemy_combine、alchemy_leave操作当前界面。选项/槽位取当前alchemy或selection，不能编造配方索引。
11. 开局/读档：config的classes/saves查询，然后start --class WARRIOR --challenges 0，或load --slot 4。遵守用户指定挑战与存档，不覆盖已有游戏。act的return_title可返回菜单。

## 3. 自动提供的情报

1. hero、hunger、effects、timing：位置、生命/护盾、等级、力量、经验、金币、饥饿阶段、公开状态和动作耗时。action_time_estimates是估算，实际消耗以返回耗时为准，null表示未知。
2. entities：当前可见角色、位置、可见状态、近似血条和公开效果。不是隐藏AI状态，也不是精确怪物HP。对象消失不能直接算击杀。
3. inventory、keys、talents：当前物品、数量、鉴定/装备、公开充能/耐久、可用动作、钥匙和天赋点。公开status格式因物品而异，不能假定每件物品都有精确耐久数字。
4. ground是当前视野物品；map_items是游戏地图仍显示的所有已见堆，visible标明当前是否可见，含商店和容器。不会暴露未打开容器内部。
5. map_memory自动保存当前楼层已探索地图并增量更新。切换楼层使用对应楼层记忆；旧地形不是永远不变的事实，靠近后重新核实。
6. knowledge.text自动提供首次遇见及变化的玩家可查看说明：怪物、词缀、挑战、特殊地形、可见危险、背包和可见普通地面物品等。未变化说明不重发；历史知识不证明对象仍然在场。
7. events返回日志及可见战斗反馈，包括命中/未命中、显示伤害、已显示伏击标记等。显示伤害可包含护盾吸收，未必等于HP损失，来源关联不保证完整。
8. attention提示重要变化，不替你决定行动。player_messages和selection也自动提供，先处理人类接管和待选择界面。

## 4. 阅读结果与恢复

1. patch是相对已确认状态的增量，reset表示重建基准。_spd_codec/table/ref是呈现编码，字段语义不变；使用现有客户端解码，不猜测标记。需要更易读的变化可在命令前用全局--raw，例如 `python ai-probe/player-client/player.py --raw state`。
2. goto检查travel.arrived及batch.stop_reason；interact-at另检查interaction.status。到达不等于完成。completed有公开结果证据；not_completed表示没有确认到预期结果；awaiting_user_choice要继续处理界面；interrupted不继续自动执行。
3. batch实际时间只含移动，interaction_time_spent含最后交互，navigation_time_spent为可确认合计。未知返回null。
4. 请求未知或处理中只resume；不能盲目重发，也不能删protocol-state.json解除阻塞。整个目标意图不会在恢复后自动续做。resolve仅供真实人类审核，AI不得冒充人类使用。
5. state --full/resync恢复完整交付，view读取已确认本地状态（不是最新现场），detail --ref按需深入说明。map保留兼容用途，正常动作无需另调它；query --json支持公开地图的region/find/route/frontier计算。
6. brief生成交接，memory保存目标/计划/带来源的事实与问题；外部历史实际替换后才ack-context。摘要生成不等于上下文已缩短。
7. usage仅记录真实模型单次用量。decision-history.jsonl保留已确认公开状态与事件，trace保留原子动作反馈，通常不需要把日志再发回模型。

## 5. 边界与尚未实现的便利操作

1. 没有自动选择打法、自动追杀、自动吃饭或无限休息。旧fight/rest/explore/collect等决策循环不是正常入口；平静目标移动使用goto。
2. 暂无rest_until、统一use_item_at或aim_preview；仍可通过已有wait、use_item和choose_cell完成基本操作。不能声称已有这些组合命令。
3. goto/interact-at是Python客户端组合命令，不是同名HTTP端点。直接HTTP兼容调用不会自动获得这些组合语义。
4. 实测能力不代表策略保证：新危险会中断，已知敌人在场不自动连续接近，未知格不自动探索。行动前后都由你判断，别把距离2当成安全证明。
