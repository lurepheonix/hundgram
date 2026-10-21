package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.DrawerMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class DrawerMenuOrderActivity : MenuOrderActivity<DrawerMenuConfig.Item>() {
    override val config get() = InuConfig.DRAWER_MENU_ITEMS
    override val headerStringRes = R.string.InuDrawerMenuItems
    override val resetStringRes = R.string.InuDrawerMenuReset

    override fun isToggleable(item: DrawerMenuConfig.Item): Boolean = item != DrawerMenuConfig.Item.SETTINGS

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuNavigationDrawer)
}
