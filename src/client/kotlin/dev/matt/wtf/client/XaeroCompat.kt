package dev.matt.wtf.client

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

// Soft-dependency on Xaero's Minimap - no compile-time dependency, so the mod
// builds/runs fine without it installed. Everything routes through reflection
// behind isModLoaded(), gated again by try/catch as a safety net against
// internal Xaero API changes across versions.
object XaeroCompat {
    private val present: Boolean by lazy { FabricLoader.getInstance().isModLoaded("xaerominimap") }

    fun isPresent(): Boolean = present

    // ChatFormatting-style 16-color palette Xaero's Waypoint uses - not dye
    // colors, so map the shulker box's dye color down to the closest one.
    private val dyeToWaypointColor = mapOf(
        "white" to "WHITE", "orange" to "GOLD", "magenta" to "DARK_PURPLE",
        "light_blue" to "AQUA", "yellow" to "YELLOW", "lime" to "GREEN",
        "pink" to "RED", "gray" to "GRAY", "light_gray" to "GRAY",
        "cyan" to "DARK_AQUA", "purple" to "PURPLE", "blue" to "BLUE",
        "brown" to "DARK_RED", "green" to "DARK_GREEN", "red" to "RED", "black" to "BLACK"
    )

    // Undyed shulker_box is itself purple in vanilla.
    fun waypointColorNameFor(shulkerItemId: String): String {
        val path = shulkerItemId.substringAfter(':')
        if (path == "shulker_box") return "PURPLE"
        val dye = path.removeSuffix("_shulker_box")
        return dyeToWaypointColor[dye] ?: "PURPLE"
    }

    // Mirrors TemporaryWaypointHandler's internals (BuiltInHudModules.MINIMAP ->
    // current MinimapSession -> MinimapWorld -> its current WaypointSet) rather
    // than calling the public createTemporaryWaypoint, because that API doesn't
    // expose name/initials/color.
    //
    // targetDim: dimension the box actually lives in (e.g. "minecraft:the_nether").
    // When it differs from the dimension currently loaded client-side, the
    // waypoint is written into THAT dimension's own MinimapWorld/waypoint set
    // instead of the current one, so it shows up on the correct map layer
    // instead of misleadingly appearing in the dimension the player is in.
    fun addNamedTemporaryWaypoint(x: Int, y: Int, z: Int, name: String, initials: String, colorName: String, targetDim: String): Boolean {
        if (!present) return false
        return try {
            val builtInClass = Class.forName("xaero.hud.minimap.BuiltInHudModules")
            val hudModule = builtInClass.getField("MINIMAP").get(null)
            val session = hudModule.javaClass.getMethod("getCurrentSession").invoke(hudModule) ?: return false

            val worldClass = Class.forName("xaero.hud.minimap.world.MinimapWorld")
            val worldManager = session.javaClass.getMethod("getWorldManager").invoke(session)
            val currentWorld = worldManager.javaClass.getMethod("getCurrentWorld").invoke(worldManager) ?: return false

            val dimensionHelper = session.javaClass.getMethod("getDimensionHelper").invoke(session)
            val currentDimId = Minecraft.getInstance().level?.dimension()?.identifier()?.toString()
            val world = if (currentDimId == targetDim) {
                currentWorld
            } else {
                val pathClass = Class.forName("xaero.hud.path.XaeroPath")
                val getFullPath = worldClass.getMethod("getFullPath")
                val currentPath = getFullPath.invoke(currentWorld)
                val dimKey = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.parse(targetDim)
                )
                val dirName = dimensionHelper.javaClass.getMethod(
                    "getDimensionDirectoryName", net.minecraft.resources.ResourceKey::class.java
                ).invoke(dimensionHelper, dimKey) as String
                val targetPath = pathClass.getMethod("resolveSibling", String::class.java).invoke(currentPath, dirName)
                worldManager.javaClass.getMethod("getWorld", pathClass).invoke(worldManager, targetPath)
                    ?: worldManager.javaClass.getMethod("addWorld", pathClass).invoke(worldManager, targetPath)
                    ?: return false
            }

            // Waypoint goes straight into the target dimension's own
            // MinimapWorld, in that dimension's native local coords - same
            // as if placed while standing there, no scale conversion needed.
            val colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor")
            val color = colorClass.getField(colorName).get(null)
            val purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose")
            val purpose = purposeClass.getField("NORMAL").get(null)

            val waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint")
            val ctor = waypointClass.getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, String::class.java, colorClass, purposeClass,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            val waypoint = ctor.newInstance(x, y, z, name, initials, color, purpose, true, true)

            val waypointSet = worldClass.getMethod("getCurrentWaypointSet").invoke(world)
            waypointSet.javaClass.getMethod("add", waypointClass).invoke(waypointSet, waypoint)
            true
        } catch (e: Exception) {
            WTFClient.log("XaeroCompat.addNamedTemporaryWaypoint failed: $e")
            false
        }
    }
}
