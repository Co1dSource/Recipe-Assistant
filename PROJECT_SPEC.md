# Recipe Assistant — 设计与验证规格 V1.0

> 阶段8（0.8.0，优先于历史规格）：对外更名Recipe Assistant，取消整合包专用定位；内部Mod ID保持兼容。Forge固定43.4.12；JEI唯一强制依赖，AE2/ExtendedAE可选。当前自动合成、编码和分发已实现。依赖隔离、统一UI与逐张真实内容预览见docs/stage8；实际游戏测试状态以该目录GAME_TESTS.md为准。

> 阶段7补充规则（0.7.1，优先于下方历史描述）：未选生产配方是正常外部供应，只表示本树不负责生产，不限定ME或其他库存。编码不要求补选外部材料生产路线；实际合成仍按阶段5已适配的来源和真实数量检查。背包/容器内可识别物品支持悬停G；未新增背包内部取料适配。批量Crafting继承终端替换设置；Processing遵循AE2原生格式，不添加不存在的替换标志。


> 状态：Chat 阶段 0 源码核验已完成，结果见 `API_CHECK.md`。用户已在 CTI 游戏内实测样板管理终端兼容普通/扩展供应器及分子装配室；自动化代码尚未实现。目标是严格客户端新增模组，不修改服务器。
> 制定日期：2026-11-05

## 0. 定位与版本

- Minecraft 1.19.2、Forge 43.4.2、Java 17。
- CTI 989 公开模组清单列出 JEI `11.6.0.1024`、AE2 `12.9.9`、ExtendedAE `1.19-0.2.2-forge`。本地 JAR 尚未提供，构建时使用这些版本，不得自行升级；若本地实例与清单不一致，以本地 JAR/启动日志为准。
- 不安装 EMI；只在客户端添加一个 `ctirecipeassistant` 模组。`mods.toml` 应声明可选/强制客户端依赖并配置适当的服务端版本显示策略，实际通过专用服务器握手测试。
- 不复制 Quick Craft 的服务端自动合成逻辑；只参考或在许可范围内改造 UI/纯算法代码。
- 原版 JEI 的 `+` 不改变；在正在显示的每张 JEI 配方右上方另加一个外观近似 `+` 的按钮，显示 tooltip。默认快捷键 `G` 打开 Quick Craft 风格配方树。

## 1. 模式及交互

### A. Quick Craft 风格配方树（参考、浏览、选择）

- 默认 G 打开鼠标指向物品的配方树；拖拽平移、滚轮缩放，节点切换候选配方、展开折叠、跳转查看配方用途、记忆偏好、循环检测。
- 显示机器类型、输入原材料、候选产物、确定/概率产出（若 JEI 提供概率）；本模块**不执行** Quick Craft 的原服务端自动合成。
- 默认只基于**客户端 JEI 11.6 当前已注册的配方**提取配方候选；不要只使用原版 `RecipeManager`，因为 CTI KubeJS/机器配方可能仅由 JEI 集成展示。用 `createRecipeCategoryLookup().limitFocus(...)` 与 `createRecipeLookup(...).limitFocus(...)` 查找产物，再通过 `createRecipeLayoutDrawable(...)` 读取输入/输出/催化剂槽。
- 保留候选配方的机器分类、配方真实输入/输出、原料替代选项；配方无稳定 ID 时用分类+输入输出+机器标识生成稳定指纹，不使用对象内存地址。

### B. JEI 右上角 +：根据原来打开的界面自动切换

- JEI 的父级界面为 AE2 `PatternEncodingTermMenu` / 对应编码界面时：tooltip `批量编码配方树样板`；点击构造编码计划，不做整树采购数量计算。
- 其他父级界面时：tooltip `一键合成配方树`；只允许选定且**实际要执行**的依赖链都能够在原版 3×3 工作台中执行。
- 非工作台界面允许打开配方树与查看计划，但没有正在打开、可合法操作的 3×3 工作台时不自动执行；按钮禁用并 tooltip 提示需要工作台。不要宣称在背包 2×2 中可执行 3×3 合成。
- 批量编码模式与一键合成模式共享同一份配方选择记忆，但各自管理独立的执行计划。
- JEI 右上角按钮样式：复用 JEI `jei:textures/gui/icons/recipe_transfer.png` 的 7×7 图标，放入 13×13 点击区域；相对于每张展示配方的矩形定位，缩放不乱，多个配方布局分别显示。JEI 11.6 的 `RecipesGui` 私有 `recipeLayouts` 和 `parentScreen` 需要最小只读 Mixin Accessor；通过 Forge `ScreenEvent` 绘制/处理按钮。材料不足、含非工作台实际工序、执行中时灰显，悬停 tooltip 提示原因。

