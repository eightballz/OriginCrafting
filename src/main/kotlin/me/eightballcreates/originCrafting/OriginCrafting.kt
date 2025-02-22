package me.eightballcreates.originCrafting

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.*
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class OriginCrafting : JavaPlugin(), Listener {

    val customRecipes = mutableMapOf<String, CustomRecipe>()
    private val recipeSlots = listOf(10, 11, 12, 19, 20, 21, 28, 29, 30)
    private val resultSlot = 25

    sealed class RecipeIngredient {
        data class Vanilla(val material: Material) : RecipeIngredient()
        data class CustomItem(val itemId: String) : RecipeIngredient()
    }

    sealed class RecipeResult {
        data class Vanilla(val material: Material) : RecipeResult()
        data class CustomItem(val itemId: String) : RecipeResult()
    }

    data class CustomRecipe(
        val values: Map<String, RecipeIngredient>,
        val layout: List<String>,
        val result: RecipeResult
    )

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        loadCustomRecipes()
        logger.info("Loaded ${customRecipes.size} custom recipes")
        RecipeBookGUI(this).also {
            server.pluginManager.registerEvents(it, this)
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (command.name.equals("origincrafting", ignoreCase = true)) {
            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                customRecipes.clear()
                loadCustomRecipes()
                sender.sendMessage("Recipes reloaded!")
                return true
            }
            sender.sendMessage("Usage: /origincrafting reload")
            return true
        }
        return false
    }

    private fun createCraftingGUI(): Inventory {
        val holder = CraftingTableHolder()
        val inventory = Bukkit.createInventory(holder, 45, "Crafting")
        holder.inv = inventory

        val glass = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta?.apply { setDisplayName(" ") }
        }

        for (i in 0 until 45) {
            inventory.setItem(i, glass)
        }

        recipeSlots.forEach { inventory.setItem(it, ItemStack(Material.AIR)) }

        inventory.setItem(resultSlot, ItemStack(Material.AIR))

        val recipeBook = ItemStack(Material.BOOK).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§eRecipe Book")
                lore = listOf("§7Click to view all recipes")
            }
        }
        inventory.setItem(43, recipeBook)

        return inventory
    }

    @EventHandler
    fun onCraftingTableInteract(event: PlayerInteractEvent) {
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.type == Material.CRAFTING_TABLE) {
            event.isCancelled = true
            event.player.openInventory(createCraftingGUI())
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.holder !is CraftingTableHolder) return
        if (event.clickedInventory != event.inventory) return

        when (event.rawSlot) {
            43 -> {
                event.isCancelled = true
                RecipeBookGUI(this).openRecipeBook(event.whoClicked as Player)
            }
            resultSlot -> handleResultSlotClick(event)
            in recipeSlots -> scheduleResultUpdate(event)
            else -> event.isCancelled = true
        }
    }

    private fun handleResultSlotClick(event: InventoryClickEvent) {
        val result = getCraftingResult(event.inventory) ?: run {
            event.isCancelled = true
            return
        }

        val cursor = event.cursor
        if (cursor == null || cursor.type == Material.AIR) {
            consumeIngredients(event.inventory)
            event.setCursor(result)
            event.inventory.setItem(resultSlot, ItemStack(Material.AIR))
            event.isCancelled = true
        } else {
            if (areItemsMatching(cursor, result) && cursor.amount < cursor.maxStackSize) {
                consumeIngredients(event.inventory)
                cursor.amount += 1
                event.inventory.setItem(resultSlot, ItemStack(Material.AIR))
                event.isCancelled = true
            } else {
                event.isCancelled = true
            }
        }
    }

    private fun areItemsMatching(a: ItemStack, b: ItemStack): Boolean {
        val aCustom = CustomStack.byItemStack(a)
        val bCustom = CustomStack.byItemStack(b)
        if (aCustom != null && bCustom != null) {
            return aCustom.namespacedID == bCustom.namespacedID
        }

        return a.isSimilar(b)
    }

    private fun scheduleResultUpdate(event: InventoryClickEvent) {
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            val result = getCraftingResult(event.inventory)
            event.inventory.setItem(resultSlot, result ?: ItemStack(Material.AIR))
        }, 1)
    }

    private fun consumeIngredients(inventory: Inventory) {
        recipeSlots.forEach { slot ->
            val item = inventory.getItem(slot)
            if (item != null && item.type != Material.AIR) {
                if (item.amount > 1) {
                    item.amount -= 1
                } else {
                    inventory.setItem(slot, ItemStack(Material.AIR))
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.holder !is CraftingTableHolder) return

        val player = event.player as Player
        val inventory = event.inventory

        recipeSlots.forEach { slot ->
            val item = inventory.getItem(slot)
            if (item != null && item.type != Material.AIR) {
                val leftover = player.inventory.addItem(item)
                if (!leftover.isEmpty()) {
                    leftover.values.forEach { leftoverItem ->
                        player.world.dropItem(player.location, leftoverItem)
                    }
                }
                inventory.setItem(slot, ItemStack(Material.AIR))
            }
        }

        inventory.setItem(resultSlot, ItemStack(Material.AIR))
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder !is CraftingTableHolder) return
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            val result = getCraftingResult(event.inventory)
            event.inventory.setItem(resultSlot, result ?: ItemStack(Material.AIR))
        }, 1)
    }

    private fun getCraftingResult(inventory: Inventory): ItemStack? {
        val items = recipeSlots.map { inventory.getItem(it) ?: ItemStack(Material.AIR) }

        return customRecipes.values.firstOrNull { recipe ->
            val recipePattern = recipe.layout.flatMap { row -> row.chunked(1).take(3) }.take(9)

            items.indices.all { index ->
                val patternChar = recipePattern.getOrNull(index) ?: return@all false
                val item = items[index]

                when {
                    patternChar == "Z" || patternChar == " " -> item.type == Material.AIR
                    else -> recipe.values[patternChar]?.let { ingredient ->
                        when (ingredient) {
                            is RecipeIngredient.Vanilla -> ingredient.material == item.type
                            is RecipeIngredient.CustomItem -> isMatchingItemsAdderItem(item, ingredient.itemId)
                        }
                    } ?: false
                }
            }
        }?.result?.let { result ->
            when (result) {
                is RecipeResult.Vanilla -> ItemStack(result.material)
                is RecipeResult.CustomItem -> getCustomItem(result.itemId)
            }
        }
    }

    private fun isMatchingItemsAdderItem(item: ItemStack, expectedId: String): Boolean {
        val customStack = CustomStack.byItemStack(item) ?: return false
        return customStack.namespacedID == expectedId
    }

    private fun getCustomItem(itemId: String): ItemStack? {
        return CustomStack.getInstance(itemId)?.itemStack
    }

    private fun loadCustomRecipes() {
        val recipesFile = File(dataFolder, "recipes.yml").apply {
            if (!exists()) saveResource("recipes.yml", false)
        }

        val config = YamlConfiguration.loadConfiguration(recipesFile)
        val recipesSection = config.getConfigurationSection("recipes") ?: return

        recipesSection.getKeys(false).forEach { key ->
            val recipeSection = recipesSection.getConfigurationSection(key) ?: return@forEach
            val values = parseIngredients(recipeSection.getConfigurationSection("values"))
            val layout = recipeSection.getStringList("recipe")
            val result = parseResult(recipeSection.getString("result"))

            if (values != null && result != null && layout.size == 3) {
                customRecipes[key] = CustomRecipe(values, layout, result)
                logger.info("Loaded recipe: $key")
            }
        }
    }

    private fun parseIngredients(section: ConfigurationSection?): Map<String, RecipeIngredient>? {
        if (section == null) return null
        val ingredients = mutableMapOf<String, RecipeIngredient>()
        for (key in section.getKeys(false)) {
            val valueRaw = section.getString(key) ?: continue
            val value = valueRaw.trim()
            when {
                value.startsWith("custom:", ignoreCase = true) -> {
                    ingredients[key] = RecipeIngredient.CustomItem(value.removePrefix("custom:").trim())
                }
                value.contains(":") -> {
                    if (value.startsWith("minecraft:", ignoreCase = true)) {
                        val vanillaName = value.substringAfter("minecraft:").trim()
                        try {
                            val material = Material.valueOf(vanillaName.uppercase())
                            ingredients[key] = RecipeIngredient.Vanilla(material)
                        } catch (e: IllegalArgumentException) {
                            logger.warning("Invalid material for ingredient '$key': '$value'. Ensure it's a valid vanilla material (e.g. minecraft:stone) or a valid ItemsAdder namespaced id.")
                        }
                    } else {
                        ingredients[key] = RecipeIngredient.CustomItem(value)
                    }
                }
                else -> {
                    try {
                        val material = Material.valueOf(value.uppercase())
                        ingredients[key] = RecipeIngredient.Vanilla(material)
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material for ingredient '$key': '$value'. Ensure it's a valid vanilla material name or a valid ItemsAdder namespaced id.")
                    }
                }
            }
        }
        return ingredients
    }

    private fun parseResult(valueRaw: String?): RecipeResult? {
        if (valueRaw == null) return null
        val value = valueRaw.trim()
        return when {
            value.startsWith("custom:", ignoreCase = true) -> {
                RecipeResult.CustomItem(value.removePrefix("custom:").trim())
            }
            value.contains(":") -> {
                if (value.startsWith("minecraft:", ignoreCase = true)) {
                    val vanillaName = value.substringAfter("minecraft:").trim()
                    try {
                        val material = Material.valueOf(vanillaName.uppercase())
                        RecipeResult.Vanilla(material)
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material for result: '$value'. Ensure it's a valid vanilla material (e.g. minecraft:stone) or a valid ItemsAdder namespaced id.")
                        null
                    }
                } else {
                    RecipeResult.CustomItem(value)
                }
            }
            else -> {
                try {
                    val material = Material.valueOf(value.uppercase())
                    RecipeResult.Vanilla(material)
                } catch (e: IllegalArgumentException) {
                    logger.warning("Invalid material for result: '$value'. Ensure it's a valid vanilla material name or a valid ItemsAdder namespaced id.")
                    null
                }
            }
        }
    }

    class CraftingTableHolder : InventoryHolder {
        var inv: Inventory? = null
        override fun getInventory(): Inventory {
            return inv ?: Bukkit.createInventory(this, 45, "Crafting")
        }
    }
}