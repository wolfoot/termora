package app.termora.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.text.*
import android.text.method.PasswordTransformationMethod
import android.view.*
import android.widget.*
import android.window.OnBackInvokedDispatcher
import app.termora.*
import app.termora.android.core.*
import app.termora.macro.Macro
import app.termora.snippet.Snippet
import app.termora.tag.Tag
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.io.*
import java.lang.ref.WeakReference
import java.util.Base64
import java.util.concurrent.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var folder = "0"
    private var active: MobileSession? = null
    private var terminalView: TerminalView? = null
    private var desktopView: DesktopView? = null
    private var uriCallback: ((Uri) -> Unit)? = null
    private var fileBrowser: FileBrowser? = null
    private val vault
        get() = Repository.value

    private val density
        get() = resources.displayMetrics.density

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(23, 28, 39)
        window.navigationBarColor = Color.rgb(23, 28, 39)
        Repository.init(this)
        UiBridge.activity = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                navigateBack()
            }
        TerminalPlatform.assertThread = { check(Looper.myLooper() == Looper.getMainLooper()) }
        TerminalPlatform.clipboard = { text -> UiBridge.main.post { remoteClipboard(text) } }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        if (ConnectionService.sessions.isNotEmpty()) {
            active =
                ConnectionService.sessions.values.filterIsInstance<MobileSession>().firstOrNull()
            active?.let { showSession(it) } ?: home()
        } else home()
    }

    override fun onResume() {
        super.onResume()
        UiBridge.activity = WeakReference(this)
    }

    override fun onDestroy() {
        active?.observer = null
        fileBrowser?.close()
        if (UiBridge.activity.get() === this) UiBridge.activity.clear()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun column() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun label(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(225, 232, 243))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener {
                try {
                    action()
                } catch (e: Exception) {
                    error(e)
                }
            }
        }

    private fun page(title: String, back: (() -> Unit)? = null): LinearLayout {
        active?.observer = null
        terminalView = null
        desktopView = null
        root = column()
        root.setBackgroundColor(Color.rgb(20, 24, 34))
        setContentView(root)
        root.setPadding(0, 0, 0, 0)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            insets
        }
        root.requestApplyInsets()
        val toolbar = row()
        if (back != null) toolbar.addView(button("‹", back))
        toolbar.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("⋮", ::menu))
        root.addView(toolbar)
        return root
    }

    private fun scroll(parent: LinearLayout): LinearLayout {
        val content = column()
        parent.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        return content
    }

    private fun menu() {
        choices(
            "Termora",
            listOf("主机", "连接会话", "分组和标签", "快捷命令", "密钥管理", "宏", "云同步", "终端设置", "已信任的服务器"),
        ) {
            when (it) {
                0 -> home()
                1 -> sessionList()
                2 -> groups()
                3 -> snippets()
                4 -> keys()
                5 -> macros()
                6 -> sync()
                7 -> settings()
                8 -> pins()
            }
        }
    }

    private fun choices(title: String, items: List<String>, action: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, n -> runCatching { action(n) }.onFailure(::error) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun error(e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("操作失败")
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun info(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定", null).show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> runCatching(action).onFailure(::error) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun answerDialog(
        title: String,
        message: String,
        secret: Boolean,
        confirm: Boolean,
        answer: (String?) -> Unit,
    ) {
        val edit =
            EditText(this).apply {
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .apply { if (!confirm) setView(edit) }
                .setPositiveButton(if (confirm) "信任" else "确定") { _, _ ->
                    answer(if (confirm) "yes" else edit.text.toString())
                }
                .setNegativeButton("取消") { _, _ -> answer(null) }
                .create()
        dialog.setOnCancelListener { answer(null) }
        dialog.show()
    }

    private fun textDialog(
        title: String,
        initial: String = "",
        multiline: Boolean = false,
        secret: Boolean = false,
        save: (String) -> Unit,
    ) {
        val edit =
            EditText(this).apply {
                setText(initial)
                setSingleLine(!multiline)
                if (multiline) {
                    minLines = 4
                    gravity = Gravity.TOP
                }
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                save(edit.text.toString())
                dialog.dismiss()
            } catch (e: Exception) {
                error(e)
            }
        }
    }

    private fun <T> background(message: String, work: () -> T, done: (T) -> Unit) {
        val progress =
            ProgressDialog(this).apply {
                setMessage(message)
                setCancelable(false)
                show()
            }
        UiBridge.io.execute {
            val result = runCatching(work)
            runOnUiThread {
                progress.dismiss()
                if (!isDestroyed) result.fold(done, ::error)
            }
        }
    }

    private class Form(private val activity: MainActivity) {
        val content = activity.column()

        fun field(
            title: String,
            value: String = "",
            secret: Boolean = false,
            multiline: Boolean = false,
        ): EditText {
            content.addView(activity.label(title))
            return EditText(activity).apply {
                setText(value)
                setSingleLine(!multiline)
                if (multiline) {
                    minLines = 3
                    gravity = Gravity.TOP
                }
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
                content.addView(this)
            }
        }

        fun select(title: String, items: List<String>, selected: String): Spinner {
            content.addView(activity.label(title))
            return Spinner(activity).apply {
                adapter =
                    ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
                setSelection(items.indexOf(selected).coerceAtLeast(0))
                content.addView(this)
            }
        }

        fun action(text: String, action: () -> Unit) {
            content.addView(activity.button(text, action))
        }

        fun show(title: String, save: () -> Unit) {
            val dialog =
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(ScrollView(activity).apply { addView(content) })
                    .setPositiveButton("保存", null)
                    .setNegativeButton("取消", null)
                    .create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    save()
                    dialog.dismiss()
                } catch (e: Exception) {
                    activity.error(e)
                }
            }
        }
    }

    private fun home() {
        active = null
        fileBrowser?.close()
        fileBrowser = null
        val parent = vault.hosts.firstOrNull { it.id == folder }
        val page =
            page(
                parent?.name ?: "Termora",
                if (folder != "0") {
                    {
                        folder = parent?.parentId ?: "0"
                        home()
                    }
                } else null,
            )
        val actions = row()
        actions.addView(button("＋ 主机") { editHost(null) })
        actions.addView(button("＋ 分组") { newGroup() })
        actions.addView(button("会话", ::sessionList))
        page.addView(actions)
        val search =
            EditText(this).apply {
                hint = "搜索主机、分组或标签"
                setSingleLine()
            }
        page.addView(search)
        val list = scroll(page)
        fun refresh() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            val hosts =
                vault.hosts
                    .filterNot { it.deleted }
                    .filter { h ->
                        if (query.isBlank()) h.parentId == folder
                        else
                            h.name.contains(query, true) ||
                                h.host.contains(query, true) ||
                                h.options.tags.any { id ->
                                    vault.tags.any { it.id == id && it.text.contains(query, true) }
                                }
                    }
                    .sortedWith(
                        compareByDescending<Host> { it.isFolder }
                            .thenBy { it.sort }
                            .thenBy { it.name },
                    )
            hosts.forEach { h ->
                val tags =
                    vault.tags
                        .filter { it.id in h.options.tags }
                        .joinToString(" ") { "#${it.text}" }
                val b =
                    button(
                        (if (h.isFolder) "▸ " else "${h.protocol}  ") +
                            h.name +
                            (if (h.isFolder) "" else "\n${h.username}@${h.host}  $tags"),
                    ) {
                        if (h.isFolder) {
                            folder = h.id
                            home()
                        } else connect(h)
                    }
                b.gravity = Gravity.START
                b.setOnLongClickListener {
                    hostActions(h)
                    true
                }
                list.addView(b)
            }
            if (hosts.isEmpty()) list.addView(label("添加主机后即可连接。长按主机可编辑配置。"))
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refresh()
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )
        refresh()
    }

    private fun newGroup() {
        textDialog("新建分组") { name ->
            require(name.isNotBlank())
            Repository.update {
                it.copy(
                    hosts =
                        it.hosts +
                            Host(
                                name = name,
                                protocol = "Folder",
                                parentId = folder,
                                createDate = System.currentTimeMillis(),
                                updateDate = System.currentTimeMillis(),
                            ),
                )
            }
            home()
        }
    }

    private fun hostActions(h: Host) {
        choices(h.name, listOf("连接", "编辑", "复制", "删除")) {
            when (it) {
                0 ->
                    if (h.isFolder) {
                        folder = h.id
                        home()
                    } else connect(h)
                1 -> editHost(h)
                2 -> editHost(h.copy(id = randomUUID(), name = h.name + " 副本"))
                3 -> deleteHost(h)
            }
        }
    }

    private fun deleteHost(h: Host) {
        confirm("删除 ${h.name}", "分组内的所有主机也将删除。") {
            val ids = mutableSetOf(h.id)
            var size = 0
            while (size != ids.size) {
                size = ids.size
                vault.hosts.filter { it.parentId in ids }.forEach { ids += it.id }
            }
            Repository.update {
                it.copy(
                    hosts = it.hosts.filterNot { h -> h.id in ids },
                    deleted =
                        it.deleted +
                            ids.map { id -> DeletedData(id, "Host", System.currentTimeMillis()) },
                )
            }
            home()
        }
    }

    private fun editHost(original: Host?) {
        val old = original ?: Host(name = "", protocol = "SSH", port = 22, parentId = folder)
        val form = Form(this)
        val name = form.field("名称", old.name)
        val protocol =
            form.select(
                "协议",
                listOf(
                    "SSH",
                    "SFTP",
                    "VNC",
                    "RDP",
                    "S3",
                    "OSS",
                    "COS",
                    "OBS",
                    "WebDAV",
                    "FTP",
                    "SMB",
                    "Folder",
                ),
                old.protocol,
            )
        val address = form.field("服务器地址 / HTTPS 端点", old.host)
        val port = form.field("端口（0 使用协议默认值）", old.port.toString())
        val defaults =
            mapOf(
                "SSH" to 22,
                "SFTP" to 22,
                "RDP" to 3389,
                "VNC" to 5900,
                "FTP" to 21,
                "SMB" to 445,
            )
        var previousProtocol = old.protocol
        protocol.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = protocol.selectedItem.toString()
                    if (
                        selected != previousProtocol &&
                            port.text.toString().toIntOrNull() == defaults[previousProtocol]
                    )
                        port.setText((defaults[selected] ?: 0).toString())
                    previousProtocol = selected
                }
            }
        val username = form.field("用户名 / Access Key ID", old.username)
        val auth =
            form.select(
                "认证",
                listOf("Password", "PublicKey", "KeyboardInteractive"),
                old.authentication.type.name,
            )
        val secret =
            form.field(
                "密码 / Secret Key",
                if (old.authentication.type == AuthenticationType.PublicKey) ""
                else old.authentication.password,
                true,
            )
        val keyChoices = vault.keys
        val selectedKey =
            form.select(
                "SSH 私钥",
                listOf("未选择") + keyChoices.map { it.name },
                keyChoices.firstOrNull { it.id == old.authentication.password }?.name ?: "未选择",
            )
        val folders = vault.hosts.filter { it.isFolder && it.id != old.id }
        val parent =
            form.select(
                "分组",
                listOf("根目录") + folders.map { it.name },
                folders.firstOrNull { it.id == old.parentId }?.name ?: "根目录",
            )
        val tagText =
            form.field(
                "标签（逗号分隔）",
                vault.tags.filter { it.id in old.options.tags }.joinToString(",") { it.text },
            )
        val remark = form.field("备注", old.remark, multiline = true)
        var options = old.options
        var tunnels = old.tunnelings
        form.action("SSH 登录脚本、跳板机和启动命令") { editOptions(options) { options = it } }
        form.action("端口转发 / SOCKS") { editTunnels(tunnels) { tunnels = it } }
        val proxyType = form.select("SSH 连接代理", listOf("No", "HTTP", "SOCKS5"), old.proxy.type.name)
        val proxyHost = form.field("代理地址", old.proxy.host)
        val proxyPort = form.field("代理端口", old.proxy.port.toString())
        val proxyUser = form.field("代理用户名", old.proxy.username)
        val proxyPass = form.field("代理密码", old.proxy.password, true)
        val bucket =
            form.field(
                "对象存储桶 / SMB 共享名",
                old.options.extras["bucket"] ?: old.options.extras["smb.share"].orEmpty(),
            )
        val region =
            form.field(
                "S3 / OSS 区域",
                old.options.extras["region"] ?: old.options.extras["s3.region"] ?: "us-east-1",
            )
        val pathStyle =
            form.select(
                "S3 地址模式",
                listOf("虚拟主机", "路径"),
                if (old.options.extras["pathStyle"] == "true") "路径" else "虚拟主机",
            )
        val domain =
            form.field(
                "RDP / SMB 域",
                old.options.extras["rdp.domain"] ?: old.options.extras["smb.domain"].orEmpty(),
            )
        val resolution =
            form.select(
                "RDP 分辨率",
                listOf("1280x720", "1920x1080", "1024x768"),
                "${old.options.extras["rdp.width"] ?: "1280"}x${old.options.extras["rdp.height"] ?: "720"}",
            )
        form.show("主机配置") {
            val p = protocol.selectedItem.toString()
            require(name.text.isNotBlank())
            if (p != "Folder") require(address.text.isNotBlank())
            val pn = port.text.toString().toInt()
            require(pn in 0..65535)
            val at = AuthenticationType.valueOf(auth.selectedItem.toString())
            val password =
                if (at == AuthenticationType.PublicKey)
                    keyChoices.getOrNull(selectedKey.selectedItemPosition - 1)?.id
                        ?: error("选择 SSH 私钥")
                else secret.text.toString()
            if (p in listOf("S3", "OSS", "COS", "OBS", "WebDAV"))
                require(address.text.toString().startsWith("https://")) { "云文件服务使用 HTTPS 端点" }
            val group = folders.getOrNull(parent.selectedItemPosition - 1)?.id ?: "0"
            var ancestor = group
            val seen = mutableSetOf(old.id)
            while (ancestor != "0" && ancestor.isNotBlank()) {
                require(seen.add(ancestor)) { "分组不能形成循环" }
                ancestor = vault.hosts.firstOrNull { it.id == ancestor }?.parentId ?: "0"
            }
            val tags =
                tagText.text
                    .toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            val newTags = tags.map { text ->
                vault.tags.firstOrNull { it.text == text }
                    ?: Tag(
                        randomUUID(),
                        text,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                    )
            }
            val dims = resolution.selectedItem.toString().split('x')
            val extras =
                options.extras +
                    mapOf(
                        "endpoint" to address.text.toString(),
                        "bucket" to bucket.text.toString(),
                        "region" to region.text.toString(),
                        "s3.region" to region.text.toString(),
                        "pathStyle" to (pathStyle.selectedItemPosition == 1).toString(),
                        "smb.share" to bucket.text.toString(),
                        "smb.domain" to domain.text.toString(),
                        "rdp.domain" to domain.text.toString(),
                        "rdp.width" to dims[0],
                        "rdp.height" to dims[1],
                    )
            val h =
                old.copy(
                    name = name.text.toString(),
                    protocol = p,
                    host = address.text.toString(),
                    port = pn,
                    username = username.text.toString(),
                    remark = remark.text.toString(),
                    authentication = Authentication(at, password),
                    parentId = group,
                    options = options.copy(tags = newTags.map { it.id }, extras = extras),
                    tunnelings = tunnels,
                    proxy =
                        Proxy(
                            ProxyType.valueOf(proxyType.selectedItem.toString()),
                            proxyHost.text.toString(),
                            proxyPort.text.toString().toInt(),
                            username = proxyUser.text.toString(),
                            password = proxyPass.text.toString(),
                        ),
                    createDate = old.createDate.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    updateDate = System.currentTimeMillis(),
                )
            Repository.update {
                it.copy(
                    hosts = it.hosts.filterNot { h0 -> h0.id == h.id } + h,
                    tags = (it.tags + newTags).distinctBy { it.id },
                )
            }
            home()
        }
    }

    private fun editOptions(old: Options, save: (Options) -> Unit) {
        val f = Form(this)
        var jumps = old.jumpHosts
        var scripts = old.loginScripts
        val startup = f.field("连接后执行的命令", old.startupCommand, multiline = true)
        val encoding = f.select("字符编码", listOf("UTF-8", "GB18030", "ISO-8859-1"), old.encoding)
        val env = f.field("环境变量（每行 名称=值）", old.env, multiline = true)
        val dir = f.field("SFTP 默认目录", old.sftpDefaultDirectory)
        val heartbeat = f.field("心跳秒数", old.heartbeatInterval.toString())
        f.action("设置跳板机（按连接顺序）") { editJumps(jumps) { jumps = it } }
        f.action("编辑登录脚本") { loginScripts(scripts) { scripts = it } }
        f.show("SSH 选项") {
            save(
                old.copy(
                    startupCommand = startup.text.toString(),
                    encoding = encoding.selectedItem.toString(),
                    env = env.text.toString(),
                    sftpDefaultDirectory = dir.text.toString(),
                    heartbeatInterval = heartbeat.text.toString().toInt().coerceAtLeast(5),
                    jumpHosts = jumps,
                    loginScripts = scripts,
                ),
            )
        }
    }

    private fun editJumps(initial: List<String>, save: (List<String>) -> Unit) {
        val jumps = initial.toMutableList()
        val f = Form(this)
        val list = column()
        f.content.addView(list)
        fun refresh() {
            list.removeAllViews()
            jumps.forEachIndexed { n, id ->
                val name = vault.hosts.firstOrNull { it.id == id }?.name ?: id
                list.addView(
                    button("${n+1}. $name") {
                        choices(name, listOf("上移", "下移", "移除")) { action ->
                            when (action) {
                                0 -> if (n > 0) java.util.Collections.swap(jumps, n, n - 1)
                                1 ->
                                    if (n < jumps.lastIndex)
                                        java.util.Collections.swap(jumps, n, n + 1)
                                2 -> jumps.removeAt(n)
                            }
                            refresh()
                        }
                    },
                )
            }
        }
        f.action("添加跳板机") {
            val hosts = vault.hosts.filter { it.protocol.equals("SSH", true) && it.id !in jumps }
            choices("添加跳板机", hosts.map { it.name }) {
                jumps += hosts[it].id
                refresh()
            }
        }
        refresh()
        f.show("跳板机连接顺序") { save(jumps.toList()) }
    }

    private fun loginScripts(initial: List<LoginScript>, save: (List<LoginScript>) -> Unit) {
        val scripts = initial.toMutableList()
        val f = Form(this)
        val list = column()
        f.content.addView(list)
        fun refresh() {
            list.removeAllViews()
            scripts.forEachIndexed { n, s ->
                list.addView(
                    button("${n+1}. ${s.expect} → ${s.send}") {
                        choices("登录脚本", listOf("编辑", "删除")) { action ->
                            if (action == 1) {
                                scripts.removeAt(n)
                                refresh()
                            } else
                                editScript(s) {
                                    scripts[n] = it
                                    refresh()
                                }
                        }
                    },
                )
            }
        }
        f.action("＋ 添加规则") {
            editScript(null) {
                scripts += it
                refresh()
            }
        }
        refresh()
        f.show("登录脚本") { save(scripts) }
    }

    private fun editScript(old: LoginScript?, save: (LoginScript) -> Unit) {
        val f = Form(this)
        val expect = f.field("等待文本", old?.expect.orEmpty())
        val send = f.field("发送文本（支持 \\r、\\n）", old?.send.orEmpty(), multiline = true)
        val regex =
            f.select("匹配方式", listOf("文本", "正则表达式"), if (old?.regex == true) "正则表达式" else "文本")
        f.show("规则") {
            if (regex.selectedItemPosition == 1) Regex(expect.text.toString())
            save(
                LoginScript(
                    expect.text.toString(),
                    send.text.toString(),
                    regex.selectedItemPosition == 1,
                ),
            )
        }
    }

    private fun editTunnels(initial: List<Tunneling>, save: (List<Tunneling>) -> Unit) {
        val tunnels = initial.toMutableList()
        val f = Form(this)
        val list = column()
        f.content.addView(list)
        fun refresh() {
            list.removeAllViews()
            tunnels.forEachIndexed { n, t ->
                list.addView(
                    button(
                        "${t.type} ${t.sourceHost}:${t.sourcePort} → ${t.destinationHost}:${t.destinationPort}",
                    ) {
                        choices(t.name.ifBlank { "转发" }, listOf("编辑", "删除")) { action ->
                            if (action == 1) {
                                tunnels.removeAt(n)
                                refresh()
                            } else
                                editTunnel(t) {
                                    tunnels[n] = it
                                    refresh()
                                }
                        }
                    },
                )
            }
        }
        f.action("＋ 添加转发") {
            editTunnel(null) {
                tunnels += it
                refresh()
            }
        }
        refresh()
        f.show("端口转发") { save(tunnels) }
    }

    private fun editTunnel(old: Tunneling?, save: (Tunneling) -> Unit) {
        val f = Form(this)
        val name = f.field("名称", old?.name.orEmpty())
        val type = f.select("类型", listOf("Local", "Remote", "Dynamic"), old?.type?.name ?: "Local")
        val bind = f.field("监听地址", old?.sourceHost ?: "127.0.0.1")
        val port = f.field("监听端口（本地 0 为自动分配）", (old?.sourcePort ?: 1080).toString())
        val dest = f.field("目标地址", old?.destinationHost ?: "127.0.0.1")
        val dp = f.field("目标端口", (old?.destinationPort ?: 22).toString())
        f.show("转发规则") {
            val p = port.text.toString().toInt()
            val target = dp.text.toString().toInt()
            require(p in 0..65535 && target in 1..65535)
            if (type.selectedItem.toString() == "Remote") require(p > 0)
            save(
                Tunneling(
                    name.text.toString(),
                    TunnelingType.valueOf(type.selectedItem.toString()),
                    bind.text.toString(),
                    p,
                    dest.text.toString(),
                    target,
                ),
            )
        }
    }

    private fun connect(host: Host) {
        if (
            host.protocol.uppercase() in listOf("S3", "OSS", "COS", "OBS", "WEBDAV", "FTP", "SMB")
        ) {
            openFiles(host)
            return
        }
        if (host.isFolder) return
        if (host.protocol.uppercase() !in listOf("SSH", "SFTP", "RDP", "VNC")) {
            info("此桌面协议在 Android 中不支持：${host.protocol}")
            return
        }
        startForegroundService(Intent(this, ConnectionService::class.java))
        val session = MobileSession(host, applicationContext)
        ConnectionService.sessions[session.id] = session
        if (host.protocol.equals("RDP", true))
            LibFreeRDP.setEventListener(
                object : LibFreeRDP.EventListener {
                    private fun find(id: Long) =
                        ConnectionService.sessions.values
                            .filterIsInstance<MobileSession>()
                            .firstOrNull { it.rdp == id }

                    override fun OnPreConnect(id: Long) {}

                    override fun OnConnectionSuccess(id: Long) {
                        find(id)?.connected()
                    }

                    override fun OnConnectionFailure(id: Long) {
                        find(id)?.disconnected(true)
                    }

                    override fun OnDisconnecting(id: Long) {}

                    override fun OnDisconnected(id: Long) {
                        find(id)?.disconnected(false)
                    }
                },
            )
        if (host.protocol.equals("SFTP", true)) {
            background(
                "连接 SFTP",
                {
                    val ssh = UiBridge.ssh()
                    try {
                        ssh.connect(host)
                        ssh
                    } catch (e: Exception) {
                        ssh.close()
                        throw e
                    }
                },
            ) { ssh ->
                ConnectionService.sessions.remove(session.id)
                session.close()
                openFiles(host, ssh, true)
            }
        } else {
            showSession(session)
            session.start()
        }
    }

    private fun sessionList() {
        val p = page("连接会话", ::home)
        val list = scroll(p)
        ConnectionService.sessions.values.filterIsInstance<MobileSession>().forEach { s ->
            val r = row()
            r.addView(
                button("${s.host.name}\n${s.status}") { showSession(s) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            r.addView(
                button("断开") {
                    closeSession(s)
                    sessionList()
                },
            )
            list.addView(r)
        }
        if (ConnectionService.sessions.isEmpty()) list.addView(label("没有连接中的会话"))
    }

    private fun closeSession(session: MobileSession) {
        ConnectionService.sessions.remove(session.id)
        session.close()
        if (ConnectionService.sessions.isEmpty())
            stopService(Intent(this, ConnectionService::class.java))
    }

    private fun showSession(session: MobileSession) {
        val p = page(session.host.name, ::sessionList)
        active = session
        val status = label(session.status)
        p.addView(status)
        val actions = row()
        actions.addView(
            button(if (session.host.protocol.equals("SSH", true)) "粘贴" else "剪贴板") {
                val text = clipboardText()
                terminalView?.paste(text) ?: session.paste(text)
            },
        )
        if (session.host.protocol.equals("SSH", true)) {
            actions.addView(
                button("文件") {
                    session.ssh?.let { openFiles(session.host, it, false) } ?: info("连接尚未完成")
                },
            )
            actions.addView(button("监控") { monitor(session) })
            actions.addView(
                button("转发") {
                    val forwards = session.ssh?.activeForwards.orEmpty()
                    info(
                        if (forwards.isEmpty()) "没有启用端口转发。在主机配置中添加转发规则。"
                        else
                            forwards.joinToString("\n") { (t, port) ->
                                "${t.type} ${t.sourceHost.ifBlank {"127.0.0.1"}}:$port" +
                                    (if (t.type == TunnelingType.Dynamic) " SOCKS5"
                                    else " → ${t.destinationHost}:${t.destinationPort}")
                            },
                    )
                },
            )
            actions.addView(button("命令", ::snippets))
        } else actions.addView(button("键盘") { desktopView?.keyboard() })
        actions.addView(
            button("断开") {
                closeSession(session)
                sessionList()
            },
        )
        p.addView(HorizontalScrollView(this).apply { addView(actions) })
        val keys = row()
        if (session.host.protocol.equals("SSH", true)) {
            val tv =
                TerminalView(
                    this,
                    session.terminal,
                    session::send,
                    session::resize,
                    vault.settings,
                    charset(session.host.options.encoding),
                )
            terminalView = tv
            p.addView(tv, LinearLayout.LayoutParams(-1, 0, 1f))
            listOf(
                    "Esc" to KeyCodes.VK_ESCAPE,
                    "Tab" to KeyCodes.VK_TAB,
                    "↑" to KeyCodes.VK_UP,
                    "↓" to KeyCodes.VK_DOWN,
                    "←" to KeyCodes.VK_LEFT,
                    "→" to KeyCodes.VK_RIGHT,
                    "Enter" to KeyCodes.VK_ENTER,
                )
                .forEach { (name, key) -> keys.addView(button(name) { tv.key(key) }) }
            keys.addView(button("Ctrl") { tv.ctrl = !tv.ctrl })
            keys.addView(button("Alt") { tv.alt = !tv.alt })
            keys.addView(
                button("F1–F12") { choices("功能键", (1..12).map { "F$it" }) { tv.key(112 + it) } },
            )
            tv.requestFocus()
        } else {
            val dv = DesktopView(this, session)
            desktopView = dv
            p.addView(dv, LinearLayout.LayoutParams(-1, 0, 1f))
            listOf(
                    Triple("Esc", 0xff1b, 0x1b),
                    Triple("Tab", 0xff09, 0x09),
                    Triple("Enter", 0xff0d, 0x0d),
                    Triple("↑", 0xff52, 0x26),
                    Triple("↓", 0xff54, 0x28),
                    Triple("←", 0xff51, 0x25),
                    Triple("→", 0xff53, 0x27),
                )
                .forEach { (name, v, r) -> keys.addView(button(name) { dv.special(v, r) }) }
            keys.addView(button("Ctrl") { dv.toggleModifier(true) })
            keys.addView(button("Alt") { dv.toggleModifier(false) })
            keys.addView(
                button("F1–F12") {
                    choices("功能键", (1..12).map { "F$it" }) { dv.special(0xffbe + it, 0x70 + it) }
                },
            )
            keys.addView(
                button("Ctrl+Alt+Del") {
                    val ctrl = if (session.vnc != null) 0xffe3 else 0x11
                    val alt = if (session.vnc != null) 0xffe9 else 0x12
                    session.desktopKey(ctrl, true)
                    session.desktopKey(alt, true)
                    dv.special(0xffff, 0x2e)
                    session.desktopKey(alt, false)
                    session.desktopKey(ctrl, false)
                },
            )
        }
        p.addView(HorizontalScrollView(this).apply { addView(keys) })
        session.observer = {
            status.text = session.status
            terminalView?.invalidate()
            desktopView?.invalidate()
        }
    }

    fun terminalSelection(view: TerminalView) {
        val edit =
            EditText(this).apply {
                setText(view.terminal.getDocument().getText())
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                minLines = 8
                maxLines = 18
            }
        AlertDialog.Builder(this)
            .setTitle("终端文本：长按选择和复制")
            .setView(ScrollView(this).apply { addView(edit) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun clipboardText(): String =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

    fun remoteClipboard(text: String) {
        if (!hasWindowFocus()) return
        confirm("远程剪贴板", "允许远程服务器写入手机剪贴板？") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("Termora", text),
            )
        }
    }

    private fun groups() {
        val p = page("分组和标签", ::home)
        val list = scroll(p)
        list.addView(button("新建分组", ::newGroup))
        list.addView(
            button("新建标签") {
                textDialog("标签名称") { name ->
                    require(name.isNotBlank())
                    val now = System.currentTimeMillis()
                    Repository.update {
                        it.copy(tags = it.tags + Tag(randomUUID(), name, now, now))
                    }
                    groups()
                }
            },
        )
        vault.tags.forEach { tag ->
            list.addView(
                button("#${tag.text}") {
                    choices(tag.text, listOf("重命名", "删除")) { n ->
                        if (n == 0)
                            textDialog("标签名称", tag.text) { name ->
                                Repository.update {
                                    it.copy(
                                        tags =
                                            it.tags.map { t ->
                                                if (t.id == tag.id)
                                                    t.copy(
                                                        text = name,
                                                        updateDate = System.currentTimeMillis(),
                                                    )
                                                else t
                                            },
                                    )
                                }
                                groups()
                            }
                        else
                            confirm("删除标签", tag.text) {
                                Repository.update {
                                    it.copy(
                                        tags = it.tags.filterNot { t -> t.id == tag.id },
                                        hosts =
                                            it.hosts.map { h ->
                                                h.copy(
                                                    options =
                                                        h.options.copy(
                                                            tags = h.options.tags - tag.id,
                                                        ),
                                                )
                                            },
                                        deleted =
                                            it.deleted +
                                                DeletedData(
                                                    tag.id,
                                                    "Tag",
                                                    System.currentTimeMillis(),
                                                ),
                                    )
                                }
                                groups()
                            }
                    }
                },
            )
        }
        vault.hosts
            .filter { it.isFolder }
            .forEach { g -> list.addView(button("▸ ${g.name}") { hostActions(g) }) }
    }

    private fun snippets() {
        val session = active
        val p = page("快捷命令", { session?.let(::showSession) ?: home() })
        val list = scroll(p)
        list.addView(button("＋ 命令") { editSnippet(null) })
        vault.snippets
            .filterNot { it.deleted }
            .sortedBy { it.sort }
            .forEach { snippet ->
                val b =
                    button(snippet.name) {
                        if (session?.host?.protocol.equals("SSH", true)) {
                            session?.send(snippet.snippet.toByteArray())
                            session?.let(::showSession)
                        } else info(snippet.snippet)
                    }
                b.setOnLongClickListener {
                    choices(snippet.name, listOf("编辑", "删除")) { n ->
                        if (n == 0) editSnippet(snippet)
                        else {
                            Repository.update {
                                it.copy(
                                    snippets = it.snippets.filterNot { s -> s.id == snippet.id },
                                    deleted =
                                        it.deleted +
                                            DeletedData(
                                                snippet.id,
                                                "Snippet",
                                                System.currentTimeMillis(),
                                            ),
                                )
                            }
                            snippets()
                        }
                    }
                    true
                }
                list.addView(b)
            }
    }

    private fun editSnippet(old: Snippet?) {
        val f = Form(this)
        val name = f.field("名称", old?.name.orEmpty())
        val text = f.field("命令内容", old?.snippet.orEmpty(), multiline = true)
        f.show("快捷命令") {
            require(name.text.isNotBlank())
            val snippet =
                (old ?: Snippet(name = "")).copy(
                    name = name.text.toString(),
                    snippet = text.text.toString(),
                    updateDate = System.currentTimeMillis(),
                )
            Repository.update {
                it.copy(snippets = it.snippets.filterNot { s -> s.id == snippet.id } + snippet)
            }
            snippets()
        }
    }

    private fun keys() {
        val p = page("密钥管理", ::home)
        val list = scroll(p)
        list.addView(
            button("生成密钥") {
                choices("类型", listOf("Ed25519", "RSA 3072", "ECDSA P-256")) { n ->
                    textDialog("密钥名称") { name ->
                        background(
                            "生成密钥",
                            {
                                if (n == 2) Keys.generateEcdsa(name)
                                else Keys.generate(name, n == 0)
                            },
                        ) { key ->
                            Repository.update { it.copy(keys = it.keys + key) }
                            keys()
                        }
                    }
                }
            },
        )
        list.addView(
            button("导入私钥") {
                chooseDocument("*/*") { uri ->
                    textDialog("私钥密码（未加密可留空）", secret = true) { password ->
                        background(
                            "导入私钥",
                            {
                                contentResolver.openInputStream(uri)!!.use { input ->
                                    val out = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        require(out.size() + n <= 1024 * 1024)
                                        out.write(buffer, 0, n)
                                    }
                                    Keys.import(
                                        uri.lastPathSegment ?: "导入密钥",
                                        out.toByteArray(),
                                        password,
                                    )
                                }
                            },
                        ) { key ->
                            Repository.update { it.copy(keys = it.keys + key) }
                            keys()
                        }
                    }
                }
            },
        )
        vault.keys.forEach { key ->
            list.addView(
                button("${key.name}  ${key.type}") {
                    choices(key.name, listOf("查看公钥", "导出公钥", "导出私钥", "重命名", "删除")) { n ->
                        when (n) {
                            0 -> info(Keys.publicSsh(key))
                            1 ->
                                createDocument(key.name + ".pub", "text/plain") { uri ->
                                    contentResolver.openOutputStream(uri)!!.use {
                                        it.write(Keys.publicSsh(key).toByteArray())
                                    }
                                }
                            2 ->
                                confirm("导出私钥", "私钥将以未加密 PEM 写入选定文件，请妥善保管。") {
                                    createDocument(key.name + ".pem", "application/octet-stream") {
                                        uri ->
                                        contentResolver.openOutputStream(uri)!!.use {
                                            it.write(Keys.privatePem(key))
                                        }
                                    }
                                }
                            3 ->
                                textDialog("密钥名称", key.name) { name ->
                                    Repository.update {
                                        it.copy(
                                            keys =
                                                it.keys.map { k ->
                                                    if (k.id == key.id)
                                                        k.copy(
                                                            name = name,
                                                            updateDate = System.currentTimeMillis(),
                                                        )
                                                    else k
                                                },
                                        )
                                    }
                                    keys()
                                }
                            4 ->
                                confirm("删除密钥", key.name) {
                                    Repository.update {
                                        it.copy(
                                            keys = it.keys.filterNot { k -> k.id == key.id },
                                            deleted =
                                                it.deleted +
                                                    DeletedData(
                                                        key.id,
                                                        "KeyPair",
                                                        System.currentTimeMillis(),
                                                    ),
                                        )
                                    }
                                    keys()
                                }
                        }
                    }
                },
            )
        }
    }

    private fun macros() {
        val session = active
        val p = page("宏", { session?.let(::showSession) ?: home() })
        val list = scroll(p)
        if (session?.host?.protocol.equals("SSH", true))
            list.addView(
                button(if (session?.recording == true) "停止录制" else "开始录制") {
                    if (session!!.recording) {
                        session.recording = false
                        val bytes = session.takeRecording()
                        textDialog("宏名称") { name ->
                            val macro =
                                Macro(
                                    name = name,
                                    macro = Base64.getEncoder().encodeToString(bytes),
                                )
                            Repository.update { it.copy(macros = it.macros + macro) }
                            macros()
                        }
                    } else {
                        session.recording = true
                        showSession(session)
                    }
                },
            )
        vault.macros.forEach { macro ->
            list.addView(
                button(macro.name) {
                    choices(macro.name, listOf("运行", "重命名", "删除")) { n ->
                        when (n) {
                            0 -> {
                                require(session?.host?.protocol.equals("SSH", true)) {
                                    "先打开 SSH 会话"
                                }
                                session?.send(macro.macroByteArray)
                                session?.let(::showSession)
                            }
                            1 ->
                                textDialog("宏名称", macro.name) { name ->
                                    Repository.update {
                                        it.copy(
                                            macros =
                                                it.macros.map { m ->
                                                    if (m.id == macro.id)
                                                        m.copy(
                                                            name = name,
                                                            updateDate = System.currentTimeMillis(),
                                                        )
                                                    else m
                                                },
                                        )
                                    }
                                    macros()
                                }
                            2 -> {
                                Repository.update {
                                    it.copy(
                                        macros = it.macros.filterNot { m -> m.id == macro.id },
                                        deleted =
                                            it.deleted +
                                                DeletedData(
                                                    macro.id,
                                                    "Macro",
                                                    System.currentTimeMillis(),
                                                ),
                                    )
                                }
                                macros()
                            }
                        }
                    }
                },
            )
        }
    }

    private fun sync() {
        val p = page("云同步", ::home)
        val list = scroll(p)
        list.addView(label(vault.account?.let { "账号：${it.email}" } ?: "尚未登录 Termora 账号"))
        list.addView(
            button("登录 Termora 账号") {
                textDialog("账号服务器", vault.account?.server ?: "https://account.termora.app") { server
                    ->
                    background("准备安全登录", { AccountSync.BrowserLogin(server) }) { login ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.url)))
                        val progress =
                            ProgressDialog(this).apply {
                                setMessage("请在浏览器完成登录")
                                setCancelable(true)
                                setOnCancelListener { login.close() }
                                show()
                            }
                        UiBridge.io.execute {
                            val result = runCatching { login.use { it.await() } }
                            runOnUiThread {
                                progress.dismiss()
                                if (!isDestroyed)
                                    result.fold(
                                        { account ->
                                            Repository.update { it.copy(account = account) }
                                            sync()
                                        },
                                        ::error,
                                    )
                            }
                        }
                    }
                }
            },
        )
        if (vault.account != null) {
            list.addView(
                button("同步账号数据") {
                    background("同步账号数据", { AccountSync.synchronize(vault) }) { next ->
                        Repository.update { current ->
                            current.merge(next.syncFiles()).copy(account = next.account)
                        }
                        info("账号同步完成")
                        sync()
                    }
                },
            )
            list.addView(
                button("退出账号") {
                    Repository.update { it.copy(account = null) }
                    sync()
                },
            )
        }
        val config = vault.sync
        list.addView(label("第三方同步：${config.provider}"))
        list.addView(
            button("配置 WebDAV / GitHub 同步") {
                val f = Form(this)
                val provider = f.select("服务", listOf("WebDAV", "GitHub"), config.provider)
                val endpoint =
                    f.field(
                        "HTTPS 文件地址 / GitHub API 地址",
                        config.endpoint.ifBlank { "https://api.github.com" },
                    )
                val user = f.field("WebDAV 用户名", config.username)
                val token = f.field("WebDAV 密码 / GitHub 令牌", config.token, true)
                val pass = f.field("独立加密密码（至少 12 个字符）", config.password, true)
                val gist = f.field("Gist ID（新建可留空）", config.gistId)
                f.show("同步配置") {
                    require(pass.text.length >= 12 && pass.text.toString() != token.text.toString())
                    Repository.update {
                        it.copy(
                            sync =
                                SyncSettings(
                                    provider.selectedItem.toString(),
                                    endpoint.text.toString(),
                                    user.text.toString(),
                                    token.text.toString(),
                                    pass.text.toString(),
                                    gist.text.toString(),
                                ),
                        )
                    }
                    sync()
                }
            },
        )
        list.addView(
            button("同步第三方数据") {
                background("拉取、合并并上传加密配置", { CloudSync.synchronize(vault) }) { next ->
                    Repository.update { current ->
                        current.merge(next.syncFiles()).copy(sync = next.sync)
                    }
                    info("同步完成")
                    sync()
                }
            },
        )
        list.addView(
            button("导出加密备份") {
                textDialog("备份密码（至少 12 个字符）", secret = true) { password ->
                    require(password.length >= 12)
                    createDocument("termora-backup.json", "application/json") { uri ->
                        background(
                            "加密备份",
                            {
                                val data =
                                    SyncEncryption.encryptWithPassword(
                                        password,
                                        wireJson.encodeToString(vault),
                                        "AndroidBackup",
                                    )
                                contentResolver.openOutputStream(uri)!!.use {
                                    it.write(data.toByteArray())
                                }
                            },
                        ) {
                            info("备份已保存")
                        }
                    }
                }
            },
        )
        list.addView(
            button("导入加密备份") {
                chooseDocument("application/json") { uri ->
                    textDialog("备份密码", secret = true) { password ->
                        background(
                            "验证并解密备份",
                            {
                                val text =
                                    contentResolver.openInputStream(uri)!!.use {
                                        it.readLimited(32 * 1024 * 1024).toString(Charsets.UTF_8)
                                    }
                                require(text.length <= 32 * 1024 * 1024)
                                wireJson.decodeFromString<Vault>(
                                    SyncEncryption.decryptWithPassword(
                                        password,
                                        text,
                                        "AndroidBackup",
                                    ),
                                )
                            },
                        ) { next ->
                            Repository.update { current ->
                                current
                                    .merge(next.syncFiles())
                                    .copy(
                                        settings = next.settings,
                                        sync = next.sync,
                                        account = next.account,
                                        pins = next.pins + current.pins,
                                    )
                            }
                            info("备份已合并")
                            sync()
                        }
                    }
                }
            },
        )
    }

    private fun settings() {
        val f = Form(this)
        val size = f.field("终端字号（sp）", vault.settings.fontSize.toString())
        val spacing = f.field("额外字间距（dp，推荐 0）", vault.settings.letterSpacing.toString())
        f.show("终端显示") {
            val s = size.text.toString().toFloat()
            val gap = spacing.text.toString().toFloat()
            require(s in 8f..32f && gap in 0f..4f)
            Repository.update { it.copy(settings = MobileSettings(s, gap)) }
            active?.let(::showSession) ?: home()
        }
    }

    private fun pins() {
        val p = page("已信任的服务器", ::home)
        val list = scroll(p)
        vault.pins.forEach { (id, pin) ->
            list.addView(
                button("$id\n$pin") {
                    confirm("忘记服务器指纹", "下次连接时需要重新核对身份。") {
                        Repository.update { it.copy(pins = it.pins - id) }
                        pins()
                    }
                },
            )
        }
    }

    private fun monitor(session: MobileSession) {
        val ssh = session.ssh ?: return info("连接尚未完成")
        val p = page("服务器监控", { showSession(session) })
        val list = scroll(p)
        val text = label("读取中…")
        text.typeface = android.graphics.Typeface.MONOSPACE
        list.addView(text)
        var running = false
        fun refresh() {
            if (running) return
            running = true
            UiBridge.io.execute {
                val result = runCatching {
                    ssh.exec(
                        "printf '\\nCPU / 内存 / 负载\\n'; LC_ALL=C top -b -n 2 -d 1 | grep -E '^(top -|Tasks:|%?Cpu|[KMG]i?B Mem|[KMG]i?B Swap)' | tail -n 5; printf '\\n磁盘\\n'; df -h -P; printf '\\nNVIDIA GPU\\n'; if command -v nvidia-smi >/dev/null 2>&1; then nvidia-smi --query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv; else printf '未检测到 NVIDIA 驱动\\n'; fi",
                        20,
                    )
                }
                runOnUiThread {
                    running = false
                    if (!isDestroyed) text.text = result.getOrElse { it.message.orEmpty() }
                }
            }
        }
        list.addView(button("刷新", ::refresh))
        refresh()
    }

    private fun chooseDocument(mime: String, callback: (Uri) -> Unit) {
        uriCallback = callback
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE),
            10,
        )
    }

    private fun createDocument(name: String, mime: String, callback: (Uri) -> Unit) {
        uriCallback = callback
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, name),
            10,
        )
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 10) {
            val callback = uriCallback
            uriCallback = null
            if (result == RESULT_OK)
                data?.data?.let { uri -> runCatching { callback?.invoke(uri) }.onFailure(::error) }
        }
    }

    // Android 9–12 dispatch back here; Android 13+ uses OnBackInvokedDispatcher above.
    @SuppressLint("GestureBackNavigation") override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (active != null && (terminalView != null || desktopView != null)) {
            sessionList()
        } else if (folder != "0") {
            folder = vault.hosts.firstOrNull { it.id == folder }?.parentId ?: "0"
            home()
        } else home()
    }

    private fun openFiles(host: Host, ssh: SshConnection? = null, ownsSsh: Boolean = false) {
        fileBrowser?.close()
        background(
            "连接文件服务",
            {
                when (host.protocol.uppercase()) {
                    "SSH",
                    "SFTP" -> SftpStore(ssh ?: error("SSH 未连接"))
                    "FTP" -> FtpStore(host)
                    "SMB" -> SmbStore(host)
                    "WEBDAV" -> WebDavStore(host)
                    else -> ObjectStore(host)
                }
            },
        ) { store ->
            val browser = FileBrowser(host, store, ssh, ownsSsh)
            fileBrowser = browser
            browser.show(host.options.sftpDefaultDirectory.ifBlank { "/" })
        }
    }

    private inner class FileBrowser(
        private val host: Host,
        private val store: FileStore,
        private val ssh: SshConnection?,
        private val ownsSsh: Boolean,
    ) : Closeable {
        private val executor = Executors.newSingleThreadExecutor()
        private var path = "/"
        private var closed = false
        private val leaseId = "file-" + randomUUID()

        init {
            if (ownsSsh) {
                val heldStore = store
                val heldSsh = ssh
                ConnectionService.sessions[leaseId] = Closeable {
                    runCatching { heldStore.close() }
                    heldSsh?.close()
                }
            }
        }

        private fun <T> task(message: String, work: () -> T, done: (T) -> Unit) {
            val progress =
                ProgressDialog(this@MainActivity).apply {
                    setMessage(message)
                    setCancelable(false)
                    show()
                }
            executor.execute {
                val result = runCatching(work)
                runOnUiThread {
                    progress.dismiss()
                    if (!closed && !isDestroyed) result.fold(done, ::error)
                }
            }
        }

        fun show(dir: String) {
            path = dir
            val session = active
            val p =
                page(
                    "${host.name} · 文件",
                    {
                        close()
                        fileBrowser = null
                        session?.let(::showSession) ?: home()
                    },
                )
            val actions = row()
            actions.addView(
                button("上一级") {
                    show(path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" })
                },
            )
            actions.addView(button("刷新") { show(path) })
            actions.addView(
                button("新建文件夹") {
                    textDialog("文件夹名称") { name ->
                        task("创建文件夹", { store.mkdir(remotePath(path, name)) }) { show(path) }
                    }
                },
            )
            actions.addView(
                button("上传") {
                    chooseDocument("*/*") { uri ->
                        textDialog(
                            "远程文件名",
                            uri.lastPathSegment?.substringAfterLast('/') ?: "upload",
                        ) { name ->
                            val target = remotePath(path, name)
                            task(
                                "上传文件",
                                {
                                    val temp = File.createTempFile("upload-", ".tmp", cacheDir)
                                    try {
                                        contentResolver.openInputStream(uri)!!.use { input ->
                                            temp.outputStream().use { input.copyTo(it) }
                                        }
                                        temp.inputStream().use {
                                            store.write(target, it, temp.length())
                                        }
                                    } finally {
                                        temp.delete()
                                    }
                                },
                            ) {
                                show(path)
                            }
                        }
                    }
                },
            )
            p.addView(HorizontalScrollView(this@MainActivity).apply { addView(actions) })
            p.addView(button(path) { textDialog("远程目录", path) { show(it) } })
            val list = scroll(p)
            task("读取目录", { store.list(path) }) { files ->
                files
                    .sortedWith(compareByDescending<RemoteFile> { it.directory }.thenBy { it.name })
                    .forEach { file ->
                        val b =
                            button(
                                (if (file.directory) "▸ " else "") +
                                    file.name +
                                    (if (!file.directory) "  ${file.size} B" else ""),
                            ) {
                                if (file.directory) show(file.path) else fileActions(file)
                            }
                        b.gravity = Gravity.START
                        b.setOnLongClickListener {
                            fileActions(file)
                            true
                        }
                        list.addView(b)
                    }
            }
        }

        private fun fileActions(file: RemoteFile) {
            val items = mutableListOf<String>()
            if (!file.directory) items += listOf("下载", "编辑文本")
            items += listOf("重命名", "删除")
            if (store is SftpStore) items += listOf("修改权限", "压缩", "解压")
            choices(file.name, items) { n ->
                when (items[n]) {
                    "下载" ->
                        createDocument(file.name, "application/octet-stream") { uri ->
                            task(
                                "下载文件",
                                {
                                    contentResolver.openOutputStream(uri)!!.use {
                                        store.read(file.path, it)
                                    }
                                },
                            ) {
                                info("下载完成")
                            }
                        }
                    "编辑文本" -> {
                        require(file.size <= 1024 * 1024) { "文本编辑限制为 1 MiB" }
                        task(
                            "读取文件",
                            {
                                val out =
                                    object : ByteArrayOutputStream() {
                                        override fun write(b: ByteArray, off: Int, len: Int) {
                                            require(size() + len <= 1024 * 1024)
                                            super.write(b, off, len)
                                        }
                                    }
                                store.read(file.path, out)
                                out.toString("UTF-8")
                            },
                        ) { text ->
                            textDialog(file.name, text, true) { changed ->
                                val bytes = changed.toByteArray()
                                task(
                                    "保存文件",
                                    {
                                        store.write(
                                            file.path,
                                            ByteArrayInputStream(bytes),
                                            bytes.size.toLong(),
                                        )
                                    },
                                ) {
                                    show(path)
                                }
                            }
                        }
                    }
                    "重命名" ->
                        textDialog("新名称", file.name) { name ->
                            task("重命名", { store.rename(file.path, remotePath(path, name)) }) {
                                show(path)
                            }
                        }
                    "删除" ->
                        confirm("删除文件", file.path) {
                            task("删除", { store.delete(file.path, file.directory) }) { show(path) }
                        }
                    "修改权限" ->
                        textDialog(
                            "权限（八进制）",
                            file.permissions?.let { Integer.toOctalString(it and 4095) } ?: "644",
                        ) { mode ->
                            task("修改权限", { (store as SftpStore).chmod(file.path, mode.toInt(8)) }) {
                                show(path)
                            }
                        }
                    "压缩" ->
                        textDialog("压缩文件完整路径", file.path + ".tar.gz") { dest ->
                            task("远程压缩", { (store as SftpStore).archive(file.path, dest, false) }) {
                                show(path)
                            }
                        }
                    "解压" ->
                        textDialog("解压目录", path) { dest ->
                            task("远程解压", { (store as SftpStore).archive(file.path, dest, true) }) {
                                show(path)
                            }
                        }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            val lease = ConnectionService.sessions.remove(leaseId)
            executor.execute {
                if (lease != null) lease.close()
                else {
                    runCatching { store.close() }
                    if (ownsSsh) ssh?.close()
                }
            }
            executor.shutdown()
            if (ConnectionService.sessions.isEmpty())
                stopService(Intent(this@MainActivity, ConnectionService::class.java))
        }
    }
}
