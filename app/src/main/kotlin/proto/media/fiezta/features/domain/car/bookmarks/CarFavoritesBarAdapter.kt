package proto.media.fiezta.features.domain.car.bookmarks

import android.support.v4.content.ContextCompat
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.core.bookmarks.Bookmark
import proto.media.fiezta.features.domain.core.bookmarks.BookmarkMonogram
import proto.media.fiezta.features.domain.core.bookmarks.SiteColor
import proto.media.fiezta.features.domain.core.bookmarks.SiteColorStore

class CarFavoritesBarAdapter(private var bookmarks: List<Bookmark>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var onBookmarkSelected: (Bookmark) -> Unit = {}

    fun setOnBookmarkSelected(listener: (Bookmark) -> Unit) {
        onBookmarkSelected = listener
    }

    fun setBookmarks(bookmarks: List<Bookmark>) {
        this.bookmarks = bookmarks
    }

    override fun getItemCount(): Int = if (bookmarks.isEmpty()) EMPTY_PLACEHOLDER_COUNT else bookmarks.size

    override fun getItemViewType(position: Int): Int =
        if (bookmarks.isEmpty()) VIEW_TYPE_PLACEHOLDER else VIEW_TYPE_BOOKMARK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.bookmark_monogram_car, parent, false)
        return if (viewType == VIEW_TYPE_PLACEHOLDER) PlaceholderViewHolder(view) else MonogramViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MonogramViewHolder) {
            val bookmark = bookmarks[position]
            holder.bind(bookmark)
            holder.itemView.setOnClickListener { onBookmarkSelected(bookmark) }
        }
    }

    private class MonogramViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val monogram: TextView = itemView.findViewById(R.id.bookmark_monogram)

        fun bind(bookmark: Bookmark) {
            val initials = BookmarkMonogram.of(bookmark.title, bookmark.url)
            monogram.text = initials
            val fillColor = fillColorOf(bookmark.url)
            monogram.background = CarMonogramBackground.create(itemView.context, fillColor)
            val initialsColor = if (SiteColor.needsDarkInitials(fillColor)) R.color.brandBackground else android.R.color.white
            monogram.setTextColor(ContextCompat.getColor(itemView.context, initialsColor))
            itemView.isFocusable = true
            itemView.isClickable = true
        }

        private fun fillColorOf(url: String?): Int {
            SiteColorStore.colorOf(itemView.context, url)?.let { return it }
            val palette = itemView.resources.getIntArray(R.array.car_favorite_monogram_palette)
            return palette[SiteColor.fallbackIndex(SiteColor.domainOf(url), palette.size)]
        }
    }

    private class PlaceholderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            val monogram = itemView.findViewById<TextView>(R.id.bookmark_monogram)
            monogram.text = ""
            monogram.setBackgroundResource(R.drawable.car_favorite_placeholder_background)
            itemView.isFocusable = false
            itemView.isClickable = false
        }
    }

    companion object {
        private const val VIEW_TYPE_BOOKMARK = 0
        private const val VIEW_TYPE_PLACEHOLDER = 1
        private const val EMPTY_PLACEHOLDER_COUNT = 3
    }
}
