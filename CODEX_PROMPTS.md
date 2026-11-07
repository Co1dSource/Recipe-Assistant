# CTI Recipe Assistant — Codex 分阶段提示词

使用顺序：将 `AGENTS.md`、`PROJECT_SPEC.md`、`API_CHECK.md` 和 `DEVELOPMENT_STATUS.md` 放入项目根目录。**阶段 0 已由 ChatGPT 完成，不要让 Codex 重复执行。** 从阶段 1 开始，每次只发送当前阶段提示词；每阶段建立独立 Git 提交。所有阶段只做**客户端**，不修改服务器。

**以下精简规则适用于所有阶段，且优先于阶段提示词中的泛化测试/检查要求：** 正常路径优先；不写推测性兼容、过度抽象、万能 try/catch、重复前置检查、重复日志或多套回退。只检查当前操作实际依赖的外部数据和会改变物品的必要边界。每阶段结束编译一次（失败才重试），报告不超过 8 行。详情以 `AGENTS.md` 为准；每次发提示词时不必重复粘贴规则。

## 阶段 0：已由 ChatGPT 完成

已完成的版本、JEI/AE2/ExtendedAE API 和 Quick Craft 源码核验见 `API_CHECK.md`。不要重新执行阶段 0，不要重复搜索已确认的基础兼容性。若后续真实编译发现版本差异，只核对对应的单个接口。

## 阶段 1：最小 Forge 工程 + 客户端加载

```text
请阅读 PROJECT_SPEC.md、AGENTS.md 和 API_CHECK.md，只做阶段 1。
创建或修复一个使用 Java 17、Minecraft 1.19.2、Forge 43.4.2 的最小可构建项目，modid 为 ctirecipeassistant。目标依赖版本为 JEI 11.6.0.1024、AE2 12.9.9、ExtendedAE 1.19-0.2.2-forge。
1. 配置客户端专用入口。使用 `reference/mods` 中实际 JAR 或已确认的 Maven 坐标添加 JEI/AE2/ExtendedAE 编译/运行依赖；不要猜测 ExtendedAE 坐标，不要升级版本；绝不修改 CTI 服务器。
2. 不添加 EMI；不复制 Quick Craft 的服务端 CraftService/Network/容器扫描。
3. 注册一个可重新绑定的默认 G 快捷键和仅客户端日志，暂不实现配方树界面。
4. 采用 Forge 正常的客户端兼容声明，避免自定义握手实现；联机效果由实际测试确认。
5. 运行 Windows gradlew.bat build，修复真实编译错误，生成 JAR。
验收：构建成功、modid 元数据正确、不引用任何专用服务端类；尚未运行游戏的内容如实标为未测试。完成后停止。
```

## 阶段 2：Quick Craft 风格的只读配方树

```text
只做 RecipeTree。参考 https://github.com/Sxilverrr/Quick-Craft 的 TreeLayout.java、NodeView.java、RecipePreferences.java 和 QuickCraftScreen 的绘制/交互。该仓库没有 1.19.2 版本，GUI 代码要适配 1.19.2 PoseStack；不要整文件移植 1.20.1+ QuickCraftScreen。
1. 用 JEI 11.6 IRecipeManager：对目标物品建立 OUTPUT focus，查 `createRecipeCategoryLookup().limitFocus(...)`，再按类别调用 `createRecipeLookup(type).limitFocus(...)`；用 `createRecipeLayoutDrawable` 和 `IRecipeSlotsView` 读取 INPUT/OUTPUT/CATALYST。保留 JEI/KubeJS/机器类别，不只用原版 RecipeManager。
2. 节点支持多配方、多机器、不同原料选择、展开折叠、拖动缩放、循环检测和偏好记忆；等价路线小产出优先。
3. JEI 槽位视图不提供通用概率权重字段；把输出槽中的所有候选作为可选目标，不推测概率、不计算概率。概率输入数量由用户手动填写。
4. 只参考 Quick Craft 的纯 UI/布局逻辑；不移植 CraftService、网络包、EMC、附近容器、StationScan/CraftPlanner。
5. 该阶段不执行合成、编码、分发。完成后 build 一次并停止。
```