### C. 一键合成（仅工作台，使用 JEI 填料）

- 只使用玩家背包及当前工作台输入槽中的材料，不扫描或移动附近箱子/背包/ME 网络物资。
- 按依赖自底向上规划真实执行次数；允许某个中间配方执行多次，并利用已有中间物品和基础材料。
- 每步：通过 `IRecipeTransferManager#getRecipeTransferHandler(menu, category)` 取得 JEI 已注册处理器，调用 `IRecipeTransferHandler#transferRecipe(..., doTransfer=true)` -> 经服务器确认填入 -> 通过合法工作台容器交互拿走结果 -> 等待背包/槽位状态同步 -> 下一步。不得直接在客户端制造物品，不得发送服务器未知的自定义数据包。
- 整棵树材料不足时新 `+` 禁用（原本 JEI `+` 不受影响）。在连续操作中物品栏变化/背包满/服务器拒绝则停止并清楚提示，不能无限循环或吞物品。
- 技术风险：JEI 11.6 有 `IRecipeTransferManager`、`IRecipeTransferHandler#transferRecipe`，但如何从已展示的配方提取 `IRecipeSlotsView` 并在反复执行时调用，仍需针对真实客户端验证。

### D. 样板编码模式

- 选择配方树后，每一条**被选中的唯一配方/目标产物组合**只生成一张样板；不计算最终订单的材料总需求量。AE2 CPU 负责实际下单的需求计划。
- 工作台配方写合成样板；机器加工写处理样板（不盲目转为原版合成）。
- 相同机器、相同原材料组合且投入/产出比例等价时默认选择**单次目标产出较少**的配方（例如 4 而非 8）；不同机器或同一机器不同原料路线第一次要求玩家选择，按服务器/世界保存偏好，可随时覆盖。
- 如果选中的目标物品是某配方的概率产物：也作为普通目标可选；要求玩家手动填写每种**输入**原材料数量，目标产物输出数量保持 JEI 所示数量不变，忽略其他产物；不要自动概率换算/过量倍率。
- 概率处理风险必须有简短 tooltip：AE2 CPU 通常按样板的确定数量规划，真实随机输出不足仍可能让任务卡住。
- 复用 AE2 12.9.9 注册的通用 `EncodePatternTransferHandler`，通过 JEI `IRecipeTransferManager` 取得并调用；它会调用 `EncodingHelper` 将配方填入 `PatternEncodingTermMenu` 的 FakeSlot。随后单独调用 `PatternEncodingTermMenu#encode()`，由 AE2 已注册的 `ACTION_ENCODE` 服务端处理器使用 `PatternDetailsHelper` 编码。不直接编辑物品 NBT、不凭空生成样板；空白样板不足、编码输出未取走、输入/输出超过样板限制时暂停。
- 编码中不自动跨界面开启远程终端。完成编码后引导玩家打开样板管理终端进行自动分发。

### E. 样板自动分发（阶段 7）

- 同时支持 AE2 原版 9 槽样板供应器和 ExtendedAE 36 槽扩展样板供应器，包含方块与部件形态。
- **用户已在 CTI 1.19.2 Forge 游戏内验证**：AE2 原版样板管理终端可以管理普通样板供应器、ExtendedAE 扩展样板供应器，以及连接的分子装配室。阶段 7 不再验证这些基本兼容性。
- 源码确认：AE2 `PatternProviderLogic` 默认容量为 9；ExtendedAE `TileExPatternProvider` 和 `PartExPatternProvider` 均构造 `PatternProviderLogic(...,36)`。
- 统一使用 AE2 原版 `PatternAccessTermMenu` / `PatternAccessTermScreen`。不开发独立样板管理终端。
- `PatternAccessTermScreen` 的私有 `byId` 保存客户端 `PatternContainerRecord`；使用最小只读 Mixin Accessor 读取。`PatternContainerRecord` 公开 `getServerId()`、`getInventory()`、`getGroup()`；槽位数由同步库存大小取得。
- `PatternContainerGroup` 同步机器分组的 icon/name/tooltip，但不包含机器方块坐标或唯一 registry ID。因此按分组信息匹配；无法区分的同名目标由玩家手动选择。**serverId 是当前终端会话 ID，不持久化。**
- 使用 AE2 原版 GUI 同样的 `InventoryActionPacket(InventoryAction.PICKUP_OR_SET_DOWN, slot, serverId)` 完成远程槽位交互；先通过正常玩家背包菜单操作将已编码样板放到光标，再放入目标供应器。不得创建自定义服务器包或直接写远程库存。
- 服务端改变后，AE2 通过 `PatternAccessTerminalPacket` 完整/增量更新客户端 `PatternContainerRecord`。等待对应槽位同步出现目标样板后再继续。
- 优先空槽，不覆盖已有样板；比较完整编码样板内容以避免重复；当前供应器空间不足时继续寻找其他匹配供应器；无空位、终端关闭、网络断开或分组变化时停止。
- 自动分发只在 AE2 原版样板管理终端打开时执行；不扫描世界或周围容器，不尝试在终端关闭时读取 ME 网络。

