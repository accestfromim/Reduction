package com.mindtravel.reduction.shared.ssh

data class SshJumpHost(val host: String, val port: Int, val user: String) {
    companion object {
        private val address = Regex("""(?:([^\s@,:/\[\]]+)@)?(?:\[([\w.%:-]+:[\w.%:-]*)\]|([^\s@,:/\[\]]+))(?::([0-9]+))?""")
        private const val FORMAT = "跳板机格式应为 [用户@]主机[:端口]，IPv6 地址请用方括号"

        @Throws(IllegalArgumentException::class)
        fun parse(spec: String, defaultUser: String): SshJumpHost? {
            val text = spec.trim()
            if (text.isEmpty()) return null
            val match = address.matchEntire(text) ?: throw IllegalArgumentException(FORMAT)
            val (_, user, ipv6, host, port) = match.groupValues
            val number = if (port.isEmpty()) 22 else port.toIntOrNull()
            require(number != null && number in 1..65535) { "跳板机端口必须在 1 到 65535 之间" }
            return SshJumpHost(ipv6.ifEmpty { host }, number, user.ifEmpty { defaultUser })
        }
    }
}
