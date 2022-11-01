package com.guncolony.client.networking.asset

import com.guncolony.common.networking.asset.AssetRegistry
import com.guncolony.common.networking.asset.AssetSystem
import com.guncolony.common.networking.messages.MessageSCAsset
import com.guncolony.util.AssetId
import kotlinx.coroutines.channels.Channel

/**
 * The client's implementation of [AssetSystem]. It receives and stores assets from the network.
 *
 * The client is unable to request assets from the server; all assets that it needs should be provided by the server by
 * "pushing" assets to the client through [MessageSCAsset].
 */
object AssetSystemClient : AssetSystem {
    /**
     * Stores all assets currently loaded by this server.
     *
     * The outer array always contains 256 IntMaps indexed by `AssetId.type`, and these references will never change.
     * The inner values are concurrent maps indexed by `AssetId.subtype`.
     *
     * Thus, an asset could be obtained with `loadedAssets[ type ][ subtype ]`, and assets can be loaded
     * using `loadedAssets[ type ].put(subType, value)`.
     * This access pattern is always thread-safe.
     */
    val loadedAssets: Array<HashMap<Long, Any>> =
        Array(256){HashMap()}

    /**
     * Stores tasks that are waiting for an asset to load. The map is keyed by the *entire* asset ID.
     *
     * Each value is a waiting list containing channels.
     * When the asset is loaded, each channel should be sent a reference to the asset, and when asset loading fails,
     * each channel should be sent the `null` value.
     * These waiting lists **must be manually synchronized on** when being accessed.
     */
    private val tasksWaitingForAsset = HashMap<Long, Channel<Channel<Any?>>>()

    /**
     * An array containing an [AssetRegistry] for each asset type. The value will be null for asset types that are
     * unrecognized/unregistered by the server.
     */
    private val registries: Array<AssetRegistry<out Any>?> = Array(256){null}

    /** Puts a freshly-loaded asset into the registry and send it to any tasks waiting for it.
     *
     * Note that sending it a null value will indicate that the asset loading has failed. */
    private fun loadAsset(id: AssetId, asset: Any?) {
        if(asset != null) loadedAssets[id.type.toInt()][id.subtype] = asset
        println("[Assets] Loaded asset with AssetId: ${id.type}, ${id.subtype}")

        // Send this asset to any tasks waiting for it
        // Also, clear the waiting list by removing it from the map & clearing the list itself just in case
        tasksWaitingForAsset.remove(id.value.toLong())?.let {tasks ->
            // The waiting list is a channel. We take all the sub-channels out of the channel and send each one an asset
            while(true) {
                val task = tasks.tryReceive().getOrNull()?:break
                task.trySend(asset)
            }
        }
    }

    /**
     * Contains the data of incoming asset messages that have not been fully assembled.
     *
     * Once an asset is fully assembled, it will be removed from this map and added to [loadedAssets] through the
     * [loadAsset] function.
     */
    private val pendingAssetPieces = HashMap<Long, PendingAsset>()

    /** Used to store a pending message that we do not have all the pieces of. */
    private class PendingAsset(val pieces: Array<ByteArray?>, var piecesCount: Int)

    /**
     * Called when receiving an asset message from the server, containing the data of an asset fragment.
     *
     * This method will always trust the message from the server, storing the fragment in
     * [pendingAssetPieces]. Once all fragments have been received, it will call the [loadAsset] function
     * to load the asset.
     *
     * If the message contains the entire asset (i.e. `numFragments = 1`), then we directly load the asset using the
     * [loadAsset] function.
     */
    fun receiveAssetMessage(message: MessageSCAsset) {
        val id = message.id
        val fragment = message.fragment.toInt()
        val numFragments = message.numFragments.toInt()
        val data = message.data

        // If the message contains the entire asset, load it now
        if(numFragments == 1) {
            loadAsset(id, registries[id.type.toInt()]!!.deserialize(id.subtype, data))
            return
        }
        // Get fragment array. If we didn't find an array, or it's the wrong length, restart with a new array
        var pending = pendingAssetPieces[id.value.toLong()]
        if(pending == null || pending.pieces.size != numFragments) {
            pending = PendingAsset(Array(numFragments){null}, 0)
            pendingAssetPieces[id.value.toLong()] = pending
        }
        // Add this piece of the asset
        if(pending.pieces[fragment] == null) {
            pending.pieces[fragment] = data
            pending.piecesCount += 1
        }
        // If the asset is fully assembled, then we load it
        if(pending.piecesCount == numFragments) {
            // Assemble the complete data of the asset
            val size = pending.pieces.sumOf{it!!.size}
            val completeData = ByteArray(size)
            var i = 0
            for(piece in pending.pieces) {
                piece!!.copyInto(completeData, i)
                i += piece.size
            }
            // Load this asset
            loadAsset(id, registries[id.type.toInt()]!!.deserialize(id.subtype, completeData))
            // Remove the pending asset from the map
            pendingAssetPieces.remove(id.value.toLong())
        }
    }

    override fun getRegistry(type: UShort): AssetRegistry<out Any>? = registries[type.toInt()]

    override fun addRegistry(registry: AssetRegistry<out Any>) {
        registries[registry.type.toInt()] = registry
    }

    override fun get(id: AssetId): Any? = loadedAssets[id.type.toInt()][id.subtype]

    override suspend fun waitFor(id: AssetId): Any {
        // If asset is already loaded, return it
        loadedAssets[id.type.toInt()][id.subtype]?.let{return it}

        // The waiting list should be initialized as a channel with unlimited capacity, if it doesn't already exist
        val waitingList = tasksWaitingForAsset.getOrPut(id.value.toLong()){Channel(Channel.UNLIMITED)}

        // Make a channel and add it to the waiting list
        // When the asset is loaded, it will be sent to all channels in the waiting list, and we can return it
        val channel: Channel<Any?> = Channel(1)
        waitingList.send(channel)
        println("[Assets] We are now waiting for asset with AssetId ${id.type}, ${id.subtype}")

        return channel.receive()
            ?:error("Asset loading for AssetId ${id.type}, ${id.subtype} has failed while waiting for asset.")
    }

    /**
     * Waits for a singleton asset with the given subtype, then casts it to ByteArray
     */
    suspend fun waitForSingleton(subtype: Long): ByteArray {
        return waitFor(AssetId(0u, subtype)) as ByteArray
    }
}
