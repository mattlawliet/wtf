package dev.matt.wtf.client

import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

data class ShulkerEntry(
    val id: String,
    val name: Component,
    val stack: ItemStack,
    val section: String,
    val location: String,
    val shortHash: String,
    val serial: Int,
    val items: List<ItemStack> = emptyList(),
    val cachedContentsNbt: ByteArray? = null,
    val matchPercent: Float = 0f,
    val from: String = "",
    val lastLocation: String? = null,
    val lastKnown: Boolean = false
)

fun fuzzyMatch(query: String, text: String): Boolean {
    if (query.isEmpty()) return true
    val q = query.lowercase()
    val t = text.lowercase()
    var qi = 0
    for (ti in t.indices) {
        if (t[ti] == q[qi]) {
            qi++
            if (qi == q.length) return true
        }
    }
    return false
}
