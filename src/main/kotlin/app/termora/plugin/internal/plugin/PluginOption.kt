package app.termora.plugin.internal.plugin

import app.termora.*
import app.termora.account.Account
import app.termora.account.AccountExtension
import app.termora.account.AccountManager
import app.termora.plugin.PluginDescriptor
import app.termora.plugin.PluginFiles
import app.termora.plugin.PluginManager
import app.termora.plugin.PluginOrigin
import app.termora.plugin.PluginXmlParser
import app.termora.plugin.internal.extension.DynamicExtensionHandler
import app.termora.plugin.marketplace.MarketplaceManager
import com.formdev.flatlaf.extras.components.FlatPopupMenu
import com.formdev.flatlaf.extras.components.FlatTabbedPane
import com.formdev.flatlaf.extras.components.FlatToolBar
import org.apache.commons.io.FileUtils
import java.awt.BorderLayout
import java.io.File
import java.util.jar.JarFile
import javax.swing.*

class PluginOption : JPanel(BorderLayout()), OptionsPane.Option, Disposable, AccountExtension {

    companion object {
        val installedFromDisk = mutableListOf<PluginPluginDescriptor>()
    }

    private val installButtons = mutableListOf<JButton>()
    private val tabbed = FlatTabbedPane()
    private val toolbar = FlatToolBar()
    private val settingsButton = JButton(Icons.settings)
    private val refreshButton = JButton(Icons.refresh)
    private val owner get() = SwingUtilities.getWindowAncestor(this)
    private val marketplacePanel = MarketplacePanel()
    private val installedPanel = InstalledPanel()
    private val marketplaceManager get() = MarketplaceManager.getInstance()

    init {
        initView()
        initEvents()
    }


    private fun initView() {

        tabbed.styleMap = mapOf(
            "focusColor" to DynamicColor("TabbedPane.background"),
            "hoverColor" to DynamicColor("TabbedPane.background"),
            "inactiveUnderlineColor" to DynamicColor("TabbedPane.underlineColor")
        )
        tabbed.isHasFullBorder = false
        tabbed.tabPlacement = JTabbedPane.TOP

        Disposer.register(this, marketplacePanel)
        Disposer.register(this, installedPanel)

        tabbed.addTab(I18n.getString("termora.settings.plugin.marketplace"), marketplacePanel)
        tabbed.addTab(I18n.getString("termora.settings.plugin.installed"), installedPanel)

        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(refreshButton)
        toolbar.add(settingsButton)
        tabbed.trailingComponent = toolbar

        tabbed.selectedIndex = EnableManager.getInstance().getFlag("PluginOption.defaultTab", 0)
        refreshButton.isVisible = tabbed.selectedIndex == 0

        add(tabbed, BorderLayout.CENTER)

        putClientProperty("ContentPanelBorder", BorderFactory.createEmptyBorder())
    }

    private fun initEvents() {
        DynamicExtensionHandler.getInstance().register(AccountExtension::class.java, this)
            .let { Disposer.register(this, it) }

        settingsButton.addActionListener { showContextMenu() }
        refreshButton.addActionListener {
            if (marketplacePanel.isLoading.get().not()) {
                marketplaceManager.clear()
                marketplacePanel.reload()
            }
        }
        tabbed.addChangeListener { refreshButton.isVisible = tabbed.selectedIndex == 0 }
    }

    override fun getIcon(isSelected: Boolean): Icon {
        return Icons.plugin
    }

    override fun getTitle(): String {
        return I18n.getString("termora.settings.plugin")
    }

    override fun getJComponent(): JComponent {
        return this
    }

    override fun dispose() {
        DynamicExtensionHandler.getInstance().unregister(this)

        EnableManager.getInstance().setFlag("PluginOption.defaultTab", tabbed.selectedIndex)
    }

    private fun showContextMenu() {
        val popupMenu = FlatPopupMenu()

        val managePluginRepositoryMenu =
            popupMenu.add(I18n.getString("termora.settings.plugin.manage-plugin-repository"))
        managePluginRepositoryMenu.addActionListener {
            val dialog = PluginRepositoryDialog(owner)
            dialog.isVisible = true
            if (dialog.changed) {
                MarketplaceManager.getInstance().clear()
                marketplacePanel.reload()
            }
        }
        popupMenu.addSeparator()

        val installPluginFromDiskMenu = popupMenu.add(I18n.getString("termora.settings.plugin.install-from-disk"))
        installPluginFromDiskMenu.addActionListener {
            val chooser = FileChooser()
            chooser.osxAllowedFileTypes = listOf("zip")
            chooser.allowsMultiSelection = false
            chooser.win32Filters.add(Pair("Zip files", listOf("zip")))
            chooser.fileSelectionMode = JFileChooser.FILES_ONLY
            chooser.showOpenDialog(owner).thenAccept { if (it.isNotEmpty()) installPluginFromDisk(it.first()) }
        }

        popupMenu.show(settingsButton, 0, settingsButton.height)
    }

