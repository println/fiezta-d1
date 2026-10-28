package proto.media.fiezta.features.domain.phone.browser

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.ImageViewCompat
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.core.settings.SettingsStorage
import proto.media.fiezta.features.domain.core.bookmarks.BookmarkUtils
import proto.media.fiezta.features.domain.phone.settings.SettingsPhoneActivity
import proto.media.fiezta.features.domain.phone.warnings.WarningsPhoneActivity
import proto.media.fiezta.support.webviewex.WebViewEx
import proto.media.fiezta.support.webviewex.navigation.HostName
import io.realm.Realm

object BrowserMenuPopup {

    fun interface Listener {
        fun onShowBookmarks()
    }

    @JvmStatic
    fun show(context: Context, anchor: View, browser: WebViewEx<*>, hasPage: Boolean, listener: Listener) {
        val content = LayoutInflater.from(context).inflate(R.layout.popup_browser_menu, null)

        val navRow = content.findViewById<View>(R.id.menu_nav_row)
        val addBookmark = content.findViewById<View>(R.id.menu_add_bookmark)
        val openIn = content.findViewById<View>(R.id.menu_open_in)
        val desktopSite = content.findViewById<View>(R.id.menu_desktop_site)
        val navVisibility = if (hasPage) View.VISIBLE else View.GONE
        navRow.visibility = navVisibility
        addBookmark.visibility = navVisibility
        openIn.visibility = navVisibility
        desktopSite.visibility = navVisibility

        val menuWidth = (250 * context.resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(content, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.setBackgroundDrawable(ColorDrawable(0))
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 8 * context.resources.displayMetrics.density

        if (hasPage) {
            val back = content.findViewById<View>(R.id.menu_back)
            val forward = content.findViewById<View>(R.id.menu_forward)
            back.isEnabled = browser.canGoBack()
            back.alpha = if (browser.canGoBack()) 1f else 0.4f
            forward.isEnabled = browser.canGoForward()
            forward.alpha = if (browser.canGoForward()) 1f else 0.4f

            back.setOnClickListener {
                browser.goBack()
                popupWindow.dismiss()
            }
            forward.setOnClickListener {
                browser.goForward()
                popupWindow.dismiss()
            }
            content.findViewById<View>(R.id.menu_refresh).setOnClickListener {
                browser.reload()
                popupWindow.dismiss()
            }
            content.findViewById<View>(R.id.menu_share).setOnClickListener {
                popupWindow.dismiss()
                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = "text/plain"
                shareIntent.putExtra(Intent.EXTRA_TEXT, browser.url)
                context.startActivity(Intent.createChooser(shareIntent, null))
            }
            val isFavorite = isFavorite(browser.url)
            showFavoriteState(content.findViewById(R.id.menu_add_bookmark_icon), isFavorite)
            addBookmark.setOnClickListener {
                popupWindow.dismiss()
                if (isFavorite) BookmarkUtils.removeByUrl(browser.url) else BookmarkUtils.addBookmark(browser)
            }
            openIn.setOnClickListener {
                popupWindow.dismiss()
                val viewIntent = Intent(Intent.ACTION_VIEW)
                viewIntent.data = Uri.parse(browser.url)
                context.startActivity(Intent.createChooser(viewIntent, null))
            }

            val url = browser.url
            val host = HostName.of(url)
            val desktopSwitch = content.findViewById<SwitchCompat>(R.id.menu_desktop_site_switch)
            desktopSwitch.isChecked = SettingsStorage.isDesktopEnabledForHost(context, host)
            desktopSwitch.setOnCheckedChangeListener { _, isChecked ->
                SettingsStorage.setDesktopOverride(context, host, isChecked)
                browser.reload()
            }
        }

        content.findViewById<View>(R.id.menu_bookmarks).setOnClickListener {
            popupWindow.dismiss()
            listener.onShowBookmarks()
        }
        content.findViewById<View>(R.id.menu_warnings).setOnClickListener {
            popupWindow.dismiss()
            context.startActivity(Intent(context, WarningsPhoneActivity::class.java))
        }
        content.findViewById<View>(R.id.menu_settings).setOnClickListener {
            popupWindow.dismiss()
            context.startActivity(Intent(context, SettingsPhoneActivity::class.java))
        }

        popupWindow.showAsDropDown(anchor, 0, -anchor.height)
    }

    private fun isFavorite(url: String?): Boolean {
        val realm = Realm.getDefaultInstance()
        return try {
            BookmarkUtils.findByUrl(realm, url) != null
        } finally {
            realm.close()
        }
    }

    private fun showFavoriteState(icon: AppCompatImageView, isFavorite: Boolean) {
        if (isFavorite) {
            icon.setImageResource(R.drawable.ic_car_favorite_filled)
            ImageViewCompat.setImageTintList(icon, null)
        }
    }
}
