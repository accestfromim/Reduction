package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** Public repository metadata for the assistant; Windows may have a different network route than WSL. */
class GitHubTool(private val cards: com.mindtravel.reduction.feature.chat.data.CardRepository, private val writes: GitHubWrites? = null) : ChatTool {
    override val spec = ToolSpec("github", "查询公开 GitHub 仓库的 Issue、PR 或发布记录。kind=sync 刷新本地仓库动态卡。",listOf(
        ToolParam("kind","查询类型",choices=listOf("issues","pulls","releases","sync","create_issue","update_issue","comment","publish_card")),
        ToolParam("repo","owner/repo，默认 mindtravel/Reduction",false),
        ToolParam("number","目标 Issue 编号",false,jsonType="integer"),
        ToolParam("title","Issue 标题",false), ToolParam("body","Issue 或评论正文",false),
        ToolParam("state","Issue 状态",false,choices=listOf("open","closed")),
        ToolParam("card","要发布的卡片全名",false), ToolParam("path","默认分支中的 .html 文件路径",false),
        ToolParam("preview","true 只生成本机预览",false,jsonType="boolean"),
        ToolParam("page","页码，从 1 开始",false,jsonType="integer")
    ))
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val repo = args.optString("repo","mindtravel/Reduction")
        require(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+").matches(repo) && repo.substringAfter('/') !in listOf(".","..")) { "需要 owner/repo" }
        val kind = args.getString("kind")
        if (kind in setOf("create_issue","update_issue","comment","publish_card")) return requireNotNull(writes).execute(repo,kind,args)
        if (kind == "sync") return sync(repo)
        require(kind in setOf("issues","pulls","releases"))
        val page = args.optInt("page",1)
        require(page > 0)
        val url = "https://api.github.com/repos/$repo/$kind?per_page=20&page=$page" + if(kind=="releases") "" else "&state=all"
        val raw = try {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).header("User-Agent","Reduction").build()
            val res = http.send(req,HttpResponse.BodyHandlers.ofString())
            check(res.statusCode()==200) { "GitHub HTTP ${res.statusCode()}" }
            res.body()
        } catch (e: java.io.IOException) {
            val ps = """${'$'}ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new(); (Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 '$url' -ErrorAction Stop).Content"""
            val command = Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
            val p = ProcessBuilder("/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",command).redirectErrorStream(true).start()
            val content = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(p.waitFor()==0) { "GitHub 请求失败，请检查网络、仓库名或 API 限流" }
            content
        }
        val records = JSONArray(raw)
        val items = JSONArray()
        for (i in 0 until records.length()) {
            val obj = records.getJSONObject(i)
            if (kind=="issues" && obj.has("pull_request")) continue
            val item = JSONObject()
            for (key in listOf("number","title","name","tag_name","state","html_url","updated_at","published_at","body"))
                if(obj.has(key)) item.put(key,obj.get(key))
            items.put(item)
        }
        return JSONObject().put("items",items).put("page",page).put("mayHaveNext",records.length()==20).toString()
    }

    private fun sync(repo: String): String {
        val name = if (repo.equals("mindtravel/Reduction", true)) "Reduction/GitHub/仓库动态" else "GitHub/$repo/仓库动态"
        val old = cards.document(name)
        val doc = org.jsoup.nodes.Document.createShell("")
        doc.outputSettings().prettyPrint(false)
        val section = doc.body().appendElement("section").attr("data-github-repo", repo)
        var count = 0
        for ((kind,title) in listOf("issues" to "Issues","pulls" to "Pull requests","releases" to "Releases")) {
            section.appendElement("h2").text(title)
            val list = section.appendElement("ul")
            var page = 1
            do {
                val result = JSONObject(execute(JSONObject().put("repo",repo).put("kind",kind).put("page",page++).toString()))
                val entries = result.getJSONArray("items")
                for (index in 0 until entries.length()) {
                    val record = entries.getJSONObject(index)
                    val li = list.appendElement("li")
                    val prefix = if (record.has("number")) "#"+record.getInt("number")+" " else ""
                    li.appendElement("a").attr("href",record.getString("html_url"))
                        .text(prefix + record.optString("title",record.optString("tag_name")))
                    li.appendText(" " + record.optString("state"))
                    count++
                }
            } while (result.getBoolean("mayHaveNext"))
            if (list.childrenSize()==0) list.replaceWith(org.jsoup.nodes.Element("p").text("暂无记录"))
        }
        val begin = "<!-- reduction-github:begin -->"
        val end = "<!-- reduction-github:end -->"
        fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val content = section.outerHtml()
        val part = begin + "<!-- sha256:" + digest(content) + " -->" + content + end
        val output = when {
            old == null -> "<!doctype html><html><body><h1>仓库动态</h1>$part</body></html>"
            old.indexOf(begin)>=0 && old.indexOf(end)>old.indexOf(begin) ->
                {
                    require(old.indexOf(begin)==old.lastIndexOf(begin) && old.indexOf(end)==old.lastIndexOf(end)) { "自动区标记重复，已保留原卡" }
                    val previous = old.substringAfter(begin).substringBefore(end)
                    val stamp = Regex("^<!-- sha256:([0-9a-f]{64}) -->").find(previous)
                    val intact = if (stamp == null) previous == content else
                        digest(previous.substring(stamp.range.last + 1)) == stamp.groupValues[1]
                    require(intact) { "自动区内有手写修改，已保留原卡；请将备注移到自动区外再刷新" }
                    old.replaceRange(old.indexOf(begin),old.indexOf(end)+end.length,part)
                }
            else -> {
                val at = old.lastIndexOf("</body>",ignoreCase=true)
                require(at >= 0) { "卡片缺少正文结束标签" }
                old.replaceRange(at,at,part)
            }
        }
        if (output != old) check(cards.autosave(name,"",output,old==null,"",old.orEmpty())=="saved") { "卡片正在修改或已锁定，请稍后刷新" }
        return "已核对 $count 条记录：$name"
    }
}
