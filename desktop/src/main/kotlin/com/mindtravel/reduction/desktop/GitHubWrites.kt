package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.feature.chat.data.CardRepository
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitHubWrites(
    private val cards: CardRepository,
    private val publicHtml: (String) -> File,
    private val approvals: ToolApprovalHost
) {
    private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+","%20")

    private fun api(method: String, endpoint: String, body: JSONObject? = null, missingOK: Boolean = false): JSONObject? {
        val command = mutableListOf("gh","api","--hostname","github.com","--method",method,endpoint)
        if (body != null) command.addAll(listOf("--input","-"))
        val error = File.createTempFile("reduction-gh-", ".err")
        try {
            val p = ProcessBuilder(command).redirectError(error).apply {
                environment()["GH_PROMPT_DISABLED"]="1"
                environment()["GH_PAGER"]="cat"
            }.start()
            p.outputStream.bufferedWriter(Charsets.UTF_8).use { if (body != null) it.write(body.toString()) }
            val result = p.inputStream.bufferedReader().use { it.readText() }
            if (p.waitFor()!=0) {
                val message = error.readText()
                if (missingOK && "HTTP 404" in message) return null
                val reason = when {
                    "gh auth login" in message || "HTTP 401" in message -> "请在 WSL 运行 gh auth login 登录 github.com"
                    "HTTP 403" in message -> "账号缺少写入权限或 GitHub 已限流"
                    "HTTP 409" in message || "HTTP 422" in message -> "目标已变化或参数不被接受，请重新核对"
                    else -> "请检查 GitHub 网络和账号权限；重试前先核对远端状态"
                }
                error(reason)
            }
            return JSONObject(result)
        } finally { error.delete() }
    }

    fun execute(repo: String, kind: String, args: JSONObject): String {
        val payload = JSONObject()
        val root = "/repos/$repo"
        var endpoint: String
        var method: String
        val review: String
        var card: String? = null
        when(kind) {
            "publish_card" -> {
                val name = args.getString("card")
                val path = args.getString("path")
                require(path.endsWith(".html",true) && path.split('/').all { it.isNotBlank() && !it.startsWith('.') && it.none(Char::isISOControl) && '\\' !in it }) { "请指定仓库内的 .html 文件路径" }
                cards.requireShareable(name)
                card = name
                val artifact = publicHtml(name)
                val bytes = artifact.readBytes()
                payload.put("message","更新公开卡片 $path")
                    .put("content",Base64.getEncoder().encodeToString(bytes))
                endpoint = "$root/contents/" + path.split('/').joinToString("/") { segment(it) }
                method = "PUT"
                review = "将卡片「$name」的公开 HTML 写入 $repo/$path（默认分支，${bytes.size} 字节）。\n本机可核对文件：${artifact.absolutePath}"
            }
            "create_issue","update_issue","comment" -> {
                endpoint = "$root/issues"
                if (kind != "create_issue") {
                    val number=args.getInt("number")
                    require(number>0) { "需要有效的 Issue 编号" }
                    endpoint += "/$number"
                }
                if (kind == "comment") endpoint += "/comments"
                method = if(kind=="update_issue") "PATCH" else "POST"
                val keys = if(kind=="comment") listOf("body") else listOf("title","body","state")
                for(key in keys) if(args.has(key)) payload.put(key,args.getString(key))
                if(kind=="create_issue") require(payload.optString("title").isNotBlank() && !payload.has("state")) { "创建 Issue 需要标题，可附正文" }
                if(kind=="comment") require(payload.optString("body").isNotBlank()) { "请提供评论正文" }
                require(payload.length()>0 && (!payload.has("state") || payload.getString("state") in listOf("open","closed"))) { "请指定有效的修改字段" }
                review = "$method $endpoint\n" + keys.filter(payload::has).joinToString("\n") { "$it：${payload.getString(it)}" }
            }
            else -> error("未知写入操作")
        }
        if (args.optBoolean("preview")) return review
        api("GET","/user")
        if (kind=="publish_card") {
            val existing = api("GET",endpoint,missingOK=true)
            if (existing!=null) {
                require(existing.optString("type")=="file") { "目标必须是普通文件" }
                payload.put("sha",existing.getString("sha"))
            }
        }
        val decision = CompletableFuture<Boolean>()
        approvals.requestToolApproval("确认写入 GitHub",review) { decision.complete(it) }
        if (!decision.get(90, TimeUnit.SECONDS)) return "用户取消，未提交"
        card?.let(cards::requireShareable)
        val result = requireNotNull(api(method,endpoint,payload))
        return result.optJSONObject("content")?.getString("html_url") ?: result.getString("html_url")
    }
}
