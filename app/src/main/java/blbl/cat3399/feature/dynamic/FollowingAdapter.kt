package blbl.cat3399.feature.dynamic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemFollowingBinding
import com.google.android.material.R as MaterialR

class FollowingAdapter(
    private val onClick: (FollowingUi) -> Unit,
) : RecyclerView.Adapter<FollowingAdapter.Vh>() {
    data class FollowingUi(
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
        val showRecentUpdateDot: Boolean = false,
        val isAll: Boolean = false,
    )

    private val items = ArrayList<FollowingUi>()
    private var selectedMid: Long = MID_ALL

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<FollowingUi>, selected: Long = MID_ALL) {
        val prevSelected = selectedMid
        val sameItems =
            items.size == list.size &&
                items.indices.all { idx -> items[idx] == list[idx] }

        selectedMid = selected
        if (sameItems) {
            val prevPos = items.indexOfFirst { it.mid == prevSelected }
            val newPos = items.indexOfFirst { it.mid == selectedMid }
            if (prevPos >= 0) notifyItemChanged(prevPos)
            if (newPos >= 0 && newPos != prevPos) notifyItemChanged(newPos)
            return
        }

        // 用 DiffUtil 局部刷新：搜索时列表频繁变化，全量 notifyDataSetChanged
        // 会把可见行全部重绑（头像重新加载/解码），在 TV 上很吃性能。
        val old = ArrayList(items)
        items.clear()
        items.addAll(list)
        DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = old[oldPos].mid == list[newPos].mid
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = old[oldPos] == list[newPos]
                override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                    val a = old[oldPos]
                    val b = list[newPos]
                    // 名字/头像没变，只是红点或选中态变化 -> 只刷这部分，不动头像
                    return if (a.name == b.name && a.avatarUrl == b.avatarUrl && a.isAll == b.isAll) PAYLOAD_STATE else null
                }
            },
            true,
        ).dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = items[position].mid

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemFollowingBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) =
        holder.bind(items[position], items[position].mid == selectedMid, onClick)

    override fun onBindViewHolder(holder: Vh, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.contains(PAYLOAD_STATE).not()) {
            onBindViewHolder(holder, position)
            return
        }
        holder.bindState(items[position], items[position].mid == selectedMid)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemFollowingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FollowingUi, selected: Boolean, onClick: (FollowingUi) -> Unit) {
            binding.tvName.text = item.name
            val ctx = binding.root.context
            val primaryText = ThemeColor.resolve(ctx, MaterialR.attr.colorOnSurface, blbl.cat3399.R.color.blbl_text)
            if (item.isAll) {
                binding.ivAvatar.setImageResource(blbl.cat3399.R.drawable.ic_all)
                binding.ivAvatar.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        primaryText,
                    )
            } else {
                binding.ivAvatar.imageTintList = null
                ImageLoader.loadInto(binding.ivAvatar, ImageUrl.avatar(item.avatarUrl))
            }
            bindState(item, selected)
            binding.root.setOnClickListener { onClick(item) }
        }

        fun bindState(item: FollowingUi, selected: Boolean) {
            binding.vAvatarUpdateDot.isVisible = !item.isAll && item.showRecentUpdateDot
            binding.vSelected.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.isSelected = selected
            val ctx = binding.root.context
            val primaryText = ThemeColor.resolve(ctx, MaterialR.attr.colorOnSurface, blbl.cat3399.R.color.blbl_text)
            val secondaryText = ThemeColor.resolve(ctx, android.R.attr.textColorSecondary, blbl.cat3399.R.color.blbl_text_secondary)
            binding.tvName.setTextColor(if (selected) primaryText else secondaryText)
        }
    }

    companion object {
        const val MID_ALL: Long = -1L
        private const val PAYLOAD_STATE = "state"
    }
}
