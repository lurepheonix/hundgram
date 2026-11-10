package desu.inugram

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import desu.inugram.helpers.update.UpdateHelper
import desu.inugram.ui.ChangelogSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

class UpdateAppAlertDialog(
    context: Context,
    appUpdate: TLRPC.TL_help_appUpdate,
    accountNum: Int,
) : ChangelogSheet(
    context,
    LocaleController.getString(R.string.InuUpdate),
    createRows(context, appUpdate, accountNum),
    LocaleController.getString(R.string.AppUpdateRemindMeLater),
    { UpdateHelper.clearPending() },
    LocaleController.getString(R.string.AppUpdateDownloadNow),
    {
        UpdateHelper.markWhatsNewSeen(appUpdate.version.toIntOrNull() ?: 0)
        UpdateHelper.startDownload(accountNum)
    },
) {
    override fun onOpenAnimationEnd() {
        super.onOpenAnimationEnd()
        UpdateHelper.revealPendingUpdate()
    }

    private companion object {
        fun createRows(context: Context, appUpdate: TLRPC.TL_help_appUpdate, accountNum: Int): List<View> {
            val rows = ArrayList<View>()

            appUpdate.sticker?.let { sticker ->
                val imageView = BackupImageView(context)
                val docLocation = ImageLocation.getForDocument(sticker)
                val svgThumb = DocumentObject.getSvgThumb(sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f)
                if (svgThumb != null) {
                    imageView.setImage(docLocation, "250_250", svgThumb, 0, "update")
                } else {
                    val thumb = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
                    imageView.setImage(docLocation, "250_250", ImageLocation.getForDocument(thumb, sticker), null, 0, "update")
                }
                rows.add(FrameLayout(context).apply {
                    addView(imageView, LayoutHelper.createFrame(160, 160f, Gravity.CENTER_HORIZONTAL, 17f, 8f, 17f, 0f))
                })
            }

            rows.add(TextView(context).apply {
                typeface = AndroidUtilities.bold()
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                text = LocaleController.getString(R.string.InuUpdate)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(23f), dp(16f), dp(23f), 0)
            })

            rows.add(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                text = LocaleController.formatString(
                    R.string.AppUpdateVersionAndSize,
                    appUpdate.version,
                    AndroidUtilities.formatFileSize(appUpdate.document.size),
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(23f), 0, dp(23f), dp(5f))
            })

            val changelog = UpdateHelper.getChangelog(appUpdate)
            rows.add(
                if (changelog != null && changelog.blocks.isNotEmpty()) {
                    RichMessageLayout.PreviewView(context, accountNum, null).apply {
                        set(changelog)
                        setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                    }
                } else {
                    TextView(context).apply {
                        setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                        text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty))
                        setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                    }
                },
            )
            return rows
        }
    }
}
