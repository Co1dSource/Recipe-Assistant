# MIT发布核对

基线232204e，开始时工作区干净。此次仅变更许可、作者/版本元数据、打包及文档，没有修改功能源码。版本0.8.2，Git提交作者和仓库创建者的署名记录用于核对版权持有人Co1dSource。

- 根目录LICENSE使用完整MIT文本，仅填写2026及Co1dSource；正文与保留的标准MIT模板逐词核对一致。
- mods.toml声明license="MIT"、authors="Co1dSource"；README保留简短MIT链接。Gradle将同一LICENSE全文放入META-INF/LICENSE。
- 原LICENSE.txt是Forge MDK许可，原样移入licenses/FORGE-MDK-LICENSE.txt，避免将第三方许可当作项目主许可。
- Quick Craft原作者Sxilverrr的MIT全文和版权保持原样。第三方边界在THIRD-PARTY-NOTICES.md和JAR内META-INF/THIRD-PARTY-NOTICES.txt分别说明。
- 正式JAR不含JEI、AE2、ExtendedAE或其他第三方模组的类、资源命名空间、嵌套JAR或开发测试文件；项目内调用API的联动代码仍属于本项目。
- gradlew.bat build --console=plain成功，含reobfJar；本轮无功能改动，不重复游戏/功能检查。Gradle test为NO-SOURCE。
- python docs/mit-release/check_jar.py通过，验证0.8.2/作者/MIT正文及嵌入文本/依赖声明/Java17/归档边界。reference/mods各JAR SHA-256未变，Forge MDK和Quick Craft许可文本字节未变。
- JAR：releases/recipeassistant-0.8.2.jar，已与build/libs原产物核对哈希，见ARTIFACT.json。历史版本JAR保留原发布内容，不覆盖。
- 用户确认Modrinth与CurseForge未发布；仅准备两平台一致的MIT声明，见docs/RELEASE_LICENSE.md，没有创建项目或声称在线页面已更新。

标准MIT文本依据：[Open Source Initiative](https://opensource.org/license/mit)。该链接用于核对文本，不替代项目根目录LICENSE。GitHub识别结果在推送后通过官方API单独核对，原始回执保存于outputs/mit-release-082。

同步时发现远端用户新增README提交eaef056，已保留该提交全部文案，只追加MIT链接；本地MIT提交重放后正常推送，不强制覆盖远端。