## 2. 建议的数据模型与模块边界

核心数据：

- `ItemIdentity`：物品 ID + 必需 NBT + 目标数量单元；防止同名不同 NBT 合并。
- `RecipeCandidate`：配方来源/分类 ID、稳定配方 ID 或指纹、机器类型、输入及替代项、全部输出（含概率/未知概率标记）、工作台可执行标志。
- `RecipeNode`：目标产物、一组候选配方、当前选择、子节点、手动终止/循环标记。
- `RecipeSelectionStore`：按服务器/世界和目标物品、分类、机器、输入路线的指纹记忆；数量调整单独保存。
- `CraftPlan`：仅一键合成模式使用，拓扑有序步骤、次数、基础材料可用性及阻塞原因。
- `PatternPlan`：去重后的 `(recipeId/fingerprint, targetOutput)` 队列、模式、实际输入输出和目标机器分类；不包含总订单数量。
- `ProviderSnapshot`：终端当前会话供应器 ID、显示组、槽位数、已用槽位、可用槽位、用户绑定；执行时只操作最新快照。

包结构建议：

```text
com.example.ctirecipeassistant
  client/keys, client/gui, client/jei, client/state
  recipe/model, recipe/index, recipe/tree, recipe/preferences
  crafting/plan, crafting/execute
  ae2/encoding, ae2/distribution, ae2/adapters
  config, diagnostics
```

接口分离原则：

- `RecipeIndex` 只产生候选，UI 不直接访问 JEI 私有对象。
- `TreeBuilder` 只管理配方树与选择，不调用实际合成、编码或上传。
- `CraftPlanner` 是唯一负责手动工作台执行数量计算的组件；`PatternPlanner` 不调用它。
- `RecipeTransferAdapter` 独立封装 JEI 11.6，拒绝根据最新 JEI API 猜接口。
- `EncodingAdapter`/`DistributionAdapter` 分别封装 AE2 菜单交互。
- 客户端任务统一通过等待服务端可见结果的状态机，不使用无间隔批量点击。

## 3. 核心选择算法（语言无关）

```text
resolve(node):
  candidates = JEI 注册配方中所有能产出 node.target 的候选；包含可能的概率输出
  remove cycles and invalid candidates; allow manual terminal/leaf cut-off
  if 用户保留的配方偏好仍存在: select it
  else group candidates by (machine category, ingredient route)
       if multiple machine categories or ingredient routes: require user selection
       else prefer smallest positive output count among equivalent input/output-ratio recipes
  if selected target is probabilistic: request user input quantities (keep selected output count unchanged)
  expand selected input nodes recursively; detect cycles and bound max depth/max nodes

patternPlan(tree):
  traverse selected recipes
  emit at most one PatternTask per (recipe fingerprint, selected target output)
  never aggregate target order quantities or auto-calculate chance compensation

craftPlan(tree, targetCount, actualInventory):
  require all executed steps be ordinary crafting-table recipes
  determine counts bottom-up, apply owned inventory and remainders
  disable new JEI + if insufficient materials or no available 3x3 menu
  if ready: execute JEI transfer -> server sync -> vanilla output pickup -> server sync per step
```

若配方不确定、概率信息未暴露、流体或工具耐久消耗无法正确表达，标记为待确认，不做静默错误编码。

## 4. 阶段 0 真实 API 调查结果

详细签名、源码位置和限制见 `API_CHECK.md`。阶段 0 已由 ChatGPT 完成，Codex 不应再次执行阶段 0。关键结论：

