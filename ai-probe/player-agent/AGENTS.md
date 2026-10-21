最新完整能力清单见[AI能力报告](AI能力报告.md)，首次接入先读此文件；以运行时capabilities、supported_actions和selection为准。

# 地牢游玩：协议2

1. 只使用公开接口；不读开发历史、游戏源码或存档来决定动作。运行python ../player-client/player.py brief建立当前上下文。
2. 输出带batch_id、base_revision、revision、valid、patch和events。同一未确认批次会重放，不代表动作再次发生。程序负责确定性合并和校验；只有valid=true才用于新决策。
3. 读到批次后用ack --batch编号确认，或在下一次命令附--seen编号。act --seen编号 --obs观测版本可合并确认，不增加一次模型决策。没有读到结果不要确认。
4. 忙碌批次不删除角色、背包等业务状态。确认后继续state；客户端会短暂本地等待。缺失消息或模型忘记状态时用resync/brief恢复，不猜测patch。
5. 战斗只提交单步act，使用最新obs。解释events中的伤害、miss与已显示surprise；显示伤害不是精确HP损失。零点天赋不生效，必须核对实际挑战、饥饿和动作耗时。
6. 平静探索可用goto或明确的短path；交互使用interact-at并检查结果。新敌人、受伤及重要状态或环境变化会中断；普通治疗、护盾增长和视野扩展在目标移动中可能继续。停止规则不等于无风险保证。
7. 客户端用请求编号保存操作。结果未知或accepted尚未完成时，新操作被阻止；只用resume查询恢复，绝不重发原动作。跨游戏进程重启后可能要求人工审核；resolve命令不得由AI自行冒充人工使用。
8. 首次/变化的玩家可见说明自动进入knowledge.text；正常决策无需额外拉取这些说明。其他长说明引用可用detail --ref深入读取，缺少必要说明时查询后再动作。_spd_codec标记是呈现编码，真实字段名称不改变。view只显示已确认状态，行动前仍需新观测。
9. memory分别保存goal、plan、带source/observed_at的facts和unresolved。它们是登记的观察与意图，不替代游戏真值；未确认操作自动保留。不要把上一局能力当本局事实。
10. context_rotation提示时生成brief并确认批次，实际替换外部历史后才ack-context --proposal编号。生成摘要不清零计数。客户端无法证明外部历史真的被替换；不能仅追加摘要。
11. 地图随state/act/path自动增量更新；map只作兼容刷新，query按需计算路径/边界。不要每回合读取整篇经验库或完整行动追踪。
12. 有真实模型usage时按单次request_id记录input/cached input/output；没有数据不估计。输出字符和模型token分开统计。正常开局先config，start必须指定挑战，load必须指定槽位。


## 2026-10-14 统一决策交付

1. 正式客户端要求/frame的decision_version=1；状态、事件、已探索地图增量在同一稳定采样中取得。正常state/act/path/brief自动更新map_memory，map不再是必需步骤。
2. knowledge保存玩家可查看的说明，使用text字段避免长说明只剩需主动拉取的引用；首次遇见及内容变化自动传递，未变化保留不重发，换局重置。历史知识不证明对象仍在当前视野，当前事实以entities/map_items等为准。
3. attention是变化提醒，含怪物观测、生命/饥饿、可用天赋点、物资、地图及知识变化；不是动作建议，也不替代patch和events。未变化的天赋点提醒不会持续重发。
4. 地图与知识一起纳入批次重放/摘要校验/ACK；游标只在ACK后提交。完整brief包含知识和地图；忙碌时完整resync也保留同scope已确认业务状态。

10. goto可走向已探索地图上的目标；interact-at指定pickup/open/unlock/talk/descend/ascend/buy/alchemy，自动接近并执行。必须检查interaction.status，购买及窗口选择不自动确认；未知结果不自动续做意图。
