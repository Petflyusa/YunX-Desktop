package com.yunx.app.util

import com.sun.jna.platform.win32.Crypt32Util
import com.yunx.app.AppContext
import java.io.File
import java.sql.DriverManager

/**
 * 一键从本机浏览器导入网盘登录 Cookie（方案 A）。
 *
 * - Chromium 系（Chrome / Edge / 360 / QQ / 搜狗 / Opera / Brave 等）：
 *   Network\Cookies（SQLite）中的 encrypted_value 为 "v10" 前缀 + Windows DPAPI 加密载荷，
 *   用 CryptUnprotectData 解密（同一 Windows 用户下可解）。
 *   "v20" 前缀为「应用绑定加密」（Chrome 127+ / 较新 Edge），本应用无法解密 → 跳过该 Cookie。
 * - Firefox：cookies.sqlite 中 value 为明文，直接读取。
 */
object BrowserCookieImporter {

    private const val TAG = "YunX-Cookie"

    enum class Kind { CHROMIUM, FIREFOX }

    data class BrowserInfo(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val profileDirs: List<File>,
        /** macOS：该浏览器在钥匙串里的「Safe Storage」服务名（解密 Cookie 用）；Windows 为 null */
        val macSafeStorage: String? = null
    )

    data class FoundCookies(
        val browserName: String,
        val cookie: String,
        val count: Int
    )

    /** 平台 → 需要匹配的 Cookie 域名后缀 */
    fun domainSuffixes(platform: String): List<String> = when (platform) {
        "QUARK" -> listOf(".quark.cn")
        "UC" -> listOf(".uc.cn")
        "BAIDU" -> listOf(".baidu.com")
        "C139" -> listOf("mail.10086.cn", "yun.139.com", ".10086.cn")
        "PAN115" -> listOf(".115.com", ".115cdn.com")
        else -> emptyList()
    }

