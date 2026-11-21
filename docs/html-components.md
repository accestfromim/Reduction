# CARD · Reduction 卡片协议
  
## 协议的三个部分

1. **协议内容**定义官方组件、字段和写法。下方的组件表与示例是共享约定。
2. **卡片 CARD 纯文本**是每张卡唯一的正文来源。它包含完整 HTML、原生 SVG 和附件引用。组件目录只是从源码生成的索引。
3. **卡片最终渲染结果**是正式渲染器根据正文和附件生成的画面。作者视图保留私人标注，对外导出应用隐私过滤。

手机和桌面调试共用 `card` 工具。只传卡名即返回完整正文和版本号。传 `do: {"blocks":true}` 获取桌面编辑位置。传 `do: {"render":true,"revision":"读取得到的版本号"}` 查看该正文版本的对外 PNG；revision 可省略以使用当前版本，view 传 author 可检查含私人标注的作者画面，可用 `page` 选择画面。版本号标识正文，不包含附件字节。AI 的写入仍使用下述结构化组件。


CARD 默认黑底白字。公众号模板在 html 根元素声明 `data-card-template="wechat"`，共享渲染器使用白底深色文字、标题下短黑线和居中图注。渲染器移除卡片自带的样式表、主题色与行内布局，统一使用官方模板。颜色和时间轴节点样式由共享渲染器提供。HTML、PDF、PNG 使用此固定主题，Markdown 遵循阅读器主题。

Markdown 仅用于导入和导出。旧正文经共享 CardDocument 转成 CARD HTML，再进入组件目录、渲染器和编辑器。首次实际编辑将 CARD 保存，打开旧卡无需批量改写。内部 AI 通过 card 工具写入结构化组件，文字字段按字面保存。

卡片保存标准 HTML。组件使用原生元素和少量 data 属性。正文和表格在页面原位编辑。卡片源码保存内容、单位和来源，图形在预览文档中生成。

选择组件时看信息承担的作用。原有截图、插画和流程图作为一张图片，原件和图注放在同一个 figure；音视频同理。结构化数值用表格或图表，按时间排列的事件用时间轴，带出处的原话用引用，普通观点用段落。003 的两张矢量图使用原生 SVG 组件，保留形状、坐标及 text 标签。正文、标题与说明分别使用文本组件。来自白纸版式的透明配图可以使用 figure.card-paper。

| 组件 | 源码约定 | 编辑方式 |
| --- | --- | --- |
| 原生矢量图 | figure 内嵌 svg；AI 写入字段为 svg，标签使用 text 或 tspan | 整图按原样展示；通过 CARD 源码更新图中文字 |
| 本地文件链接 | `<a data-path="/home/mindtravel/Reduction/docs/HANDOFF.md">交接文档</a>` | 桌面端点击后在资源管理器中定位文件。也支持 Windows 盘符绝对路径，含空格或中文可原样填写。 |
| 数据表 | table；caption 写报告期和单位，th 写列名或行名，td 写数值 | 直接编辑标题或单元格 |
| 占比图 | table data-chart="share" | 改第二列，重新计算各项占总量的比例 |
| 横向条形图 | table data-chart="bar" | 改第二列，以最大值为标尺重绘 |
| 图片 | img data-attachment="media:附件ID"；用 alt 描述画面 | 在 figcaption 写可编辑图注 |
| 音频 | audio controls preload="metadata" data-attachment="media:附件ID" | 用 figcaption 标注来源、原声或合成 |
| 视频 | video controls playsinline preload="metadata" data-attachment="media:附件ID" | 用 figcaption 标注来源、现场或自制 |

图表只读取 tbody，每行依次为标签与非负数值。单位放在标题中。空值、负数、全零或不符合两列约定时保留原表。普通数据表可容纳负数和多列。附件 ID 由主机保存文件后返回。

```html
<table data-chart="share">
  <caption>收入构成（百万元）</caption>
  <tbody>
    <tr><th scope="row">按量</th><td>80</td></tr>
    <tr><th scope="row">订阅</th><td>20</td></tr>
  </tbody>
</table>
```

## 文件输入与完整附件包

拖入或选择本地文件即可挂载到卡片。挂载时保存原始字节、原文件名、字节数和媒体类型。文件可以独立于正文存在，多次引用同一附件仍指向同一个附件 ID。解析或预览失败时，原件仍可读取和导出。

