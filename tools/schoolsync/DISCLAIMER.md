# Classpp 远端学校声明仓库免责声明

**Classpp / Class Plus Plus（Classpp / Class++）远端学校适配分发源**
（主项目目录：`tools/schoolsync/`；发布地址：<https://gitee.com/ChengFY0710/classpp_schoolsync>）

最后更新：2026-11-08

> **请在使用、登记、分发或依赖本仓库任何内容之前，完整阅读本声明。您访问、拉取、使用、复制、修改、提交或分发本仓库内容，即视为您已阅读、理解并同意本声明的全部内容。如不同意，请立即停止使用并删除本仓库及其全部副本。**

---

## 1. 仓库性质

1.1 本仓库是 Classpp 的**远端学校适配分发源**，仅包含 JSON 声明与说明文档：`index.json`（学校清单）、`schools/<id>.json`（单校声明）、`templates/demo.json`（模板）与 `examples/xmu.json`（参考范例）。声明面向 Tier 1 能力（WebView 登录 + JSON 待办接口 + 可选声明式教务课表导入），仅描述请求、字段与模板；App 使用固定脚本取数，**不执行远端 JavaScript/HTML**。它不是 App 本体的发布渠道，不包含 APK、安装包、更新器或任何可执行程序。需要原生登录/自动续期的学校属于 App 内编译期适配，不在本仓库范围内。

1.2 本仓库由个人开发者出于兴趣爱好维护，属于**非官方**项目，与 Classpp 主项目同源；内容可能由 AI 编程工具辅助生成、整理或校对，**未经学校、教育机构或任何第三方的审核、授权或认证**。

1.3 本仓库按“现状”（AS IS）与“现有可用”（AS AVAILABLE）提供，作者不保证内容的正确性、完整性、时效性、安全性、合法性或适用于任何特定用途。