1. Quick Craft 当前主干只有 1.20.1 和 1.21.1 版本，没有 1.19.2 版本。`TreeLayout`/`NodeView` 可作为布局参考；`QuickCraftScreen` 很大且依赖 EMC、站点扫描、服务器 CraftService，不直接整文件移植。MIT 代码若复制需保留 LICENSE。
2. JEI 11.6 公开支持配方类别/配方查找、`IRecipeLayoutDrawable` 槽位视图、`IRecipeTransferManager` 和 `IRecipeTransferHandler#transferRecipe`。配方树从 JEI 注册类别读取，不只从 Minecraft `RecipeManager` 读取。
3. JEI 11.6 `RecipesGui` 的 `recipeLayouts` 和 `parentScreen` 是私有字段；逐配方按钮需要最小只读 Accessor Mixin。`IRecipeLayoutDrawable` 可提供配方对象、类别、槽位视图及布局矩形。
4. JEI `IRecipeSlotView` 提供槽位角色和候选 ingredient，但没有通用概率/权重字段。输出候选可读取；概率概率值不能由 JEI 通用 API 推断，按用户手动选择/标记，不做概率计算。
5. JEI 一键填料可通过当前菜单对应的 `IRecipeTransferHandler` 调用。只允许在原版 `CraftingMenu` 使用普通工作台 handler，并对每次转移等待服务器同步。
6. AE2 已注册通用 `EncodePatternTransferHandler` 到 `PatternEncodingTermMenu`。它通过 `EncodingHelper` 将 JEI 配方写入 AE2 FakeSlot；**该 transfer handler 不负责最终编码**，还要调用 `PatternEncodingTermMenu#encode()`。
7. AE2 处理样板 transfer 会从 JEI 输入槽读取所有候选并按 ME/玩家物品优先级选材；用户手动选择的输入和概率数量需要通过缩窄槽候选/包装 `IRecipeSlotsView` 或 AE2 现有 `SET_FILTER` 槽操作覆盖。处理样板输出只取每个 JEI 输出槽的首个候选，批量编码目标副产物时应只保留所选目标输出。
8. 用户已实测 AE2 原版样板管理终端能管理普通与扩展供应器及分子装配室。AE2 `PatternAccessTermScreen` 客户端记录、`PatternContainerRecord`、`InventoryActionPacket` 和 `PatternAccessTerminalPacket` 提供自动分发所需的现有路径；只需实现客户端编排。

## 5. 验收条件与暂停门槛

- Gate 0：`gradlew build` 成功；纯客户端能加入未装本模组的 CTI 服务器；无额外必需模组；核对 JAR 版本。
- Gate 1：配方树 GUI 可 G 打开，切换候选、循环保护、配方记忆；在测试世界用 KubeJS 自定义配方、机器多产出配方验证。
- Gate 2：JEI 每个显示配方右上角单独 +；单/多配方同屏及 GUI 缩放无错位；能拿到正确候选配方 ID，悬浮正确。
- Gate 3：原版工作台 3 步合成链，含需要重复制作的中间配方；实际背包不足时灰显；服务器拒绝/背包满时停止；无凭空物品。
- Gate 4：AE2 编码终端能写 1 张合成和 1 张处理样板，再批量 N 张；空白样板不足暂停；概率输出输入可手动设定，输出不变。
- Gate 5：已知 9/36 槽供应器在原版管理终端的基本兼容性已由用户实测确认；自动分发实现后验证客户端通过 AE2 现有槽位协议写入并收到增量更新。
- Gate 6：分发时按分组信息匹配目标机器；同名/同图标无法唯一识别时手动选择；逐个确认回执；断开网络/切换终端/取消时安全停止。
- Gate 7：在 CTI 多人服务器稳定运行。任何联网合成/分发功能若仅单人成功，仍视为未达标。

## 6. 已知未证实风险

- 多模组 CTI 机器的 JEI 配方输出是否完整提供概率、流体与输入数量；不能仅遍历 Minecraft RecipeManager。
- JEI 11.6 的配方填入处理器在实际 CTI 服务端能否连续调用并正确等待工作台菜单同步。
- AE2 菜单/终端在真实 CTI 多人服务器中自动化点击后，是否每次都按预期发出同步更新；手动兼容性已验证，但自动化写入仍需测试。
- JEI 右上角按钮实现的 Mixin 目标在实际 JEI `11.6.0.1024` 文件中是否与 `v11.6.0` 源码一致（通过编译和启动检查）。
- 机器加工的多产物/概率输出修改属于**样板声明**，AE2 不能保证机器结果符合该声明；失败情况必须允许停止/提示。
- AE2 终端分组信息只有 icon/name/tooltip，不提供机器方块坐标/唯一 registry ID；inventory ID 仅在当前 GUI 会话有效。

## 7. 开源及引用

- Quick Craft 源码：https://github.com/Sxilverrr/Quick-Craft （MIT；当前主干含 1.20.1/1.21.1，没有 1.19.2；复制代码需保留版权和许可证）
- JEI 11.6.0：https://github.com/mezz/JustEnoughItems/tree/v11.6.0 （MIT）
- AE2 12.9.9：https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/v12.9.9 （LGPL，研究并调用 API；若复制代码遵守许可证）
- ExtendedAE 1.19-0.2.2：https://github.com/GlodBlock/ExtendedAE/tree/1.19-0.2.2-forge （LGPLv3）

禁止拷贝第三方 `src` 整目录并去掉版权。只需引用第三方依赖，不应将其代码打包进最终 JAR。
