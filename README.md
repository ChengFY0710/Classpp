## 致谢：

https://github.com/chrisbanes/haze

https://github.com/Nevodev/Cresto

https://github.com/Kyant0/Shapes

https://github.com/Kyant0/AndroidLiquidGlass

https://github.com/coil-kt/coil

https://github.com/mikepenz/multiplatform-markdown-renderer

https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse （Wakeup 导入适配脚本 wake_up.js 来源）

---

# Class Plus Plus（Classpp/Class++）
**一款简洁高颜值安卓课表app。**

起初只是一份设计稿，充满着作者对安卓端简洁好看的课表app的所有幻想。在接触Vibe Coding工具之后，在AI的帮助下将这些想法一点一点的变成了现实。在此还要再次感谢这些优秀的开源项目，Classpp的成功运行，离不开这些优秀项目的加持。

---

Developing...

已知bug：
+ PopupMenuCard组件出场退场动画待优化。
+ 待办编辑页，键盘聚焦滚动性能待优化。
+ ~~浮层内输入框唤起输入法后，点击popupSelectCard组件，键盘闪烁退出。~~（2026-11-10 已修复：选择行菜单改非焦点弹层，并让本次收键盘不回滚内容；待真机复核）
+ 调休浮层、账号与同步设置子页，会触发Haze2 RenderNode无限递归崩溃问题，目前均退让规避。
+ 日程页02-04态，04DayCalendar组件展开收起，手势有概率发生冲突、元素错位。

---

如您发现本项目存在任何侵权情形，请及时与我联系；一经核实，将立即移除本项目相关内容。

---

## 免责声明：

本项目为纯 Vibe Coding 个人开源项目，未经安全审计，按"现状"提供。**使用本项目的任何安全风险及后果均由您自行承担，作者概不负责。** 使用前请完整阅读 [DISCLAIMER.md](DISCLAIMER.md)，不同意请勿使用。

请注意：AI 对话会将聊天内容、图片以及课表/待办等上下文发送至你选择的第三方 AI 服务商；教务同步、Wakeup 导入与远端学校列表也会访问对应第三方服务。导出备份为未加密 ZIP，请自行妥善保管。

---

## 协议：

本项目采用 [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) 发布，完整条文见 [LICENSE](LICENSE)。

+ 允许任何**非商业目的**的使用、修改与分发（个人学习、研究、爱好项目、非营利组织等均属非商业用途）；
+ **禁止**将本项目（含基于本项目的修改版、衍生作品）用于任何商业用途。

`tools/schoolsync/` 是 `classpp_schoolsync` 仓库的镜像备份，该目录内容按 Apache-2.0 授权（见 `tools/schoolsync/LICENSE`），不适用本项目的 PolyForm 非商业条款。

Copyright (c) 2026 ChengFY

本项目依赖的第三方组件（haze、Cresto、Shapes、AndroidX、kotlinx.serialization 等）均遵循其各自的 Apache-2.0 协议。