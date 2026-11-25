package blbl.cat3399.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import blbl.cat3399.R
import blbl.cat3399.core.ui.ThemeColor
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemSettingsLeftBinding

class SettingsLeftAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SettingsLeftAdapter.Vh>() {
    private val items = ArrayList<String>()
    private var selected = 0

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<String>, selected: Int) {
        items.clear()
        items.addAll(list)
        this.selected = selected.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        if (position !in items.indices || position == selected) return
        val oldSelected = selected
        selected = position
        if (oldSelected in items.indices) notifyItemChanged(oldSelected)
        notifyItemChanged(position)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSettingsLeftBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = Vh(binding)
        // 点击只挂一次；位置在点击时现取，避免复用后绑到旧位置。
        binding.root.setOnClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION) onClick(clickedPosition)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], position == selected)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSettingsLeftBinding) : RecyclerView.ViewHolder(binding.root) {
        /** 选中底色与主题相关，只在首次 bind 时解析一次。 */
        private var accentContainerColor: Int? = null

        fun bind(text: String, selected: Boolean) {
            binding.tvTitle.text = text
            binding.root.isSelected = selected
            val ctx = binding.root.context
            val accent =
                accentContainerColor
                    ?: ThemeColor.resolve(
                        context = ctx,
                        attr = R.attr.blblAccentContainer,
                        fallbackRes = R.color.blbl_accent_violet_container,
                    ).also { accentContainerColor = it }
            binding.root.setCardBackgroundColor(if (selected) accent else 0x00000000)
        }
    }
}
