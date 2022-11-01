package com.guncolony.multiserver.grid

import com.google.gson.JsonPrimitive
import com.guncolony.multiserver.config.GridConfig
import com.guncolony.multiserver.networking.NetworkMessage
import com.guncolony.multiserver.networking.NetworkMessagePlayForwardable
import org.bukkit.Bukkit
import org.bukkit.Location
import java.lang.IllegalArgumentException
import kotlin.math.floor

/**
 * Represents a server on the grid. It can be the local server, a neighbor, or a faraway server.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
sealed class GridServer {
    /** Represents the grid X coordinate of the server. */
    abstract val gridX: Int

    /** Represents the grid Z coordinate of the server. */
    abstract val gridZ: Int

    /** The BungeeCord server name of this server, if it is valid. Is used to send a player to this server.
     *
     * Note that this server name is currently hardcoded to the "grid-X-Z" format. */
    val bungeeName: String inline get() = "grid-$gridX-$gridZ"

    /** The minimum Minecraft region X coordinate region for this server (inclusive) */
    val minRegionX: Int inline get() = gridX * GridConfig.regionsPerServer
    /** The maximum Minecraft region X coordinate region for this server (non-inclusive) */
    val maxRegionX: Int inline get() = (gridX + 1) * GridConfig.regionsPerServer
    /** The minimum Minecraft region Z coordinate region for this server (inclusive) */
    val minRegionZ: Int inline get() = gridZ * GridConfig.regionsPerServer
    /** The maximum Minecraft region Z coordinate for this server (non-inclusive) */
    val maxRegionZ: Int inline get() = (gridZ + 1) * GridConfig.regionsPerServer
    /** The minimum chunk X coordinate for this server (inclusive) */
    val minChunkX: Int inline get() = gridX * GridConfig.chunksPerServer
    /** The maximum chunk X coordinate for this server (non-inclusive) */
    val maxChunkX: Int inline get() = (gridX + 1) * GridConfig.chunksPerServer
    /** The minimum chunk Z coordinate for this server (inclusive) */
    val minChunkZ: Int inline get() = gridZ * GridConfig.chunksPerServer
    /** The maximum chunk Z coordinate for this server (non-inclusive) */
    val maxChunkZ: Int inline get() = (gridZ + 1) * GridConfig.chunksPerServer
    /** The minimum block X coordinate for this server (inclusive) */
    val minX: Int inline get() = gridX * GridConfig.blocksPerServer
    /** The maximum block X coordinate for this server (non-inclusive) */
    val maxX: Int inline get() = (gridX + 1) * GridConfig.blocksPerServer
    /** The minimum block Z coordinate for this server (inclusive) */
    val minZ: Int inline get() = gridZ * GridConfig.blocksPerServer
    /** The maximum block Z coordinate for this server (non-inclusive) */
    val maxZ: Int inline get() = (gridZ + 1) * GridConfig.blocksPerServer

    /** The difference in grid X coordinate of this server minus that of the local server */
    open val relativeGridX: Int get() = gridX - LocalServer.gridX
    /** The difference in grid Z coordinate of this server minus that of the local server */
    open val relativeGridZ: Int get() = gridX - LocalServer.gridX

    /** Whether this server is within bounds */
    val isValid: Boolean inline get() = gridX in GridConfig.minGridX until GridConfig.maxGridX &&
                                        gridZ in GridConfig.minGridZ until GridConfig.maxGridZ

    /** The world name simulated by MultiServer on this server */
    val worldName inline get() = GridConfig.worldName
    /** The world simulated by MultiServer on this server */
    val world by lazy { Bukkit.getWorld(worldName)!!}
    /** The view distance of the world. Determines how many chunks from the boundary of another server
     * should be sent to the server */
    val viewDistance by lazy {world.viewDistance}

    /** Returns whether this chunk is inside the area simulated by this server */
    fun isChunkInside(chunkX: Int, chunkZ: Int): Boolean {
        return (chunkX in minChunkX until maxChunkX && chunkZ in minChunkZ until maxChunkZ)
    }
    /** Returns whether this chunk is outside but near enough to this server, such that a chunk should be sent
     * from a neighbor server to this server to be displayed. */
    fun isChunkOutsideButNear(chunkX: Int, chunkZ: Int): Boolean {
        return !(chunkX in minChunkX until maxChunkX && chunkZ in minChunkZ until maxChunkZ) &&
                chunkX in (minChunkX - viewDistance) until (maxChunkX + viewDistance) &&
                chunkZ in (minChunkZ - viewDistance) until (maxChunkZ + viewDistance)
    }

    /** Returns whether this block is inside the area simulated by this server */
    fun isBlockInside(x: Int, z: Int): Boolean {
        return (x in minX until maxX && z in minZ until maxZ)
    }
    /** Returns whether this block is outside but near enough to this server, such that it should be sent
     * from a neighbor server to this server to be displayed. */
    fun isBlockOutsideButNear(x: Int, z: Int): Boolean {
        return isChunkOutsideButNear(x.shr(4), z.shr(4))
    }

    /** Returns whether this location is inside the area simulated by this server */
    fun isInside(location: Location): Boolean {
        return location.world.name == worldName &&
                location.x >= minX && location.x < maxX &&
                location.z >= minZ && location.z < maxZ
    }
    /** Returns whether this location is outside but near enough to this server, such that something here should be sent
     * from a neighbor server to this server to be displayed. */
    fun isOutsideButNear(location: Location): Boolean {
        return isChunkOutsideButNear(floor(location.x).toInt().shr(4), floor(location.z).toInt().shr(4))
    }



    /**
     * Send a network message to this server. Thread safe.
     *
     * @param message the message to send
     * @param flush if true, send as soon as possible. If false, do not send over the network yet, so multiple messages
     * can be sent together in one network packet.
     *
     * @throws IllegalArgumentException if `this` is a FarawayServer and the message is not a
     * [NetworkMessagePlayForwardable]
     * @throws UnsupportedOperationException if `this` is a LocalServer (the message handler requires that all messages
     * are directly sent by a NeighborServer)
     */
    fun sendMessage(message: NetworkMessage, flush: Boolean = true) {
        if(message is NetworkMessagePlayForwardable) {
            // Add the destination server to the JSON, denoted by uppercase "X", "Z"
            val json = message.toJson()
            json.add("X", JsonPrimitive(gridX))
            json.add("Z", JsonPrimitive(gridZ))
            val messageString = NetworkMessage.gson.toJson(json)

            when(this) {
                is NeighborServer -> this.networking.sendMessage(messageString, flush)
                is FarawayServer -> this.neighborForNetworking.networking.sendMessage(messageString, flush)
                is LocalServer -> throw UnsupportedOperationException("You may not send a message to the LocalServer!")
            }
        }
        else {
            when(this) {
                is NeighborServer -> this.networking.sendMessage(message.toString(), flush)
                is FarawayServer ->
                    throw IllegalArgumentException("You may not send a non-forwardable message to a FarawayServer!")
                is LocalServer -> throw UnsupportedOperationException("You may not send a message to the LocalServer!")
            }
        }
    }

    /**
     * Send all pending messages to this server, or to the neighbor server that should be used to reach this server.
     * Thread safe.
     *
     * Pending messages are created by calling [sendMessage] with `flush = false`.
     *
     * Will not do anything if this server is the [LocalServer], so this method should never create an exception.
     */
    fun sendPendingMessages() {
        when(this) {
            is NeighborServer -> this.networking.sendPendingMessages()
            is FarawayServer -> this.neighborForNetworking.networking.sendPendingMessages()
            is LocalServer -> {}
        }
    }
}
