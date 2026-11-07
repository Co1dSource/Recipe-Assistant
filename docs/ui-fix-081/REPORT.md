# Recipe Assistant 0.8.1 修复记录

本次由Codex直接实现，基线499ff9a，开始时工作区干净。未调用Qoder或子代理。用户追加要求：编码预览不算手动配方树历史，G不能恢复最后编码的物品。

## 修改

| 文件 | 内容 |
| --- | --- |
| RecipeTreeScreen.java | 节点展开/选配方按钮、删除操作、页码移除原版文字阴影 |
| PatternPreviewScreen.java | 数量页标题、材料名称、输出与错误改为无阴影；深度框新增JEI原生13×13 −/+，无Tooltip，0/最大int禁用对应按钮，保留原保存和即时刷新 |
| DistributionPreview.java | 12×12分发图标与原生12px滑块同列，y4上移到y0，间隔6px；Tooltip从原先700+400改为350+400=750，保留自动分发文本并恢复调用者PoseStack |
| RecipeInteractions.java | 编码目标不匹配手动树时创建独立临时树，不写last；匹配时复用手动树选择，只有主动打开树才进入G历史 |
| README.md | 只介绍功能、特点、操作、0.8.1版本支持及依赖/联动，移除构建、内部实现、开发验收等段落 |
| gradle.properties / releases | 版本0.8.1；发布JAR为releases/recipeassistant-0.8.1.jar，复制后核对SHA-256 |
| HANDOFF / DEVELOPMENT_STATUS / API_CHECK | 更新有效断点、绘制限制及原生API证据 |

GitHub私有仓库Co1dSource/Recipe-Assistant的About描述已通过官方API更新并GET读回核对，仅修改description字段。内容：

> Minecraft 1.19.2 Forge 客户端配方助手：JEI 配方树与一键合成，可选 AE2 批量样板编码及普通/扩展供应器自动分发。

## 验证

- G历史回归先在0.8.0失败：仅编码即错误填入G历史。修复后通过：空历史仍为空，手动浏览A后编码B仍恢复A，编码A时复用原选择。
- EncodingHistoryCheck及UiControlsCheck均通过。后者验证深度0、普通值、最大int的加减；按钮与原生Scrollbar边界同列同宽、间隔至少5px；Tooltip保留文本、最终层级750在裁剪内且不污染调用者PoseStack。
- gradlew.bat build --console=plain：BUILD SUCCESSFUL，包含reobfJar。Gradle test为NO-SOURCE，不当作单元测试通过。
- 独立JAR检查通过：Recipe Assistant 0.8.1、固定依赖、可选集成、Java17、Mixin配置，无第三方类、嵌套JAR或测试文件。文件与哈希见ARTIFACT.json。
- 原始证据位于outputs/ui-fix-081：history-red.log、EncodingHistoryCheck.log、UiControlsCheck.log、build.log和repository-description-result.json。测试源码保存在本目录checks。

本轮没有运行真实Minecraft图形客户端。重影消除后的视觉、实际悬停提示、各GUI缩放和实际点击仍需游戏确认；不将无游戏检查冒充视觉验收。未改合成、编码或上传执行器及其服务器同步顺序。