    /** 发现本机已安装浏览器的 Cookie 库目录（存在 cookies 文件才算） */
    fun discoverBrowsers(): List<BrowserInfo> {
        if (OsPlatform.isMac) return discoverMacBrowsers()
        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: return emptyList()
        val roaming = System.getenv("APPDATA")?.let { File(it) } ?: return emptyList()
        val out = mutableListOf<BrowserInfo>()

        // Chromium 系：<root>/Network/Cookies
        val chromiumCandidates = listOf(
            "Chrome" to listOf(File(local, "Google\\Chrome\\User Data")),
            "Edge" to listOf(File(local, "Microsoft\\Edge\\User Data")),
            "360极速浏览器" to listOf(File(local, "360Chrome\\Chrome\\User Data")),
            "360安全浏览器" to listOf(
                File(roaming, "360se6\\User Data"),
                File(roaming, "360se\\User Data"),
                File(local, "360se6\\User Data")
            ),
            "QQ浏览器" to listOf(File(local, "Tencent\\QQBrowser\\User Data")),
            "搜狗浏览器" to listOf(
                File(roaming, "SogouExplorer\\User Data"),
                File(local, "SogouExplorer\\User Data")
            ),
            "Opera" to listOf(File(roaming, "Opera Software\\Opera Stable")),
            "Brave" to listOf(File(local, "BraveSoftware\\Brave-Browser\\User Data")),
            "Vivaldi" to listOf(File(local, "Vivaldi\\User Data"))
        )
        for ((name, roots) in chromiumCandidates) {
            val existing = roots.filter { root ->
                root.isDirectory && File(root, "Network\\Cookies").exists()
            }
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing)
            }
        }

        // Firefox：<Profiles>/xxx/cookies.sqlite
        val ffRoot = File(roaming, "Mozilla\\Firefox\\Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
            }
        }
        return out
    }

    /** 从指定浏览器导入指定平台的 Cookie；未找到返回 null */
    fun importFrom(browser: BrowserInfo, domains: List<String>): FoundCookies? =
        when (browser.kind) {
            Kind.CHROMIUM -> importChromium(browser, domains)
            Kind.FIREFOX -> importFirefox(browser, domains)
        }

    // ---------- Chromium ----------

    private fun importChromium(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        // macOS：先从钥匙串取该浏览器的 Safe Storage 口令派生 AES 密钥（系统会弹一次授权框）
        val macKey = if (OsPlatform.isMac) {
            macChromiumKey(browser.macSafeStorage) ?: run {
                Log.w(TAG, "读取 ${browser.displayName} 的钥匙串口令失败（可能被拒绝授权）")
                return null
            }
        } else null
        for (root in browser.profileDirs) {
            val cookiesFile = chromiumCookiesFile(root) ?: continue
            val copy = File(AppContext.cacheDir, "browser-cookies-${System.nanoTime()}.db")
            try {
                cookiesFile.copyTo(copy, overwrite = true)
                val cookie = (if (macKey != null) extractChromiumMac(copy, domains, macKey)
                    else extractChromium(copy, domains)) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 ${browser.displayName} Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractChromium(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, encrypted_value FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = decryptChromiumValue(rs.getBytes(3)) ?: continue
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }

    /**
     * Chromium encrypted_value：
     * - "v10" 前缀 + DPAPI 载荷 → CryptUnprotectData 解密；
     * - "v20" 前缀（应用绑定加密，Chrome 127+）→ 返回 null（无法解密，跳过后整体提示）；
     * - 无前缀（个别旧版）→ 直接按 DPAPI 尝试。
     */
    private fun decryptChromiumValue(bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        var offset = 0
        if (bytes[0] == 'v'.code.toByte() &&
            bytes[1] in '0'.code.toByte()..'9'.code.toByte() &&
            bytes[2] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            // v10 / v20 等版本前缀
            val version = (bytes[1] - '0'.code.toByte()) * 10 + (bytes[2] - '0'.code.toByte())
            if (version >= 20) return null // 应用绑定加密，无法解密
            offset = 3
        }
        val payload = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
        return runCatching {
            val plain = Crypt32Util.cryptUnprotectData(payload)
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    /** Cookie 库位置：新版在 `<profile>/Network/Cookies`，macOS 与旧版在 `<profile>/Cookies` */
    private fun chromiumCookiesFile(root: File): File? =
        listOf(File(File(root, "Network"), "Cookies"), File(root, "Cookies")).firstOrNull { it.isFile }

    // ---------- macOS ----------

    /** macOS 上各浏览器的配置根目录与钥匙串服务名 */
    private fun discoverMacBrowsers(): List<BrowserInfo> {
        val support = File(System.getProperty("user.home"), "Library/Application Support")
        val out = mutableListOf<BrowserInfo>()
        val chromium = listOf(
            Triple("Chrome", "Google/Chrome", "Chrome Safe Storage"),
            Triple("Edge", "Microsoft Edge", "Microsoft Edge Safe Storage"),
            Triple("Brave", "BraveSoftware/Brave-Browser", "Brave Safe Storage"),
            Triple("Vivaldi", "Vivaldi", "Vivaldi Safe Storage"),
            Triple("Opera", "com.operasoftware.Opera", "Opera Safe Storage"),
            Triple("Arc", "Arc/User Data", "Arc Safe Storage"),
            Triple("Chromium", "Chromium", "Chromium Safe Storage")
        )
        for ((name, rel, service) in chromium) {
            val root = File(support, rel)
            if (!root.isDirectory) continue
            // 配置目录：Default / Profile 1 / Profile 2 …（Opera 直接把库放在根目录）
            val profiles = (listOf(root) + (root.listFiles { f ->
                f.isDirectory && (f.name == "Default" || f.name.startsWith("Profile "))
            }?.sortedBy { it.name } ?: emptyList())).filter { chromiumCookiesFile(it) != null }
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, profiles, macSafeStorage = service)
            }
        }
        val ffRoot = File(support, "Firefox/Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
        }
        return out
    }

    /**
     * macOS Chromium 的 Cookie 密钥：钥匙串「<浏览器> Safe Storage」里的口令
     * → PBKDF2-HMAC-SHA1(salt="saltysalt", 1003 次, 128 位)。
     */
    private fun macChromiumKey(service: String?): ByteArray? {
        val svc = service ?: return null
        val password = OsPlatform.runForOutput(
            "/usr/bin/security", "find-generic-password", "-w", "-s", svc, timeoutSec = 60
        ) ?: return null
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), "saltysalt".toByteArray(), 1003, 128)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun extractChromiumMac(dbFile: File, domains: List<String>, key: ByteArray): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            // 库版本 ≥ 24（Chrome 130+）时明文前面多了 32 字节的域名 SHA-256，需要去掉
            val dbVersion = runCatching {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT value FROM meta WHERE key='version'").use { rs ->
                        if (rs.next()) rs.getString(1).toIntOrNull() ?: 0 else 0
                    }
                }
            }.getOrDefault(0)
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, value, encrypted_value FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val plainValue = rs.getString(3).orEmpty()
                        val value = if (plainValue.isNotEmpty()) plainValue
                            else decryptMacValue(rs.getBytes(4), key, dbVersion >= 24) ?: continue
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }

    /** "v10" + AES-128-CBC(IV = 16 个空格, PKCS7) */
    private fun decryptMacValue(bytes: ByteArray?, key: ByteArray, stripDomainHash: Boolean): String? {
        if (bytes == null || bytes.size <= 3) return null
        if (bytes[0] != 'v'.code.toByte()) return null
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(ByteArray(16) { ' '.code.toByte() })
            )
            var plain = cipher.doFinal(bytes, 3, bytes.size - 3)
            if (stripDomainHash && plain.size >= 32) plain = plain.copyOfRange(32, plain.size)
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    // ---------- Firefox ----------

    private fun importFirefox(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (profile in browser.profileDirs) {
            val dbFile = File(profile, "cookies.sqlite")
            if (!dbFile.exists()) continue
            val copy = File(AppContext.cacheDir, "ff-cookies-${System.nanoTime()}.db")
            try {
                dbFile.copyTo(copy, overwrite = true)
                val cookie = extractFirefox(copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 Firefox Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractFirefox(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host LIKE ?" }
            conn.prepareStatement(
                "SELECT host, name, value FROM moz_cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = rs.getString(3)
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }
}
