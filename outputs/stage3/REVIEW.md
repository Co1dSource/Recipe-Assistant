# 阶段 3 审查

## 0.3.1 用户反馈修正

- 已建立 Git 仓库。本轮基线为 `0f113d48f2a620dec108ee7225c5dd07d15d5df8`，开始时工作区干净；由 Codex 直接修正。
- 截图中新增按钮偏左原因是使用布局右侧 +4，而 JEI 原按钮为 +6；现直接复用原填入按钮横坐标。原按钮没有修改。
- 新增按钮仅在普通 3×3 工作台、AE2 CraftingTermMenu 合成终端、PatternEncodingTermMenu 编码终端显示。合成模式还限制为 JEI RecipeTypes.CRAFTING；烧炼/高炉/烟熏/营火/切石等隐藏。编码模式仍保留机器配方入口。
- 绘制、悬浮和点击沿用同一面板/类别条件，隐藏时不会占用原位置点击。未增加实际合成或编码。
- `outputs/stage3-button-fix/build-03.log`：最终构建成功，含 reobfJar；`check.log`：真实生产代码的列对齐、4 种高度/命中边缘、无父级隐藏、合成类别过滤及编码模式保留检查通过。真实 AE2 菜单与游戏视觉仍待复测。
- 原先“其他正常界面显示合成按钮”的步骤由用户本轮反馈覆盖，最新验收见 GAME_TESTS.md。以下为 0.3.0 历史记录。

项目：`D:\Documents\Project\Recipe Assistant`。用户明确授权 Codex 直接实现，未使用 Qoder；Qoder 每次执行备份规约未触发。非 Git 项目，原文件副本及指纹见 `baseline-files/` 和 `baseline.json`。

## 文件与实现

新增源文件：
- `mixin/RecipesGuiAccessor.java`：只读访问 `recipeLayouts`、`parentScreen`，JEI 目标 `remap=false`，无 setter、无修改列表。
- `client/jei/RecipeActionButtons.java`：Forge Render.Post 绘制，MouseButtonPressed.Pre 高优先级拦截本按钮的左键；命中后立即以该 layout 构造上下文。绘制与点击共用坐标和边界函数。
- `client/operation/ClientRecipeOperation.java`：真实 recipe/category/slotsView/rect、RecipeCandidate、目标和父屏幕；两个模式枚举。
- `client/operation/RecipeOperationDispatcher.java`：统一保存最近一次 pending 上下文，输出未执行提示；按钮状态 ENABLED/DISABLED/DISABLED_REASON。
- `src/main/resources/ctirecipeassistant.mixins.json`：仅 client 项，Manifest 的 MixinConfigs 注册。

修改源文件与配置：`JeiRecipeIndex.java` 提取已有布局→RecipeCandidate 方法，原树查询继续复用同一实现；`TreeJeiPlugin.java` 在 runtime 失效时清理 pending；`build.gradle` 注册 Mixin；`gradle.properties` 版本 0.3.0。
文档：本目录计划、基线、检查、游戏步骤及根目录 DEVELOPMENT_STATUS/HANDOFF。

按钮直接使用 JEI 11.6 的 GuiIconButtonSmall、Internal.getTextures().getRecipeTransfer()，因此复用其 7×7 原图、背景、悬停和声音；不复制 JEI 绘制实现。位置为布局内容右侧 4px、顶部，短配方与原按钮重叠时向上避让。不存在覆盖输入/输出内容的内部按钮。

模式唯一依据：parentScreen 为 AbstractContainerScreen，且其 getMenu() 为 PatternEncodingTermMenu。PatternAccessTermMenu 不满足该类型，其他界面及 null 父级都返回 ONE_KEY_CRAFT。每次绘制、悬停和点击重新判断，没有初始化时缓存模式。
最终 JAR 字节码确认该分支调用重混淆后的容器 getMenu 方法并检查真实编码类，见 `bytecode.txt`。本地管理终端实际为 `appeng.menu.implementations.PatternAccessTermMenu`，继承 AEBaseMenu，与编码菜单没有继承关系。

默认目标取首个非空输出槽的当前显示候选；其他输出不删除。上下文保留原始 slotsView 及 recipe，不按名字寻找相似配方。没有执行引擎，不改变容器、物品或网络。

## 验证证据

- 首次构建在沙箱内连接本机 Gradle daemon 失败，没有进入编译；通过宿主授权后 `build-02.log` 成功。
- 定向检查发现 Rect2i.contains 的右下边界包含性与 JEI 原生按钮不同；修正为原生 Button 相同的排他右下边界。最终 `build-03.log`：BUILD SUCCESSFUL in 8s，退出 0，包含 reobfJar。Gradle test 为 NO-SOURCE。
- `Stage3Check.java` 调用真实生产类：4 种配方高度、原按钮不相撞、不同布局平移、点击边缘、null 父级模式、准确 recipe/target 及多输出上下文通过，见 `check.log`。不把该检查当作真实 AE2 菜单或游戏渲染测试。
- `artifact-verification.json`：归档可读取、Manifest/Mixin 配置完整、新入口 class 为 Java 17 major 61、无第三方 JAR 嵌入、源码未发现实际 transferRecipe/encode/物品点击/发包调用。
- 对照基线审阅 `changes.diff`：阶段 1 初始化、键位、树 GUI、偏好、身份及布局类未修改；仅阶段 2 索引提取复用和 JEI runtime 释放入口有相关变化。
- 产物 `build/libs/ctirecipeassistant-0.3.0.jar`，58567 字节，SHA-256 `bc06a09ce367b36b697e024785f3857153d231dd2b333023a2da685a3182f3b0`。

## 验收边界

没有已知编译阻塞。Mixin 在真实 CTI 中应用、视觉位置、GUI 缩放、多配方点击及编码/管理终端模式仍待用户按 GAME_TESTS.md 验收。阶段 1/2 已由用户在本次任务确认游戏验收完成；不重跑其全部测试。本阶段完成实现后停止，不开始阶段 4。
