package com.guncolony.multiserver.transfer

import com.google.common.cache.CacheBuilder
import com.guncolony.multiserver.Utils
import com.guncolony.multiserver.grid.GridServer
import com.guncolony.multiserver.grid.LocalServer
import com.guncolony.multiserver.networking.messages.MsgEntityDespawn
import com.guncolony.multiserver.networking.messages.MsgEntityTransferRequest
import com.guncolony.multiserver.networking.messages.MsgEntityTransferResponse
import com.guncolony.multiserver.networking.messages.MsgEntityTransfer
import com.guncolony.multiserver.replication.EntityReplication
import com.guncolony.multiserver.types.EntityLocationRotation
import de.tr7zw.nbtapi.NBTContainer
import de.tr7zw.nbtapi.NBTEntity
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Handles the process of transferring entities between servers.
 */
@Suppress("DuplicatedCode")
object EntityTransfer {
    private data class TransferSendData(val transferUUID: UUID, val entityUUID: UUID, val server: GridServer, val to: Location)

    /** Map containing pending transfers where we send an entity on this server to another server.
     * The key of the map is the Entity UUID, and the value of the map is the data.
     * Note that each entity can only have one active transfer request to another server.
     *
     * 1000 ms expiry to prevent entities from being sent to unresponsive servers. */
    private val transferSendMap = CacheBuilder.newBuilder().expireAfterWrite(1000, TimeUnit.MILLISECONDS)
        .build<UUID, TransferSendData>()

    /**
     * Initialize a request for an entity to be transferred to the server controlling the given location.
     *
     * Should be triggered on an entity movement or teleport event. This event should always be cancelled when calling
     * this transfer method.
     *
     * If this location is outside the grid, or the entity already has a pending transfer request, then nothing
     * will happen.
     */
    fun initiateTransfer(e: Entity, to: Location) {
        // If the location is outside grid, cancel
        if(!Utils.isWithinGrid(to)) return
        val server = Utils.getServerAt(to)
        if(!server.isValid && server is LocalServer) return

        val transferUUID = UUID.randomUUID()
        val entityUUID = e.uniqueId

        // If the entity already has a pending transfer, cancel
        if(transferSendMap.getIfPresent(entityUUID) != null) return

        // Send the message to the server
        server.sendMessage(MsgEntityTransferRequest(
            transferUUID,
            entityUUID,
            EntityLocationRotation(to),
            e.type
        ))

        // Record the pending transfer of this player
        transferSendMap.put(entityUUID, TransferSendData(transferUUID, entityUUID, server, to))

        Utils.log(3){"[EntityTransfer] Initializing a transfer attempt for Entity ${e.type.name} ($entityUUID) to $server" +
                " at location ${to.blockX},${to.blockY},${to.blockZ}"}
    }


    /**
     * Called when receiving a [MsgEntityTransferResponse], either a success that indicates we should now despawn
     * the entity and transfer it to the destination server via a [MsgEntityTransfer],
     * or a failure that indicates we should cancel the transfer.
     */
    fun receiveTransferResponse(msg: MsgEntityTransferResponse, sender: GridServer) = Utils.runOnMainThread {
        val (transferUUID, entityUUID, success) = msg

        // Check the transfer send cache if it has the correct data for the entity; invalidate the data if so
        // Return if data is incorrect, missing, or expired after 1000 ms
        val transferSendData = transferSendMap.getIfPresent(entityUUID)?:return@runOnMainThread
        if(transferSendData.transferUUID != transferUUID ||
            transferSendData.entityUUID != entityUUID ||
            transferSendData.server != sender) return@runOnMainThread
        transferSendMap.invalidate(entityUUID)

        // Get entity if still valid on the server
        val e = LocalServer.world.getEntity(entityUUID)?:return@runOnMainThread

        if(success) {
            // Transfer the entity
            transferEntity(e, transferSendData.to, sender, transferUUID)
        }
        else {
            Utils.log(3){"[EntityTransfer] Transfer attempt rejected by destination for transferring entity ${e.type.name} ($entityUUID) to $sender"}
        }
    }

    /**
     * Transfers the given entity to the destination server. Can either be called directly or through the transfer
     * request chain.
     */
    fun transferEntity(
        e: Entity,
        to: Location,
        server: GridServer = Utils.getServerAt(to),
        transferUUID: UUID = UUID.randomUUID()
    ) {
        if(!server.isValid && server is LocalServer) return
        val entityUUID = e.uniqueId

        // Construct the transfer message to make it reflect the current state of the entity
        val transferMessage = MsgEntityTransfer(transferUUID, e, EntityLocationRotation(to))

        // Despawn the entity
        e.remove()

        // Despawn the replicated display entity on neighboring servers as well
        EntityReplication.sendEntity(e){MsgEntityDespawn(e)}

        // Transfer the entity to the destination server
        server.sendMessage(transferMessage)

        Utils.log(3){"[EntityTransfer] Now transferring entity ${e.type.name} ($entityUUID) to $server"}
    }

    /**
     * Spawns this entity which was transferred from another server.
     */
    fun spawnTransferredEntity(msg: MsgEntityTransfer, sender: GridServer) = Utils.runOnMainThread {
        val (_, _, type, to, entityNBT) = msg

        val nbtNew = NBTContainer(entityNBT)
        val world = LocalServer.world
        val location = to.toLocation(world)

        // Create a new entity
        EntityReplication.isSpawningReplicatedEntity = true
        val newEntity = world.spawnEntity(location, type)
        EntityReplication.isSpawningReplicatedEntity = false

        // Update the data of the entity
        val nbt = NBTEntity(newEntity)
        val originalPos = nbt.getDoubleList("Pos")
        val originalUuid = nbt.getUUID("UUID")
        nbt.mergeCompound(nbtNew)
        // Restores the original UUID and intended location of the entity
        nbt.setUUID("UUID", originalUuid)
        nbt.getDoubleList("Pos").apply {
            set(0, originalPos[0])
            set(1, originalPos[1])
            set(2, originalPos[2])
        }

        Utils.log(3){"[EntityTransfer] Successfully spawned transferred entity ${newEntity.type.name} at (${location.blockX},${location.blockY},${location.blockZ}) using message sent from $sender"}
    }
}
