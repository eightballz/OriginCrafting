package me.eightballcreates.originCrafting

import dev.lone.itemsadder.api.CustomStack
import dev.lone.itemsadder.api.ItemsAdder.getCustomItem
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.*
import kotlin.collections.HashMap

class RecipeBookGUI(private val plugin: OriginCrafting) : Listener {

    private val recipesPerPage = 45
    private val playerPages = HashMap<UUID, Int>()
    private val recipeDetailViews = HashMap<UUID, String>()
    private val playerSearches = HashMap<UUID, String>()

    fun openRecipeBook(player: Player, page: Int = 0, searchQuery: String? = null) {
        val cleanQuery = searchQuery?.trim()?.takeIf { it.isNotEmpty() }
        playerPages[player.uniqueId] = if (cleanQuery != null) 0 else page
        cleanQuery?.let { playerSearches[player.uniqueId] = it } ?: playerSearches.remove(player.uniqueId)
        player.openInventory(createRecipeListGUI(playerPages[player.uniqueId] ?: 0, cleanQuery))
    }

    private fun createRecipeListGUI(page: Int, searchQuery: String?): Inventory {
        val allRecipes = plugin.customRecipes.entries.toList()
        val filteredRecipes = filterRecipes(allRecipes, searchQuery)
        val totalPages = (filteredRecipes.size + recipesPerPage - 1).coerceAtLeast(1) / recipesPerPage
        val adjustedPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        val invTitle = buildString {
            append("Recipe Book - Page ${adjustedPage + 1}/$totalPages")
            searchQuery?.let { append(" §8[§e$it§8]") }
        }

        val inv = Bukkit.createInventory(null, 54, invTitle)
        val startIdx = adjustedPage * recipesPerPage
        val endIdx = minOf(startIdx + recipesPerPage, filteredRecipes.size)

        filteredRecipes.subList(startIdx, endIdx).forEachIndexed { idx, entry ->
            getResultItem(entry.value.result)?.clone()?.apply {
                itemMeta = itemMeta?.apply {
                    lore = (lore ?: mutableListOf()).also {
                        it.add("§8RecipeKey: ${entry.key}")
                    }
                }
                inv.setItem(idx, this)
            } ?: inv.setItem(idx, createGlassPane())
        }

        inv.setItem(45, if (adjustedPage > 0) createNavArrow("Previous") else createGlassPane())
        inv.setItem(53, if (adjustedPage < totalPages - 1) createNavArrow("Next") else createGlassPane())
        inv.setItem(47, createSearchButton(searchQuery))

        (0 until 54).forEach { slot ->
            if (inv.getItem(slot) == null) {
                inv.setItem(slot, createGlassPane())
            }
        }

        return inv
    }

    private fun filterRecipes(
        recipes: List<Map.Entry<String, OriginCrafting.CustomRecipe>>,
        query: String?
    ): List<Map.Entry<String, OriginCrafting.CustomRecipe>> {
        if (query.isNullOrBlank()) return recipes

        val lowerQuery = query.lowercase()
        return recipes.filter { entry ->
            val result = entry.value.result
            val resultItem = getResultItem(result)

            listOfNotNull(
                resultItem?.itemMeta?.displayName?.lowercase(),      // Display name
                CustomStack.byItemStack(resultItem)?.displayName?.lowercase(), // ItemsAdder name
                entry.key.lowercase(),                               // Recipe key
                (result as? OriginCrafting.RecipeResult.CustomItem)?.itemId?.lowercase() // Custom item ID
            ).any { it?.contains(lowerQuery) == true }
        }
    }