    private fun installPluginFromDisk(file: File) {
        val dir = Application.createSubTemporaryDir("plugin").toFile()

        try {
            file.inputStream().use { PluginFiles.extract(it, dir.toPath()) }
        } catch (e: Exception) {
            FileUtils.deleteQuietly(dir)
            SwingUtilities.invokeLater {
                OptionPane.showMessageDialog(owner, e.message ?: "Invalid plugin archive", messageType = JOptionPane.ERROR_MESSAGE)
            }
            return
        }

        // 尝试读取插件内容
        for (item in FileUtils.listFiles(dir, arrayOf("jar"), true)) {
            try {
                val pluginDescriptor = JarFile(item).use { parseJarFile(it) }
                SwingUtilities.invokeLater {
                    try {
                        installPlugin(item.parentFile, pluginDescriptor)
                    } catch (e: Exception) {
                        OptionPane.showMessageDialog(owner, e.message ?: "Unable to install plugin", messageType = JOptionPane.ERROR_MESSAGE)
                    } finally {
                        FileUtils.deleteQuietly(dir)
                    }
                }
                return
            } catch (_: Exception) {
                continue
            }
        }

        FileUtils.deleteQuietly(dir)
        SwingUtilities.invokeLater {
            OptionPane.showMessageDialog(
                owner, I18n.getString("termora.settings.plugin.install-failed"),
                messageType = JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun installPlugin(folder: File, pluginDescriptor: PluginDescriptor) {
        if (OptionPane.showConfirmDialog(
                owner,
                I18n.getString("termora.settings.plugin.install-from-disk-warning", pluginDescriptor.plugin.getName()),
                optionType = JOptionPane.OK_CANCEL_OPTION,
                messageType = JOptionPane.WARNING_MESSAGE,
                options = arrayOf(I18n.getString("termora.settings.plugin.install"), I18n.getString("termora.cancel")),
                initialValue = I18n.getString("termora.settings.plugin.install")
            ) != JOptionPane.OK_OPTION
        ) return

        var pluginDirectory = PluginFiles.directory(
            PluginManager.getInstance().getPluginDirectory().toPath(), pluginDescriptor.id
        ).toFile()

        // 如果已经存在，那么变成更新插件
        if (pluginDirectory.exists() && pluginDirectory.isDirectory) {
            pluginDirectory = PluginFiles.directory(pluginDirectory.toPath(), "updated").toFile()
            if (pluginDirectory.exists()) {
                FileUtils.deleteQuietly(pluginDirectory)
            }
        }
        FileUtils.forceMkdir(pluginDirectory)

        for (file in folder.listFiles { it.isFile } ?: emptyArray()) {
            FileUtils.moveFileToDirectory(file, pluginDirectory, false)
        }


        val descriptor = PluginPluginDescriptor(
            plugin = pluginDescriptor.plugin,
            id = pluginDescriptor.id,
            icon = pluginDescriptor.icon,
            origin = PluginOrigin.Memory,
            version = pluginDescriptor.version,
            descriptions = pluginDescriptor.descriptions,
            marketplace = false,
        )

        installedFromDisk.add(descriptor)


        for (c in SwingUtils.getDescendantsOfClass(PluginPanel::class.java, installedPanel)) {
            if (c.descriptor.id == pluginDescriptor.id) {
                c.refreshButtons()
            }
        }

    }


    private fun parseJarFile(jarFile: JarFile): PluginXmlParser.MyPluginDescriptor {

        val plugin = jarFile.getEntry("META-INF/plugin.xml")
            ?: throw IllegalStateException("META-INF/plugin.xml not found")
        val icon = jarFile.getEntry("META-INF/pluginIcon.svg")
        val darkIcon = jarFile.getEntry("META-INF/pluginIcon_dark.svg")

        val pluginInputSteam = jarFile.getInputStream(plugin).readAllBytes().inputStream()
        val iconStream = icon?.let { jarFile.getInputStream(it).readAllBytes().inputStream() }
        val darkIconStream = darkIcon?.let { jarFile.getInputStream(it).readAllBytes().inputStream() }

        return PluginXmlParser.parse(
            pluginInputSteam,
            iconStream,
            darkIconStream
        )
    }

    override fun onAccountChanged(oldAccount: Account, newAccount: Account) {
        val isFreePlan = AccountManager.getInstance().isFreePlan()
        for (button in installButtons) {
            button.icon = if (isFreePlan) Icons.locked else null
        }
    }

    override fun getIdentifier(): String {
        return "Plugin"
    }

    override fun getAnchor(): OptionsPane.Anchor {
        return OptionsPane.Anchor.Before("About")
    }
}
