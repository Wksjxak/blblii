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
    private val onRichClick: (DynamicFeedItem.Rich) -> Unit,
    private val onRichLongClick: (DynamicFeedItem.Rich) -> Unit,
    private val onRichImageClick: (DynamicFeedItem.Rich, Int) -> Unit,
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
            else -> RichVh(ItemUpDynamicRichBinding.inflate(inflater, parent, false), onRichClick, onRichLongClick, onRichImageClick)
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
        private val onClick: (DynamicFeedItem.Rich) -> Unit,
        private val onLongClick: (DynamicFeedItem.Rich) -> Unit,
        private val onImageClick: (DynamicFeedItem.Rich, Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DynamicFeedItem.Rich) {
            binding.tvRichTime.text = item.pubTs?.let { Format.pubDateText(it) }.orEmpty()
            val hasTitle = !item.title.isNullOrBlank()
            binding.tvRichTitle.visibility = if (hasTitle) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvRichTitle.text = item.title.orEmpty()
            if (item.text.isBlank()) {
                binding.tvRichText.visibility = android.view.View.GONE
            } else {
                binding.tvRichText.visibility = android.view.View.VISIBLE
                binding.tvRichText.text = item.text
                // 纯文动态（无标题）：正文即标题，加大加粗；有标题：正文小字
                if (hasTitle) {
                    binding.tvRichText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    binding.tvRichText.setTypeface(null, android.graphics.Typeface.NORMAL)
                } else {
                    binding.tvRichText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                    binding.tvRichText.setTypeface(null, android.graphics.Typeface.BOLD)
                }
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
            binding.rowRichImages.visibility =
                if (item.images.isEmpty()) View.GONE else View.VISIBLE
            val imgs = listOf(binding.ivRichImg1, binding.ivRichImg2, binding.ivRichImg3)
            imgs.forEachIndexed { idx, iv ->
                val url = item.images.getOrNull(idx)
                if (url != null) {
                    iv.visibility = View.VISIBLE
                    iv.isClickable = true
                    iv.isFocusable = true
                    ImageLoader.loadInto(iv, url)
                    iv.setOnClickListener { onImageClick(item, idx) }
                } else {
                    iv.visibility = View.GONE
                }
            }
            // 转发原动态摘要
            val origText = item.origText
            val origTitle = item.origTitle
            val origAuthor = item.origAuthor
            if (origText == null && origTitle == null && item.origCover == null) {
                binding.boxRichOrig.visibility = View.GONE
            } else {
                binding.boxRichOrig.visibility = View.VISIBLE
                val sb = StringBuilder()
                if (!origAuthor.isNullOrBlank()) sb.append("@").append(origAuthor).append("：")
                if (!origTitle.isNullOrBlank()) sb.append(origTitle)
                else if (!origText.isNullOrBlank()) sb.append(origText)
                binding.tvRichOrigText.text = sb.toString()
                if (!item.origCover.isNullOrBlank()) {
                    binding.ivRichOrigCover.visibility = View.VISIBLE
                    ImageLoader.loadInto(binding.ivRichOrigCover, item.origCover)
                } else {
                    binding.ivRichOrigCover.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_RICH = 1
    }
}
