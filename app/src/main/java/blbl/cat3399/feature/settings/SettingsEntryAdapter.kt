package blbl.cat3399.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemSettingEntryBinding

class SettingsEntryAdapter(
    private val onClick: (SettingEntry) -> Unit,
) : RecyclerView.Adapter<SettingsEntryAdapter.Vh>() {
    private val items = ArrayList<SettingEntry>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<SettingEntry>) {
        val old = items.toList()
        val diff =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = old.size

                    override fun getNewListSize(): Int = list.size

                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return old.getOrNull(oldItemPosition)?.id == list.getOrNull(newItemPosition)?.id
                    }

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return old.getOrNull(oldItemPosition) == list.getOrNull(newItemPosition)
                    }
                },
            )
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    fun indexOfId(id: SettingId): Int = items.indexOfFirst { it.id == id }

    fun idAt(position: Int): SettingId? = items.getOrNull(position)?.id

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.id?.key?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSettingEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding, onClick)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Vh(
        private val binding: ItemSettingEntryBinding,
        private val onClick: (SettingEntry) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: SettingEntry? = null

        init {
            // 点击监听只挂一次，避免每次 bind 都新建 lambda。
            binding.root.setOnClickListener {
                val current = item ?: return@setOnClickListener
                if (current.enabled) onClick(current)
            }
        }

        fun bind(item: SettingEntry) {
            this.item = item
            binding.root.tag = item.id
            binding.root.isEnabled = item.enabled
            binding.root.alpha = if (item.enabled) 1f else 0.45f
            binding.tvTitle.text = item.title
            if (item.value.isBlank()) {
                binding.tvValue.visibility = android.view.View.GONE
                binding.tvValue.text = ""
            } else {
                binding.tvValue.visibility = android.view.View.VISIBLE
                binding.tvValue.text = item.value
            }
            if (item.desc.isNullOrBlank()) {
                binding.tvDesc.visibility = android.view.View.GONE
            } else {
                binding.tvDesc.visibility = android.view.View.VISIBLE
                binding.tvDesc.text = item.desc
            }
        }
    }
}
