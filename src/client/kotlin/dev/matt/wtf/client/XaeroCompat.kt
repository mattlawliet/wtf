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

    // Mirrors xaero.hud.minimap.controls.key.function.TemporaryWaypointFunction's
    // own onPress() call chain: BuiltInHudModules.MINIMAP -> current
    // MinimapSession -> WaypointSession -> TemporaryWaypointHandler, fed the
    // session's current MinimapWorld.
    fun addTemporaryWaypoint(x: Int, y: Int, z: Int): Boolean {
        if (!present) return false
        return try {
            val builtInClass = Class.forName("xaero.hud.minimap.BuiltInHudModules")
            val hudModule = builtInClass.getField("MINIMAP").get(null)
            val session = hudModule.javaClass.getMethod("getCurrentSession").invoke(hudModule) ?: return false

            val getWaypointSession = session.javaClass.getMethod("getWaypointSession")
            val waypointSession = getWaypointSession.invoke(session)
            val handler = waypointSession.javaClass.getMethod("getTemporaryHandler").invoke(waypointSession)

            val getWorldManager = session.javaClass.getMethod("getWorldManager")
            val worldManager = getWorldManager.invoke(session)
            val world = worldManager.javaClass.getMethod("getCurrentWorld").invoke(worldManager) ?: return false

            val worldClass = Class.forName("xaero.hud.minimap.world.MinimapWorld")
            val createTemporaryWaypoint = handler.javaClass.getMethod(
                "createTemporaryWaypoint", worldClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            createTemporaryWaypoint.invoke(handler, world, x, y, z)
            true
        } catch (e: Exception) {
            WTFClient.log("XaeroCompat.addTemporaryWaypoint failed: $e")
            false
        }
    }

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

    // Same call chain as addTemporaryWaypoint, but builds the Waypoint
    // ourselves (mirroring TemporaryWaypointHandler's internals - construct,
    // scale x/z by the dimension's coordinate scale, add to the world's
    // current WaypointSet) so name/initials/color can be set, which the
    // public createTemporaryWaypoint API doesn't expose.
    fun addNamedTemporaryWaypoint(x: Int, y: Int, z: Int, name: String, initials: String, colorName: String): Boolean {
        if (!present) return false
        return try {
            val builtInClass = Class.forName("xaero.hud.minimap.BuiltInHudModules")
            val hudModule = builtInClass.getField("MINIMAP").get(null)
            val session = hudModule.javaClass.getMethod("getCurrentSession").invoke(hudModule) ?: return false

            val worldClass = Class.forName("xaero.hud.minimap.world.MinimapWorld")
            val worldManager = session.javaClass.getMethod("getWorldManager").invoke(session)
            val world = worldManager.javaClass.getMethod("getCurrentWorld").invoke(worldManager) ?: return false

            val dimensionHelper = session.javaClass.getMethod("getDimensionHelper").invoke(session)
            val dimCoordScale = dimensionHelper.javaClass.getMethod("getDimCoordinateScale", worldClass)
                .invoke(dimensionHelper, world) as Double
            val dimTypeScale = Minecraft.getInstance().level?.dimensionType()?.coordinateScale() ?: 1.0
            val scale = dimTypeScale / dimCoordScale
            val scaledX = Math.floor(x * scale).toInt()
            val scaledZ = Math.floor(z * scale).toInt()

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
            val waypoint = ctor.newInstance(scaledX, y, scaledZ, name, initials, color, purpose, true, true)

            val waypointSet = worldClass.getMethod("getCurrentWaypointSet").invoke(world)
            waypointSet.javaClass.getMethod("add", waypointClass).invoke(waypointSet, waypoint)
            true
        } catch (e: Exception) {
            WTFClient.log("XaeroCompat.addNamedTemporaryWaypoint failed: $e")
            false
        }
    }
}
