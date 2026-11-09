package blbl.cat3399.feature.following

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.DynamicFeedItem
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemUpDynamicRichBinding
import blbl.cat3399.databinding.ItemVideoCardBinding

/**
 * UP 主动态 Tab 的混合 adapter：视频卡片 + 图文卡片混排。
 * 视频卡片复用 item_video_card.xml（精简绑定，不挂长按操作 overlay）。
 */
class UpDynamicAdapter(
    private val onVideoClick: (VideoCard) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<DynamicFeedItem>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<DynamicFeedItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<DynamicFeedItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun snapshot(): List<DynamicFeedItem> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DynamicFeedItem.Video -> TYPE_VIDEO
        is DynamicFeedItem.Rich -> TYPE_RICH
    }

    override fun getItemId(position: Int): Long = items[position].stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context).cloneInUserScale(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> VideoVh(
                ItemVideoCardBinding.inflate(inflater, parent, false),
                onVideoClick,
            )
            else -> RichVh(ItemUpDynamicRichBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DynamicFeedItem.Video -> (holder as VideoVh).bind(item.card)
            is DynamicFeedItem.Rich -> (holder as RichVh).bind(item)
        }
    }

    class VideoVh(
        private val binding: ItemVideoCardBinding,
        private val onClick: (VideoCard) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoCard) {
            binding.tvTitle.text = item.title
            val subtitle = if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            binding.tvSubtitle.text = subtitle
            binding.tvPubdate.text = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.llSubtitle.visibility =
                if (subtitle.isBlank() && binding.tvPubdate.text.isNullOrBlank()) View.GONE else View.VISIBLE

            binding.tvDuration.visibility = if (item.durationSec > 0) View.VISIBLE else View.GONE
            if (item.durationSec > 0) binding.tvDuration.text = Format.duration(item.durationSec)

            val vc = item.view?.takeIf { it > 0 }
            val dc = item.danmaku?.takeIf { it > 0 }
            binding.llStats.visibility = if (vc != null || dc != null) View.VISIBLE else View.GONE
            binding.ivStatPlay.visibility = if (vc != null) View.VISIBLE else View.GONE
            binding.tvView.visibility = if (vc != null) View.VISIBLE else View.GONE
            binding.ivStatDanmaku.visibility = if (dc != null) View.VISIBLE else View.GONE
            binding.tvDanmaku.visibility = if (dc != null) View.VISIBLE else View.GONE
            vc?.let { binding.tvView.text = Format.count(it) }
            dc?.let { binding.tvDanmaku.text = Format.count(it) }

            binding.tvChargeBadge.visibility = if (item.isChargingArc) View.VISIBLE else View.GONE
            if (item.isChargingArc) {
                binding.tvChargeBadge.text = binding.root.context.getString(R.string.badge_charging)
            }
            binding.tvProgressLeft.visibility = View.GONE
            binding.tvProgressTime.visibility = View.GONE
            binding.progressWatch.visibility = View.GONE
            binding.tvCoverLeftBottom.visibility = View.GONE
            binding.llActions.visibility = View.GONE

            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    class RichVh(
        private val binding: ItemUpDynamicRichBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DynamicFeedItem.Rich) {
            binding.tvRichText.text = item.text.ifBlank { "图文动态" }
            binding.rowRichImages.visibility =
                if (item.images.isEmpty()) View.GONE else View.VISIBLE
            val imgs = listOf(binding.ivRichImg1, binding.ivRichImg2, binding.ivRichImg3)
            imgs.forEachIndexed { idx, iv ->
                val url = item.images.getOrNull(idx)
                if (url != null) {
                    iv.visibility = View.VISIBLE
                    ImageLoader.loadInto(iv, url)
                } else {
                    iv.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_RICH = 1
    }
}