    private fun createRecipeDetailGUI(recipeKey: String): Inventory {
        val recipe = plugin.customRecipes[recipeKey] ?: return Bukkit.createInventory(null, 54, "Invalid Recipe")
        val inv = Bukkit.createInventory(null, 54, "Recipe: $recipeKey")

        val gridSlots = listOf(10, 11, 12, 19, 20, 21, 28, 29, 30)
        recipe.layout.flatMap { it.toList() }
            .take(9)
            .forEachIndexed { index, char ->
                val ingredient = recipe.values[char.toString()] ?: return@forEachIndexed
                val item = when (ingredient) {
                    is OriginCrafting.RecipeIngredient.Vanilla -> ItemStack(ingredient.material)
                    is OriginCrafting.RecipeIngredient.CustomItem -> getCustomItem(ingredient.itemId)
                }
                inv.setItem(gridSlots[index], item?.clone())
            }

        inv.setItem(24, getResultItem(recipe.result)?.clone())

        inv.setItem(49, createBackButton())
        (0 until 54).forEach { slot ->
            if (!gridSlots.contains(slot) && slot != 24 && slot != 49) {
                inv.setItem(slot, createGlassPane())
            }
        }

        return inv
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        event.isCancelled = true

        when (event.view.title) {
            "Search Recipes" -> handleSearchGUI(event, player)
            else -> {
                when {
                    event.view.title.startsWith("Recipe Book") -> handleRecipeBookClick(event, player)
                    event.view.title.startsWith("Recipe: ") -> handleRecipeDetailClick(event, player)
                }
            }
        }
    }

    private fun handleSearchGUI(event: InventoryClickEvent, player: Player) {
        if (event.rawSlot == 2) { // Anvil result slot
            val searchText = event.inventory.getItem(0)?.itemMeta?.displayName ?: ""
            player.closeInventory()
            openRecipeBook(player, searchQuery = searchText)
        }
    }

    private fun handleRecipeBookClick(event: InventoryClickEvent, player: Player) {
        when (event.rawSlot) {
            45 -> navigatePage(player, -1)
            53 -> navigatePage(player, 1)
            47 -> handleSearchButtonClick(player, event.isShiftClick)
            else -> handleRecipeSelection(event, player)
        }
    }

    private fun navigatePage(player: Player, direction: Int) {
        val currentPage = playerPages[player.uniqueId] ?: 0
        openRecipeBook(player, currentPage + direction, playerSearches[player.uniqueId])
    }

    private fun handleSearchButtonClick(player: Player, isShiftClick: Boolean) {
        if (isShiftClick) {
            playerSearches.remove(player.uniqueId)
            openRecipeBook(player, 0)
        } else {
            openSearchGUI(player)
        }
    }

    private fun handleRecipeSelection(event: InventoryClickEvent, player: Player) {
        event.currentItem?.let { item ->
            item.itemMeta?.lore?.find { it.startsWith("§8RecipeKey: ") }?.let { loreLine ->
                val recipeKey = loreLine.substringAfter("§8RecipeKey: ")
                recipeDetailViews[player.uniqueId] = recipeKey
                player.openInventory(createRecipeDetailGUI(recipeKey))
            }
        }
    }

    private fun handleRecipeDetailClick(event: InventoryClickEvent, player: Player) {
        when (event.rawSlot) {
            49 -> openRecipeBook(player, playerPages[player.uniqueId] ?: 0, playerSearches[player.uniqueId])
            24 -> attemptCraft(player, event.view.title.substringAfter("Recipe: "))
        }
    }

    private fun attemptCraft(player: Player, recipeKey: String) {
        val recipe = plugin.customRecipes[recipeKey] ?: return
        if (hasRequiredItems(player, recipe)) {
            consumeIngredients(player, recipe)
            giveResultItem(player, recipe.result)
        } else {
            player.sendMessage("§cInsufficient materials!")
        }
    }