1.4 本声明针对本仓库的声明文件与文档。Classpp App 本体、其联网功能与数据处理另见主项目的 [DISCLAIMER.md](https://github.com/ChengFY0710/Classpp/blob/main/DISCLAIMER.md)；两者可同时适用。若本声明与主项目声明存在冲突，就 App 使用行为以主项目声明为准，就本仓库内容以本声明为准。

## 2. 非官方声明与第三方关系

2.1 本仓库与任何学校、教育机构、教务系统、课程中心、统一身份认证服务提供方，以及 Gitee 等托管平台**均无隶属、合作、授权、代理或背书关系**。本仓库不代表上述任何机构的立场。

2.2 声明中出现的学校名称、系统名称、接口路径、字段名、界面文案等，仅用于描述互操作配置（登录域名、取数路径、字段映射等），**不构成对任何学校的授权、认证或官方适配**，也不代表学校允许、认可或推荐本仓库或 Classpp。

2.3 相关名称、商标、标识、服务标记的权利归各自权利人所有。本仓库仅在必要范围内为说明兼容性而提及，不主张任何权利。

2.4 声明内容可能过时、错误、不完整，或因学校系统升级、接口变更、域名调整、页面改版而失效。**请始终以学校官方系统展示的内容为准。**

## 3. 信任边界与使用风险（致用户）

3.1 远端学校能力在 Classpp 中**默认关闭**，只在用户于学校选择浮层手动点击「更新学校列表」时联网拉取；App 不会在启动、后台或定时任务中自动访问本仓库。拉取成功后声明缓存在本机，冷启动只读本地缓存，不自动联网更新。

3.2 首次连接某所远端学校时，App 会展示学校名与登录域，要求用户确认；可选教务课表页的域名也会一并展示。确认记录仅保存在本机。**请务必核对域名是否为该校真实域名，再确认登录。**

3.3 App 会对声明做硬校验（例如域名必须以 `.edu.cn` 结尾、仅允许 https 与默认端口、禁止通配符与 userinfo、模板占位符必须已声明等），但**这些校验只能降低部分风险，不构成安全审计或安全保证**。声明仍可能包含错误配置、过时路径，或被第三方镜像篡改。

3.4 使用远端学校功能时，登录与取数由您的设备直接发往声明所指向的学校域名；Classpp 仅在本机保存会话 Cookie。保存凭据与自动续期属于 App 内编译期能力（仅部分学校支持），**不是本仓库提供的**。本仓库**不会收到**您的账号、密码、Cookie、待办内容或课表数据。

3.5 远端学校注册后，待办的每日自动同步会按 App 设置访问该校数据域；这属于 App 的同步功能，**不会因此向本仓库发起请求**，也不会自动检查本仓库的声明更新。

3.6 您理解并同意：

- 仅使用**您本人合法拥有或有权使用**的账号；
- 遵守学校规章制度、账号使用规范及适用法律法规，不利用本功能绕过登录、多因素认证、访问控制、频率限制或其他安全措施，不进行批量抓取、爬取他人数据或任何未经授权的访问；
- 自行判断使用行为是否符合学校规定与第三方服务条款；
- 因账号被限制、封禁、密码泄露、数据错误、同步重复/遗漏/延迟、课表导入错误等产生的后果，由您自行承担；
- 建议为教务/课程中心使用独立密码，避免在公共 Wi-Fi 等不可信网络下登录；发现异常登录或账号异常时立即自行处理；
- 不将本功能用于代刷课、自动签到、爬取成绩或他人信息、绕过学校系统限制等违反学校规定的用途；
- 不使用来源不明、被修改或非官方分发的声明；发现域名、路径或行为异常时，停止更新，必要时清除 App 数据或卸载。

3.7 远端声明可能因本地缓存、Gitee/CDN 缓存或客户端版本差异而滞后；已注册学校的声明变更通常需要重启 App 后才生效。不同版本的 App 对 `schemaVersion` 的支持范围不同，旧客户端可能整体忽略较新的清单。

## 4. 数据处理与隐私

4.1 本仓库是**静态数据仓库**，作者不运营用于收集用户数据的服务器，也不会通过本仓库主动收集、上传或存储您的账号、密码、Cookie、学号、待办、课表或其他个人信息。

4.2 拉取声明时，您的设备会向 Gitee 的 raw 地址发起普通 HTTPS 请求。该请求本身不包含账号、密码、Cookie 或待办内容；但作为网络访问，Gitee、CDN 及其基础设施可能按平台规则记录 IP 地址、访问时间、User-Agent、请求路径等日志。这些日志由平台控制，作者通常无法访问或删除，其处理规则以 Gitee 的隐私政策为准。

4.3 声明在本机缓存后，其读取、更新与删除由 Classpp 在本地完成；App 如何处理本地数据、系统备份与导出，见主项目 [DISCLAIMER.md](https://github.com/ChengFY0710/Classpp/blob/main/DISCLAIMER.md) 的相关章节。

4.4 请勿在提交的声明、示例、文档、issue 或 PR 中放入任何个人数据、账号密码、Cookie、Token、学号、身份证号、联系方式或其他敏感信息。

## 5. 贡献与登记纪律

5.1 向本仓库提交声明（包括新增学校、修改路径/字段/文案、提交示例或文档）即表示您确认并保证：

- 您是基于**自己合法持有的账号**、在遵守学校规定与适用法律的前提下获取并整理相关信息的；
- 您未通过入侵、绕过认证、抓取他人数据、破解接口或其他不正当手段获取内容；
- 提交内容不包含账号、密码、Cookie、Token、密钥、学号、姓名、联系方式等个人数据或凭据；
- 提交内容不包含 JavaScript、HTML、可执行文件、脚本片段或其他可执行内容；本仓库只接受声明式 JSON 数据；
- 所有 host（登录域、取数域、头像域、课表域）均属于真实的高校教育域名（以 `.edu.cn` 结尾），不包含重定向到非教育域名、恶意域名、通配符或 IP 地址的配置；
- 不包含绕过登录、多因素认证、访问控制、频率限制或付费/权限限制的配置；
- 不包含批量抓取、自动化刷取、攻击、探测或其他危害学校系统稳定性的用途；
- 不包含代刷课、自动签到、刷取成绩、爬取他人信息或其他违反学校规定的用途；
- 公开接口路径、字段名或文档前，已自行确认学校允许公开；不确定时不予提交；
- 不侵犯学校、第三方或任何人的知识产权、商标权、隐私权或其他合法权益；
- 不包含商业推广、收费服务、引流或与 Classpp 无关的内容。

5.2 作者有权自行决定是否接受、修改、删除或拒绝任何提交，且无义务说明理由。对于错误、过时、存在安全风险、违反学校规定或法律法规、或收到权利人异议的声明，作者可在不事先通知的情况下修改或移除。

5.3 **已发布学校的 `id` 不应随意更改**：`id` 是存储命名空间、待办来源与本地 id 前缀，更改会导致已导入数据的归属丢失。声明发布后请保持 `id` 稳定。

5.4 提交即视为您同意您提交的内容按 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 授权，允许作者及任何遵守该许可证的人复制、修改、分发、展示与再许可；您保留自己内容的著作权，并自行对提交内容负责。

5.5 本仓库的维护者不是您的法律顾问。若您不确定某一声明是否合规，请先咨询学校相关部门或专业人士，不要提交。

## 6. 安全、可用性与支持

6.1 本仓库不提供任何明示或默示的保证，包括但不限于适销性、特定用途适用性、不侵权、无病毒、无错误、持续可用等保证。

6.2 本仓库可能随时被修改、暂停、迁移或删除，作者无义务提前通知，也无义务提供技术支持、安全更新、缺陷修复或回应 issue/PR。

6.3 第三方镜像、缓存、代理或非官方分发的副本不受作者控制；其中可能包含被篡改、过期或恶意的内容。请只从作者维护的官方地址获取声明，并自行核验。

6.4 若您发现安全漏洞、恶意声明、错误配置或侵权行为，欢迎通过主项目仓库公开的联系方式反馈；作者会在合理范围内处理，但不承诺处理时限或处理结果。作者不代表学校，也无法协助处理账号申诉、解封、成绩更正或数据恢复。

## 7. 责任限制

在适用法律允许的最大范围内：

- 作者不对因访问、拉取、使用、无法使用、修改或分发本仓库内容而导致的任何直接、间接、偶然、特殊、惩罚性或后果性损害承担责任，包括但不限于数据丢失、账号封禁或限制、学业或工作损失、设备损坏、隐私泄露、第三方索赔、利润损失等；
- 无论该等损害基于合同、侵权、严格责任或其他任何法律理论，亦无论作者是否已被告知该等损害的可能性；
- 本声明不排除或限制依法不可排除或限制的责任（例如因故意或重大过失造成的人身损害）。若您所在司法辖区不允许上述部分排除，则相应条款在该辖区内不适用，其余条款仍然有效。

## 8. 知识产权与下架

8.1 本仓库的声明结构、文档与示例由作者或贡献者整理；学校名称、系统名称、商标、标识等权利归各自权利人所有。

8.2 如您是学校、教育机构、系统提供方或权利人，认为本仓库中的某项声明、文案或示例侵犯了您的合法权益、违反了学校规定或存在安全风险，请通过主项目仓库公开的联系方式与作者联系，并提供可核实的信息。作者核实后会在合理范围内修改或移除相关内容。

8.3 贡献者应保证其提交内容不侵犯第三方权利；因贡献者提交内容引发的争议、投诉或索赔，由贡献者自行承担，作者不承担连带责任。

## 9. 许可

9.1 本仓库的声明文件与文档按 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 提供。您可以在遵守该许可证的前提下使用、复制、修改与分发本仓库内容（包括商业用途），但须保留版权声明、许可证文本及相关 NOTICE（如有），并遵守许可证中的专利与免责条款。具体以发布仓库根目录的 `LICENSE` 文件为准。

9.2 第三方镜像、修改版或衍生分发不受作者控制，其许可状态与内容合规性由相应分发者负责；作者不对其承担责任，也不对其作出任何背书。

9.3 本仓库可能包含的第三方名称、接口约定与字段说明，其权利仍归各自权利人；本声明不授予您任何商标权、商号权或官方认证资格。

## 10. 其他

10.1 本声明可能随时更新，更新后自发布之时起生效，不另行通知。请以本仓库中的最新版本为准。

10.2 如本声明的任何条款被认定为无效或不可执行，不影响其余条款的效力。

10.3 本声明的解释与适用，以适用法律允许的最大范围为限。本声明以中文为准；英文摘要仅为方便阅读，不构成对中文条款的替代或修改。

---

## English Summary

This repository (`classpp_schoolsync`) is the **data-only** remote school declaration source for Classpp, a personal non-commercial open-source project. It contains JSON declarations and documentation only — **no executable code, no APK, no remote JavaScript**. The declarations are consumed by the Classpp app only when the user manually triggers “Update remote school list”; the app does not fetch them automatically in the background. The repository is **not affiliated with, authorized by, or endorsed by any school, academic system, LMS provider, or Gitee**. School names and trademarks belong to their respective owners.

All content is provided **“AS IS”, without warranty of any kind**. Declarations may be outdated, incorrect, or tampered with; the app’s validation is a risk reduction, not a security guarantee. The repository does not collect your credentials, cookies, todos, or personal data; fetching a declaration is a normal HTTPS request to Gitee, whose platform logs are governed by Gitee’s policies. Contributors must only submit information obtained lawfully from their own accounts, must not include credentials or personal data, and must not attempt to bypass authentication, access controls, or rate limits or disrupt school systems. Repository content is licensed under the **Apache License 2.0**.

To the maximum extent permitted by applicable law, the author is **not liable for any damages or losses** arising from the use of or inability to use this repository or its content, including account restriction, data loss, academic loss, or third-party claims. If you do not agree with this disclaimer, do not use this repository or its declarations. For takedown or infringement concerns, please contact the author through the main project’s public contact channel.

---

Copyright (c) 2026 ChengFY