| 输入 | 可供读取的内容 |
| --- | --- |
| Word 文档（.doc、.docx） | 提取段落、标题和表格，保留顺序。受密码保护或无法解析的文档显示具体状态。 |
| CSV、TSV | 按编码、分隔符和引号规则解析行列，保留空字段、字段内换行、前导零及原始字符串。 |
| Excel（.xls、.xlsx） | 按工作表读取表格并保留表名。 |
| PDF、纯文本、Markdown、HTML | 按类型提供页码、正文或文档结构。 |
| 图片、音频、视频及其他文件 | 已支持的媒体使用对应组件，其余文件提供文件名和原件下载。 |

完整 ZIP 以卡片附件清单为准。包内包含卡片正文、attachments/ 下的全部挂载原件，以及记录附件 ID、原文件名、包内相对路径、大小和 SHA-256 的 manifest.json。未插入正文的附件也必须收录。同名附件分配不同包内路径，原文件名保存在清单中。包内路径禁止越出目标目录。普通本地路径链接不属于挂载附件。

验收时挂入同名 Word 文件、含带引号换行和前导零的 CSV、正文未引用的文件及未知类型文件。导出并解压 ZIP，将清单与卡片附件清单逐项比对，每个原件的字节数和摘要均应相同。任何原件缺失时明确报告该项并判定完整包导出失败。

## 导出约定

电脑端通过 AI 工具 `card` 调用导出。每次导出从同一份当前正文快照生成，包含已输入但尚在等待自动保存的修改。导出过程只读。交付文件排除工具栏、编辑标记、光标及内部附件 ID。

| 格式 | 交付与验收 |
| --- | --- |
| HTML | 输出独立 HTML 文件，包含样式、表格、绘制好的图表和内嵌附件。关闭 Reduction、断网后仍能查看图片、播放音视频。外部来源链接保留原地址。 |
| Markdown | 输出 UTF-8 的 .md，保留标题、列表、引用、链接、图注和数据表。图表导出一次带单位的源表。附件放在相邻的 assets 目录，以相对路径引用；有附件时将正文与附件一起打包成 ZIP。复杂表格可保留内联 HTML。 |
| PDF | 用共享渲染结果生成分页文档。正文可搜索和复制，跨页表格重复表头；图、单位和图注保持关联。音视频使用标题、图注及已有海报，保留可访问的来源链接。 |
| PNG | 默认导出整张卡的连续长图，覆盖滚动区域。等待字体、图片和图表就绪，以明确的版心宽度和像素倍率渲染；超过编码器上限时按纵向顺序分片并打包，像素行无遗漏、无重叠。媒体使用与 PDF 相同的静态表示。 |

本地绝对路径链接只在拥有相应路径的设备上有效。导出保留作者填写的标签，不自动读取或打包链接目标；需要随报告交付的文件应挂为卡片附件。附件缺失时报告具体文件，避免生成看似完整的交付件。

验收使用一张包含中文、长表格、图表、图片、音视频与本地链接的卡片。修改表格数值后立即导出四种格式，核对数值、单位和来源一致；检查 PDF 分页和 PNG 最后一行，并在脱离应用的环境中打开导出件。

## 桌面 AI 入口

`card` 接收卡名和可选的 `do` 对象。省略 do 返回简短目录。每次只表达一个意图，共用普通卡片的锁和自动保存冲突检查。

| 意图 | do 示例 |
| --- | --- |
| 追加标题或段落 | `{"text":"我的判断","style":"h2"}`，普通段落可省略 style |
| 引用原文 | `{"quote":"逐字引文","source":"https://example.org/document"}`，显示可点击的来源域名，Markdown 保留原文和完整链接 |
| 添加时间轴 | `{"timeline":[["周五","提出想法"],["次日","完成原型"]]}`；按输入顺序显示，允许同一时间多个事件 |
| 添加表格或图 | `{"rows":[["订阅",80],["其他",20]],"caption":"收入","view":"share"}`，可选 columns |
| 更新已有内容 | `{"row":"订阅","text":"25"}`；多列表格指定 column，重名行可指定 table |
| 改文字或切图 | `{"target":"原句","text":"新句"}` 或 `{"target":"收入","view":"bar"}` |
| 插入链接 | `{"url":"/home/mindtravel/Reduction/docs/HANDOFF.md","text":"交接文档"}` |
| 挂原件、插入媒体 | `{"attach":"绝对路径"}`，或 `{"media":"绝对路径或已挂文件名","caption":"图注"}` |
| 查看附件、读取、导出 | `{"attachments":true}`、`{"read":"报告.csv"}`、`{"export":"pdf"}` |

