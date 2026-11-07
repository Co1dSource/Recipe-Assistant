# Recipe Assistant 0.8.0 阶段8交付记录

实现与打包结束，等待最终游戏验收。用户确认阶段0～7验收；本轮按用户授权由Codex实现并上传Git，没有Qoder、子代理或服务器改动。追加更名要求已落实，对外不绑定整合包；内部Mod ID保留兼容。

| 用户要求 | 实现与实际证据 |
| --- | --- |
| 1 修改文件 | 完整阶段8文件清单见[CHANGES.md](CHANGES.md)；原未提交成果被保留并纳入仓库 |
| 2 JEI强制 | mods.toml的jei mandatory=true，固定11.6.0.1024；Gradle implementation，缺失时由Forge正常处理 |
| 3 AE2隔离 | OptionalIntegrations只含Minecraft/Forge/JEI类型；安装检测后初始化integration/ae2并手动注册事件。独立Mixin配置提前门控目标 |
| 4 ExtendedAE隔离 | 无ExtendedAE专属类调用；AE2初始化后另检expatternprovider才接纳36槽。无扩展模组仍支持普通9槽 |
| 5 全界面 | 树、详情与变体选择、批量预览、问题区、数量编辑、分发及类别/供应器选择、执行提示统一灰色容器风格；JEI原生按钮保留 |
| 6 清理 | 删除树底部教学、预览说明与ESC教学、问题修复教学、数量页滚轮教学、旧无目标教学文案；删除分发刷新/上页/下页及问题上一项/下一项控件 |
| 7 分发入口 | 原终端内x175/y4，13×13三角图标，Tooltip自动分发；实际AE2样式搜索框到x169，滚动条从y18开始 |
| 8 分发界面 | 紧凑样板/机器/供应器/空位统计，范围内滚轮，细滚动条。原生服务端更新标记刷新，背包菜单stateId变化刷新；没有高频供应器轮询 |
| 9 样板真实预览 | 处理输入直接取执行器Entry.inputs的真实选材、合并量；工作台逐格保留9槽；目标输出、手动数量及替换设置均来自编码数据/原终端 |
| 10 安山岩案例 | **未在实际游戏验证**。需在真实JEI选出巨型圆石1→圆石64及圆石1+闪长岩1→安山岩2，共2张；没有伪造配方或截图。夹具只验证同等数据路径 |
| 11 G/ESC/+ | 无游戏检查确认原JEI类别/第17条配方状态/父界面恢复，数字框焦点下首G定位准确问题，背包/容器悬停G。真实按键与+点击未在游戏复测 |
| 12 一键合成 | 外部供应、现货9可用/8缺1、问题合并的无游戏规划检查通过；真实3层、多分支、取消及联机同步未测 |
| 13 替换继承 | 原生Crafting格式4开关组合各3次与产品核对函数通过；Processing不伪造标志。真实终端连编未测 |
| 14 普通供应器 | 9槽记录、空位、映射、重复及满槽规划检查通过；实际上传未测 |
| 15 扩展供应器 | 36槽记录、开关门控及规划检查通过；实际扩展上传未测 |
| 16 装配室关联 | 保留按原终端group.icon识别ae2:molecular_assembler的逻辑；用户此前基本兼容事实保留，本轮自动分发端到端未测 |
| 17 安装组合 | 仅JEI、JEI+AE2、全依赖、缺JEI的真实Forge启动均未测。移除AE2/ExtendedAE类路径后52个核心类签名解析通过，0次可选类型解析；JAR元数据检查通过 |
| 18 多人服务器 | **未测试**；本轮没有真实服务器连接与物品操作记录。不宣称纯客户端联机功能已通过 |
| 19 最终Gradle | gradlew.bat clean build --console=plain：BUILD SUCCESSFUL，包含reobfJar。改名后重新构建成功；test为NO-SOURCE |
| 20 最终JAR | build/libs/recipeassistant-0.8.0.jar，绝对路径及SHA-256见[ARTIFACT.json](ARTIFACT.json)。build/libs仅此一个发布JAR，已原样复制到仓库releases供下载 |
| 21 README | 更新名称、版本、必需/可选依赖、功能、操作、安装、构建开关、真实限制与许可；保留内部Mod ID的原因已说明 |
| 22 未完成验证 | 图形布局/Mixin实注入、真实安山岩预览、完整安装组合及多人服务器物品操作；见[GAME_TESTS.md](GAME_TESTS.md)。不开始新阶段 |

独立JAR检查通过：名称、Mod ID、版本、Minecraft/Forge范围、JEI必需、AE2/ExtendedAE可选、Java17字节码、无第三方类/嵌套JAR/测试文件、无重复资源、两份Mixin配置及Quick Craft许可。

## 无游戏定向检查

源码在checks目录，原始参数/日志在本机outputs/stage8。实际使用D:/Java/Java17/bin/javac.exe @outputs/stage8/checks-compile.args，随后逐个java @outputs/stage8/<名称>.args。检查使用本地反混淆Minecraft/Forge/JEI/AE2类路径，不能直接使用生产混淆JAR代替。

| 检查 | 结果 |
| --- | --- |
| CoreIsolationCheck | PASS，剔除AE2/ExtendedAE后52个核心类签名可解析，无可选类型请求 |
| ExternalSupplyCheck | PASS，正常外部供应、缺料1、81项问题合并 |
| SubstitutionCheck | PASS，原生格式4组合×3及产品校验；不是游戏编码 |
| PreviewDataCheck | PASS，手动数量7、重复材料3+2合5、唯一目标、9槽位置、NBT快照及基本点击边界 |
| JeiStateCheck | PASS，原JEI状态恢复、数字输入焦点下首G定位准确节点 |
| HoverCheck | PASS，空手时背包/容器悬停优先 |
| DistributionCheck | PASS，9/36槽、可选门控、机器类别、映射歧义/失效、重复与满槽规划 |
| python docs/stage8/checks/check_jar.py | PASS，独立核对最终更名JAR |

原始构建日志outputs/stage8/build-renamed.log；审查见[REVIEW.md](REVIEW.md)。名称和打包资源变更后，只重跑构建与归档检查，未重复执行不受其影响的功能夹具。