    private fun openSearchGUI(player: Player) {
        val inv = Bukkit.createInventory(player, InventoryType.ANVIL, "Search Recipes")
        inv.setItem(0, ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(playerSearches[player.uniqueId] ?: "Type to search...")
            }
        })
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        when (event.view.title) {
            "Search Recipes" -> handleSearchClose(event, player)
        }
    }

    private fun handleSearchClose(event: InventoryCloseEvent, player: Player) {
        val searchText = event.inventory.getItem(0)?.itemMeta?.displayName ?: ""
        if (searchText.isNotEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                openRecipeBook(player, searchQuery = searchText)
            }, 1)
        }
    }

    private fun formatMaterialName(material: Material): String {
        return material.name
            .lowercase()
            .replace("_", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun getResultItem(result: OriginCrafting.RecipeResult): ItemStack? {
        return when (result) {
            is OriginCrafting.RecipeResult.Vanilla -> {
                ItemStack(result.material).apply {
                    itemMeta = itemMeta?.apply {
                        if (!hasDisplayName()) {
                            setDisplayName("§f${formatMaterialName(result.material)}")
                        }
                    }
                }
            }
            is OriginCrafting.RecipeResult.CustomItem -> {
                CustomStack.getInstance(result.itemId)?.itemStack
            }
        }
    }

    private fun Material.formatName() = name.lowercase()
        .replace("_", " ")
        .replaceFirstChar { it.uppercase() }

    private fun createSearchButton(searchQuery: String?): ItemStack {
        return ItemStack(Material.COMPASS).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§aSearch Recipes")
                lore = listOf(
                    "§7Click to search",
                    "§7Shift+Click to clear",
                    searchQuery?.let { "§8Current: §e$it" } ?: "§8No active search"
                )
            }
        }
    }

    private fun createNavArrow(text: String): ItemStack {
        return ItemStack(Material.ARROW).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§f$text Page")
                lore = listOf("§7Click to navigate")
            }
        }
    }

    private fun createBackButton(): ItemStack {
        return ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§cBack to Recipes")
                lore = listOf("§7Click to return")
            }
        }
    }

    private fun createGlassPane(): ItemStack {
        return ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta?.apply { setDisplayName(" ") }
        }
    }

    private fun hasRequiredItems(player: Player, recipe: OriginCrafting.CustomRecipe): Boolean {
        val required = HashMap<String, Int>() // Format: "vanilla:MATERIAL" or "custom:ITEM_ID"

        recipe.layout.forEach { row ->
            row.forEach { char ->
                if (char == ' ' || char == 'Z') return@forEach
                val ingredient = recipe.values[char.toString()] ?: return@forEach

                when (ingredient) {
                    is OriginCrafting.RecipeIngredient.Vanilla -> {
                        val key = "vanilla:${ingredient.material.name}"
                        required[key] = required.getOrDefault(key, 0) + 1
                    }
                    is OriginCrafting.RecipeIngredient.CustomItem -> {
                        val key = "custom:${ingredient.itemId}"
                        required[key] = required.getOrDefault(key, 0) + 1
                    }
                }
            }
        }

        return required.all { (key, amount) ->
            player.inventory.contents.filterNotNull().sumBy { item ->
                when {
                    key.startsWith("vanilla:") ->
                        if (item.type.name == key.substringAfter("vanilla:")) item.amount else 0
                    key.startsWith("custom:") ->
                        CustomStack.byItemStack(item)?.namespacedID?.let {
                            if (it == key.substringAfter("custom:")) item.amount else 0
                        } ?: 0
                    else -> 0
                }
            } >= amount
        }
    }

    private fun consumeIngredients(player: Player, recipe: OriginCrafting.CustomRecipe) {
        val inventory = player.inventory.contents

        recipe.layout.forEach { row ->
            row.forEach { char ->
                if (char == ' ' || char == 'Z') return@forEach
                val ingredient = recipe.values[char.toString()] ?: return@forEach

                when (ingredient) {
                    is OriginCrafting.RecipeIngredient.Vanilla ->
                        consumeMaterial(inventory, ingredient.material)
                    is OriginCrafting.RecipeIngredient.CustomItem ->
                        consumeCustomItem(inventory, ingredient.itemId)
                }
            }
        }
    }

    private fun consumeMaterial(inventory: Array<ItemStack?>, material: Material) {
        var remaining = 1
        inventory.filterNotNull().forEach { item ->
            if (remaining > 0 && item.type == material) {
                val take = minOf(remaining, item.amount)
                item.amount -= take
                remaining -= take
            }
        }
    }

    private fun consumeCustomItem(inventory: Array<ItemStack?>, itemId: String) {
        var remaining = 1
        inventory.filterNotNull().forEach { item ->
            if (remaining > 0) {
                CustomStack.byItemStack(item)?.let {
                    if (it.namespacedID == itemId) {
                        val take = minOf(remaining, item.amount)
                        item.amount -= take
                        remaining -= take
                    }
                }
            }
        }
    }

    private fun giveResultItem(player: Player, result: OriginCrafting.RecipeResult) {
        getResultItem(result)?.let { item ->
            val leftover = player.inventory.addItem(item)
            if (!leftover.isEmpty()) {
                player.world.dropItem(player.location, leftover.values.first())
            }
        }
    }
}