## 阶段 3：JEI 右上角 + 按钮和模式识别

```text
只做 JEI UI 入口，不开发执行引擎。严格使用 JEI 11.6.0.1024。
1. JEI `RecipesGui` 的私有 `recipeLayouts` 保存当前页每张配方布局，私有 `parentScreen` 保存父级界面；用两个最小只读 Mixin Accessor 读取，不改 JEI 内部列表。
2. 用 Forge ScreenEvent 在每个 `IRecipeLayoutDrawable#getRect()` 右上角绘制 13x13 按钮，中心使用 JEI `jei:textures/gui/icons/recipe_transfer.png` 的 7x7 图标；原右下角按钮保持原样。处理 hover tooltip 与 click。
3. 通过 `parentScreen` 的菜单识别 AE2 `PatternEncodingTermMenu`：该模式 tooltip“批量编码配方树样板”；其他模式 tooltip“一键合成配方树”。
4. 从 `IRecipeLayoutDrawable` 读取当前准确的 recipe/category/slotsView；不能仅根据物品名称猜配方。
5. 暂只显示/禁用按钮，不执行填料、合成、编码。GUI 缩放与每页多配方布局都要正确。build 一次后停止。
```

## 阶段 4：JEI 单步合法填料验证（风险门槛）

```text
只做原版 3x3 工作台“一张配方”的转移与取出验证。
1. 从当前 JEI `IRecipeLayoutDrawable` 取得 recipe/category/slotsView；用 `IJeiRuntime#getRecipeTransferManager()` 获取当前 `CraftingMenu` 与类别对应的 `IRecipeTransferHandler`。
2. 先调用 `transferRecipe(menu, recipe, slotsView, player, false, false)` 检查，再调用 `transferRecipe(..., false, true)` 执行一次 JEI 原有填料。
3. 等待服务端确认九宫格填入，再通过原版工作台菜单取出结果，确认背包变化。
4. 不复制 JEI 内部服务端处理器，不发送本模组自定义数据包，不直接设置客户端物品。
5. 只验证一次成功路径和一个真实失败条件。若 JEI handler/CTI 服务端协议不能完成，停止并报告具体阻塞点。build 一次后停止。
```

## 阶段 5：配方树逐层一键合成

```text
在阶段 4 单步填料与取出已经真实测试可用的前提下，只实现 CraftPlanner 和 CraftExecutor。
1. 只处理选定配方树中全部实际要执行的工序都能用普通 3×3 工作台完成的情况；允许已持有的中间物品作为叶子。
2. 只统计玩家背包和当前工作台，计算逐层真实需要量、复用已有产物；按实际配方处理已知容器返还，不为未知机制做复杂补偿。
3. 基础材料不足、没有 3x3 工作台或混入机器配方时，JEI 右上角 + 灰显、禁止点击、tooltip 解释。
4. 依赖顺序快速而非洪泛执行：JEI 填料 -> 服务端同步确认 -> 取出结果 -> 同步确认 -> 下一步；可暂停取消，单步超时和容器变化停止。
5. 不读取附近容器，不动 AE2 网络，不使用 Quick Craft 服务端执行逻辑。
6. 至少验证一条三层合成链、一个材料不足案例和一个中途关闭界面的案例；其他测试仅在对应错误实际出现时增加。
构建后停止，不碰 AE2 编码与分发。
```

## 阶段 6：AE2 12.9.9 批量编码样板

```text
只做 AE2PatternEncoder。使用 AE2 forge/v12.9.9 源码中的 PatternEncodingTermMenu、EncodePatternTransferHandler、EncodingHelper。
1. 当前菜单为 PatternEncodingTermMenu 时，通过 JEI IRecipeTransferManager 获取 AE2 已注册的通用 EncodePatternTransferHandler，并调用 transferRecipe(..., doTransfer=true) 填入 AE2 FakeSlot。
2. 注意该 handler 只填配方，不负责最终编码；填入完成后调用 `PatternEncodingTermMenu#encode()`，由 AE2 的 ACTION_ENCODE 服务端处理器编码。
3. 一张选定的唯一 (recipe,targetOutput) 生成一张样板；不计算订单总量。工作台配方为 CRAFTING，机器配方为 PROCESSING。
4. AE2 默认按 ME/玩家库存优先级选择某些原料。保留用户手动选择：处理配方通过缩窄 IRecipeSlotsView 候选/数量；工作台配方必要时使用 AE2 已有 SET_FILTER 槽操作覆盖选定格子，不改 NBT。
5. 概率目标：仅保留选定目标输出，忽略其他输出；手动修改输入 GenericStack 数量，目标输出数量保持 JEI/配方显示值。JEI 无通用概率权重字段，不自动计算概率。
6. 顺序处理：确保 blank pattern slot 有空白样板，填配方 -> encode -> 取走 encodedPatternSlot 输出 -> 等待同步 -> 下一张。样板不足/输出未取走/槽位限制时暂停。
7. build 一次并停止；不上传供应器。
```

## 阶段 7：普通与扩展样板供应器自动分发

```text
直接实现自动分发，不再验证基本兼容性。用户已在 CTI 游戏内确认 AE2 原版样板管理终端能控制普通供应器、ExtendedAE 扩展供应器及分子装配室；普通 9 槽、扩展 36 槽。
1. 复用 AE2 原版 PatternAccessTermScreen/PatternAccessTermMenu。用最小只读 Mixin Accessor 读取 PatternAccessTermScreen 私有 `byId`；PatternContainerRecord 的 getServerId/getInventory/getGroup 为公开方法。
2. 用 inventory.size() 识别 9/36 槽；用 PatternContainerGroup 的 icon/name/tooltip 匹配机器分组。该数据不含 blockPos/唯一 registry ID；无法唯一匹配时让用户选择。serverId 仅当前终端会话有效，不持久化。
3. 按机器分组匹配阶段 6 样板队列，先用空槽，不覆盖已有样板；完整样板内容相同则跳过；一个供应器满时找其他匹配组/供应器。
4. 复用 AE2 原版 GUI 的 InventoryActionPacket(InventoryAction.PICKUP_OR_SET_DOWN, slot, serverId) 远程槽位操作；先通过正常菜单操作将玩家背包中的 encoded pattern 放到光标，再放入供应器。不得创建自定义服务器包或直接写远程库存。
5. 等待 AE2 PatternAccessTerminalPacket 的增量更新反映到 PatternContainerRecord.getInventory() 后再继续。终端关闭、网络断开、分组变化、无空位或操作未成功时停止。
6. 显示已上传/待上传数量、当前分组与槽位，支持取消。只在样板管理终端已打开时运行。
7. 不重复验证手动兼容性，不开发独立终端。build 一次后停止。
```

## 阶段 8：整合测试与发布候选

```text
请只做整合、稳定性、性能、文档与打包，不新增核心功能。阅读 PROJECT_SPEC.md 的各 Gate。
1. 用一条 JEI 一键合成链、一条 AE2 编码链、一条普通/扩展分发链做最小端到端回归；兼容性基础事实不重复调查，只验证自动化操作结果。
2. 保证当前服务器的配方偏好隔离、GUI 按钮缩放定位正常；不要添加未请求的配置管理功能。
3. 避免每帧重建配方树；仅在实际观察到卡顿时做额外性能优化。
4. `gradlew.bat build`；输出 JAR 路径、版本、许可文件及一个必要的未验证点。不生成无要求的长篇报告。
5. 只有经过专用服务器真实测试才可声明‘纯客户端联机功能通过’；不要将单人局域网测试冒充真实服务器测试。
完成后停止。
```

## 日常修错短提示词（仅本项目使用，减少额度）

```text
仅修复我贴出的编译/运行错误，不新增功能、不重构无关文件。优先直接定位报错行和本版本真实方法签名；不要增加兜底、异常捕获、兼容分支或无关检查。做最小修改，运行一次 gradlew.bat build（失败才重试）。回复最多 5 行：原因、改动、结果。
```
