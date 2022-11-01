package com.guncolony.mapAPI.building

import com.guncolony.mapAPI.api.Faction
import com.guncolony.mapAPI.api.LocationSpawnpoint
import com.guncolony.mapAPI.api.Spawnpoint
import com.guncolony.mapAPI.building.elements.*
import com.guncolony.mapAPI.region.*
import org.apache.commons.collections4.trie.PatriciaTrie
import org.bukkit.DyeColor
import org.bukkit.Location
import org.bukkit.World
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * The MapRegion implementation of the BuildMap
 */
class BuildMapRegion(val buildMap: BuildMap, val markers: ArrayList<MapMarker>) : UniversalMapRegion {
    override val initialSpawnpoint: Spawnpoint = LocationSpawnpoint(buildMap.spawnpoint)

    private val spawnpoints = HashMap<DyeColor, ArrayList<Spawnpoint>>().apply {
        for(marker in markers) {
            if(marker is SpawnpointMarker) {
                getOrPut(marker.color){ArrayList()} += marker.spawnpoint
            }
        }
    }
    override fun spawnpoints(faction: Faction): List<Spawnpoint> = spawnpoints[faction.color]?:listOf()

    override fun spawnpointsCompetitive(faction: Faction): List<Spawnpoint> = when (faction) {
        // Take cyan spawnpoints over light blue when they exist
        Faction.FactionBlue ->
            spawnpoints[DyeColor.CYAN]?.takeIf{it.isNotEmpty()}?:spawnpoints(faction)
        // Take orange spawnpoints over red when they exist
        Faction.FactionRed ->
            spawnpoints[DyeColor.ORANGE]?.takeIf{it.isNotEmpty()}?:spawnpoints(faction)
        else -> spawnpoints(faction)
    }

    override fun spawnpointsPush(faction: Faction, activeObjective: Int): List<Spawnpoint> {
        TODO("Not yet implemented")
    }

    override fun ctfFlagSpawnpoints(faction: Faction): List<Spawnpoint> {
        TODO("Not yet implemented")
    }

    override val areas: HashMap<String, HashMap<String, Area>> = run {
        // Load all objectives
        val areasRaw = HashMap<String, HashMap<String, ArrayList<Area>>>()
        for(marker in markers) {
            if(marker is ObjectiveMarker) {
                val modes = marker.mode.split("/")

                for(mode in modes) {
                    // Objectives without a letter are treated as if having the letter "A"
                    areasRaw.getOrPut(mode.trim()){HashMap()}
                        .getOrPut(marker.letter?.takeUnless{it.isBlank()}?:"A"){ArrayList()}
                        .add(marker.area)
                }
            }
        }

        // Convert multiple areas into UnionAreas and return the final map
        HashMap<String, HashMap<String, Area>>().apply {
            for((mode, modeObjs) in areasRaw) {
                this[mode] = HashMap<String, Area>().apply {
                    for((letter, areas) in modeObjs) {
                        if(areas.isNotEmpty())
                            put(letter, if(areas.size > 1) Areas.UnionArea(areas) else areas[0])
                    }
                }
            }
        }
    }

    override val gameplayAreas: ArrayList<GameplayArea> = ArrayList<GameplayArea>().apply {
        for(marker in markers) {
            if(marker is GameplayAreaMarker) {
                add(GameplayArea(marker.area, marker.flags))
            }
        }
    }

    override val mapName: String = buildMap.name
    override val world: World
        get() = buildMap.world
    override val meta: MapMeta
        get() = buildMap.meta
    override val mobSpawnpoints: HashMap<String, Location> get() {
        // Note that multiple spawnpoint markers can share one spawnpoint.
        // To make this compatible with the MobArena system which doesn't natively support this,
        // we add a "%" suffix followed by a number.
        // The MobArena side will ignore anything starting with the % suffix to
        // allow multiple spawnpoints to share the same name.
        val rawMap = HashMap<String, ArrayList<Location>>()
        for(marker in markers) {
            if(marker is MASpawnpointMarker) {
                rawMap.getOrPut(marker.mobArenaName){ArrayList()} += marker.armorStand.location.clone().apply{pitch=0f}
            }
        }
        val result = HashMap<String, Location>()
        for((key, spawnpoints) in rawMap) {
            for(i in spawnpoints.indices) {
                val spawnpoint = spawnpoints[i]
                result["$key%$i"] = spawnpoint
            }
        }
        return result
    }
    override val lobbyWarp: Spawnpoint = LocationSpawnpoint(buildMap.spawnpoint)
    override val arenaWarp: Spawnpoint
        get() = spawnpoints[DyeColor.LIGHT_BLUE]?.firstOrNull()?:lobbyWarp
    override val spectatorWarp: Spawnpoint
        get() = spawnpoints[DyeColor.RED]?.firstOrNull()?:lobbyWarp
}
