package desu.inugram.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.update.ChangelogHelper
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.tgnet.tl.TL_iv

// release-note history from the updates channel: the installed build by default,
// older builds via the bottom bar. builds without a channel post are skipped,
// except the installed one, which shows an empty state instead.
class ChangelogActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChangelog)

    private sealed interface Screen {
        data object Loading : Screen
        data class Failed(val message: String) : Screen
        data class Ready(val entries: List<ChangelogHelper.Entry>) : Screen
    }

    private var screen: Screen = Screen.Loading
    // index into entries, or -1 for the installed build when it has no channel post
    private var pos = 0
    private var installedMissing = false
    // messageId -> loaded body; null value means loaded but empty/unreadable
    private val bodies = HashMap<Int, TL_iv.RichMessage?>()
    private val bodiesLoading = HashSet<Int>()

    private var newerButton: TextView? = null
    private var olderButton: TextView? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        when (val s = screen) {
            is Screen.Loading -> {
                items.add(UItem.asCustom(statusView(LocaleController.getString(R.string.Loading))))
                items.add(UItem.asShadow(null))
            }
            is Screen.Failed -> {
                items.add(UItem.asCustom(statusView(LocaleController.formatString(R.string.InuUpdateError, s.message))))
                items.add(UItem.asButton(BUTTON_RETRY, LocaleController.getString(R.string.Retry)))
                items.add(UItem.asShadow(null))
            }
            is Screen.Ready -> {
                items.add(UItem.asCustom(versionView()))
                items.add(UItem.asCustom(bodyView(s.entries)))
                items.add(UItem.asShadow(null))
            }
        }
    }

    override fun createView(context: Context): View {
        val root = super.createView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val newer = createSheetButton(
            context,
            LocaleController.getString(R.string.InuChangelogNewer),
            background = Theme.createSimpleSelectorRoundRectDrawable(dp(24f), 0, Theme.getColor(Theme.key_dialogButtonSelector)),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) { move(-1) }
        val older = createSheetButton(
            context,
            LocaleController.getString(R.string.InuChangelogOlder),
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 24f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) { move(1) }
        row.addView(newer, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
        row.addView(older, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f))
        newerButton = newer
        olderButton = older
        attachStickyButton(root, row)
        refreshNav()
        if (screen is Screen.Loading) load()
        return root
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_RETRY) load()
    }

    private fun load() {
        screen = Screen.Loading
        refresh()
        ChangelogHelper.loadHistory { result ->
            if (context == null) return@loadHistory
            when (result) {
                is ChangelogHelper.HistoryResult.Error -> screen = Screen.Failed(result.message)
                is ChangelogHelper.HistoryResult.Loaded -> {
                    screen = Screen.Ready(result.entries)
                    installedMissing = result.entries.firstOrNull()?.verCode != UpdateHelper.installedVersionCode()
                    pos = if (installedMissing) -1 else 0
                    currentEntry(result.entries)?.let { requestBody(it) }
                }
            }
            refresh()
        }
    }

    private fun currentEntry(entries: List<ChangelogHelper.Entry>): ChangelogHelper.Entry? =
        if (pos == -1) null else entries.getOrNull(pos)

    private fun move(d: Int) {
        val entries = (screen as? Screen.Ready)?.entries ?: return
        val min = if (installedMissing) -1 else 0
        val next = (pos + d).coerceIn(min, entries.size - 1)
        if (next == pos) return
        pos = next
        currentEntry(entries)?.let { requestBody(it) }
        refresh()
    }

    private fun requestBody(entry: ChangelogHelper.Entry) {
        if (bodies.containsKey(entry.messageId) || !bodiesLoading.add(entry.messageId)) return
        ChangelogHelper.loadChangelog(UserConfig.selectedAccount, entry) { body ->
            bodiesLoading.remove(entry.messageId)
            if (context == null) return@loadChangelog
            bodies[entry.messageId] = body
            refresh()
        }
    }

    private fun refresh() {
        listView?.adapter?.update(true)
        refreshNav()
        // the list first lays out with short loading/empty rows; when the tall changelog
        // swaps in, the RecyclerView can land at the bottom. content only ever changes to
        // a new entry here, so pinning to the top is always correct. posted: the adapter
        // lays out asynchronously, the pending scroll applies on the next pass.
        listView?.post { listView?.layoutManager?.scrollToPositionWithOffset(0, 0) }
    }

    private fun refreshNav() {
        val entries = (screen as? Screen.Ready)?.entries
        val min = if (installedMissing) -1 else 0
        setNavEnabled(newerButton, entries != null && pos > min)
        setNavEnabled(olderButton, entries != null && pos < entries.size - 1)
    }

    private fun setNavEnabled(button: TextView?, enabled: Boolean) {
        button ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun versionView(): View {
        val s = screen as? Screen.Ready
        val label = if (pos == -1 || s == null) {
            installedVersionLabel()
        } else {
            val e = s.entries[pos]
            LocaleController.formatString(R.string.InuChangelogVersion, e.appVerName, e.verCode)
        }
        return TextView(context).apply {
            typeface = AndroidUtilities.bold()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            text = label
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(21f), dp(14f), dp(21f), dp(4f))
        }
    }

    private fun bodyView(entries: List<ChangelogHelper.Entry>): View {
        val entry = currentEntry(entries) ?: return emptyView()
        if (bodiesLoading.contains(entry.messageId) || !bodies.containsKey(entry.messageId)) {
            requestBody(entry)
            return statusView(LocaleController.getString(R.string.Loading))
        }
        val body = bodies[entry.messageId]
        if (body == null || body.blocks.isEmpty()) return emptyView()
        // same view the update dialog renders new-version changelogs with
        return RichMessageLayout.PreviewView(context, UserConfig.selectedAccount, resourceProvider).apply {
            set(body)
            setPadding(dp(21f), dp(11f), dp(21f), dp(8f))
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    private fun emptyView(): View = statusView(LocaleController.getString(R.string.InuChangelogEmpty))

    private fun statusView(text: CharSequence): View = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        this.text = text
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(21f), dp(24f), dp(21f), dp(24f))
    }

    private fun installedVersionLabel(): String = LocaleController.formatString(
        R.string.InuChangelogVersion,
        UpdateHelper.stockVersionName,
        UpdateHelper.installedVersionCode(),
    )

    companion object {
        private val BUTTON_RETRY = desu.inugram.helpers.InuUtils.generateId()
    }
}