同名附件从目录取得可区分的名称。组件标签不够定位时，目录额外返回 ref。新媒体导入后若正文写入报错，宿主清理此次新建的附件；复用的原件保留。导出返回绝对路径，产物位于 `~/.local/share/reduction/exports/`。旧媒体缺少原文件名时沿用既有编号。

## 维护入口

共享渲染入口是 `app/src/main/assets/js/components/html/index.js`，负责清理文档、挂载隔离页面和解析附件。`chart-table.js` 根据表格数据绘图。`features/cards/html-fields.js` 让用户直接点页面文字编辑，只把文本变化写回源文档。编辑表格会重绘对应的图，播放器保留在原位。`features/cards/cards.js` 负责导航、草稿和自动保存。

桌面生产实现位于 `desktop/card/`。blocks.py 将结构化字段写回 HTML，files.py 处理附件和交付文件，render/export.mjs 负责浏览器渲染。渲染器使用本目录自己的 package-lock.json 安装运行依赖。编辑器的图表校验和浏览器绘图共用 chart-table.js 中的 chartData。Kotlin 宿主通过 CardWorker.kt 启动相应工作进程。运行 `python3 -B desktop/card/check.py` 可验证附件与五种导出。

Markdown 的组件转换层位于 `desktop/card/markdown_export.py`。时间轴输出有序列表，图表输出标题和数据表，音视频输出文件链接和图注，附件按清单打包。该路径无需启动浏览器。

## 桌面目录挂载与导入

在 `~/.config/reduction/card-sources.json` 配置“卡片目录名 → WSL 绝对路径”，例如 `{"个人知识库/Knowledge":"/mnt/d/Desktop/VSCODE/Knowledge"}`。磁盘目录须已获读取权限。卡片目录下的「原件」按层列举文件，点文件会在资源管理器定位。

向已有卡导入原件使用 `{"card":"个人知识库/Knowledge/主题卡","do":{"import":"PersonalKB/Economy/07_prediction_journal.md"}}`。默认取目标卡最近的绑定目录；跨目录时在 do 内加 `"source":"个人知识库/Knowledge"`。导入保存文件副本，原文件保留。生成 DS 主题摘要是独立操作。

## 私有标注

作者在原卡里能看到标注，标注文字带灰底和虚线。分享、复制正文及导出时移除标注，其他文字原样保留。HTML 和 Markdown 共用一种行内写法：

```html
<p>今天完成了首轮测试。<span data-card-privacy="note">下次记得换耳机。</span></p>
```

给 Reduction 的 subagent 只需传 `{"card":"卡片全名","do":{"note":"下次记得换耳机。"}}`。选择原卡里唯一的一段单行文字，保留原文和标点。使用 `{"share":true}` 生成只读 HTML 分享页。独立运行 `python3 desktop/card/privacy.py 日报.md 分享稿.md 作者复核.json` 也使用同一规则，清单只交给作者。发布由作者手动触发。

导入 Markdown 文件正文使用 `{"card":"目录/新卡","do":{"importMarkdown":"报告.md"}}`。宿主检查挂载目录权限并拒绝覆盖同名卡。

### 行内标注

`<card-date>2026年11月21日</card-date>` 保存日期原文。`<card-private>仅作者可见的一句话</card-private>` 标记单行纯文字，公开出口移除整个标注。旧版 data-card-privacy 标记继续按同一规则过滤。

两端共用 `CardAnnotation.kt` 将 `{"date":"原文"}` 或 `{"note":"原文"}` 写入唯一的正文文字片段。阅读与调试共用 `js/components/html/index.js` 的排版规则。Android 通过 CardPageRenderer 截取指定页，CardExport 负责交付文件。卡片右上角「导出」提供 ZIP、PNG 和 PDF。

公众号标题后用一个 `{text,style:"routine"}` 放置本期 API 开销、开源说明和仓库链接，正文从框后开始。

### 卡片的分享范围

本机卡片库的 `.private-roots.json` 是作者指定的私密路径数组。每项匹配卡片全名和其下所有子路径。私人范围优先于行内标注，公开预览、导出与整卡复制都会检查。可分享卡片沿用行内私人标注。该本机配置独立于卡片正文，跨设备需分别配置。

分享配置以一级目录继承：私密根目录写入 `.private-roots.json`；`.sharing-policy.json` 中 `annotatedRoots` 与 `publicRoots` 是一级目录名数组，`publicCards` 是用户明确放开的完整卡名，仅匹配卡片本身。所有公开输出仍过滤已标注内容。
