package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import org.telegram.ui.Cells.LoadingCell
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
    // displayed entry: lags behind pos while the selected body is still loading, so fast
    // loads swap seamlessly and slow ones keep showing the previous entry for 500ms.
    private var shownPos = 0
    private var pendingGen = 0
    private var installedMissing = false
    // messageId -> loaded body; null value means loaded but empty/unreadable
    private val bodies = HashMap<Int, TL_iv.RichMessage?>()
    private val bodiesLoading = HashSet<Int>()
    // messageId -> built row view. fills re-attach the same instance instead of
    // re-running PreviewView layout on every update, like the update dialog's static rows.
    private val bodyViews = HashMap<Int, View>()

    private var newerButton: View? = null
    private var olderButton: View? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        when (val s = screen) {
            is Screen.Loading -> {
                items.add(UItem.asCustom(loadingView()))
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
        val newer = navButton(
            context,
            LocaleController.getString(R.string.InuChangelogNewer),
            background = Theme.createSimpleSelectorRoundRectDrawable(dp(24f), 0, Theme.getColor(Theme.key_dialogButtonSelector)),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
            chevronStart = true,
        ) { move(-1) }
        val older = navButton(
            context,
            LocaleController.getString(R.string.InuChangelogOlder),
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 24f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
            chevronStart = false,
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
        pendingGen++
        screen = Screen.Loading
        refresh(scrollToTop = true)
        ChangelogHelper.loadHistory { result ->
            if (context == null) return@loadHistory
            when (result) {
                is ChangelogHelper.HistoryResult.Error -> screen = Screen.Failed(result.message)
                is ChangelogHelper.HistoryResult.Loaded -> {
                    screen = Screen.Ready(result.entries)
                    installedMissing = result.entries.firstOrNull()?.verCode != UpdateHelper.installedVersionCode()
                    pos = if (installedMissing) -1 else 0
                    shownPos = pos
                    currentEntry(result.entries)?.let { requestBody(it) }
                }
            }
            refresh(scrollToTop = true)
        }
    }

    override fun onFragmentDestroy() {
        pendingGen++
        super.onFragmentDestroy()
    }

    private fun currentEntry(entries: List<ChangelogHelper.Entry>): ChangelogHelper.Entry? =
        if (shownPos == -1) null else entries.getOrNull(shownPos)

    private fun move(d: Int) {
        val entries = (screen as? Screen.Ready)?.entries ?: return
        val min = if (installedMissing) -1 else 0
        val next = (pos + d).coerceIn(min, entries.size - 1)
        if (next == pos) return
        pendingGen++
        pos = next
        val target = if (next == -1) null else entries.getOrNull(next)
        if (target == null || bodies.containsKey(target.messageId)) {
            shownPos = next
            refresh(scrollToTop = true)
            return
        }
        requestBody(target)
        // slow load: keep showing the previous entry for 500ms, switch to the spinner
        // only if the body still hasn't arrived. stale timers are dropped by generation.
        val gen = pendingGen
        AndroidUtilities.runOnUIThread({
            if (gen == pendingGen && pos == next && !bodies.containsKey(target.messageId)) {
                shownPos = next
                refresh(scrollToTop = true)
            }
        }, 500)
        refreshNav()
    }

    private fun requestBody(entry: ChangelogHelper.Entry) {
        if (bodies.containsKey(entry.messageId) || !bodiesLoading.add(entry.messageId)) return
        ChangelogHelper.loadChangelog(UserConfig.selectedAccount, entry) { body ->
            bodiesLoading.remove(entry.messageId)
            if (context == null) return@loadChangelog
            bodies[entry.messageId] = body
            // stale arrival after fast Older/Newer tapping: cache it, leave the list alone
            val current = (screen as? Screen.Ready)?.entries?.let { list ->
                if (pos == -1) null else list.getOrNull(pos)
            }
            if (current?.messageId == entry.messageId) {
                shownPos = pos
                refresh(scrollToTop = true)
            }
        }
    }

    private fun refresh(scrollToTop: Boolean) {
        // scroll synchronously *before* the update, so the swapped-in content's first
        // laid-out frame is already at the top: no intermediate frame at the old position
        // followed by a visible jump. plain notify (no diff animations on a full-screen
        // rich-text view). body arrivals for the displayed entry don't scroll at all.
        if (scrollToTop) listView?.layoutManager?.scrollToPositionWithOffset(0, 0)
        listView?.adapter?.update(false)
        refreshNav()
    }

    private fun refreshNav() {
        val entries = (screen as? Screen.Ready)?.entries
        val min = if (installedMissing) -1 else 0
        setNavEnabled(newerButton, entries != null && pos > min)
        setNavEnabled(olderButton, entries != null && pos < entries.size - 1)
    }

    private fun setNavEnabled(button: View?, enabled: Boolean) {
        button ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    // bottom-bar button: settings-chevron head + title as one centered group, so the
    // chevron-title gap is exact and the group always sits clear of the button borders.
    // stock has no left chevron asset, so the leading one mirrors msg_arrowright.
    private fun navButton(
        context: Context,
        title: CharSequence,
        background: Drawable,
        textColor: Int,
        bold: Boolean,
        chevronStart: Boolean,
        onClick: () -> Unit,
    ): View {
        val container = FrameLayout(context).apply {
            this.background = background
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.msg_arrowright)
            if (chevronStart) rotationY = 180f
            colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.MULTIPLY)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(context).apply {
            text = title
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(textColor)
            if (bold) typeface = AndroidUtilities.bold()
        }
        val gap = dp(8f)
        if (chevronStart) {
            row.addView(chevron, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = gap })
            row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(chevron, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = gap })
        }
        container.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return container
    }

    private fun versionView(): View {
        val s = screen as? Screen.Ready
        val label = if (shownPos == -1 || s == null) {
            installedVersionLabel()
        } else {
            val e = s.entries[shownPos]
            LocaleController.formatString(R.string.InuChangelogVersion, e.verCode)
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
        bodyViews[entry.messageId]?.let { return it }
        if (bodiesLoading.contains(entry.messageId) || !bodies.containsKey(entry.messageId)) {
            requestBody(entry)
            return loadingView()
        }
        val body = bodies[entry.messageId]
        val view = if (body == null || body.blocks.isEmpty()) emptyView() else previewView(body)
        bodyViews[entry.messageId] = view
        return view
    }

    private fun previewView(body: TL_iv.RichMessage): View =
        // same view the update dialog renders new-version changelogs with
        RichMessageLayout.PreviewView(context, UserConfig.selectedAccount, resourceProvider).apply {
            set(body)
            setPadding(dp(21f), dp(11f), dp(21f), dp(8f))
            isFocusable = false
            isFocusableInTouchMode = false
        }

    private fun emptyView(): View = statusView(LocaleController.getString(R.string.InuChangelogEmpty))

    private fun loadingView(): View = LoadingCell(context, dp(28f), dp(120f))

    private fun statusView(text: CharSequence): View = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        this.text = text
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(21f), dp(24f), dp(21f), dp(24f))
    }

    private fun installedVersionLabel(): String = LocaleController.formatString(
        R.string.InuChangelogVersion,
        UpdateHelper.installedVersionCode(),
    )

    companion object {
        private val BUTTON_RETRY = desu.inugram.helpers.InuUtils.generateId()
    }
}
