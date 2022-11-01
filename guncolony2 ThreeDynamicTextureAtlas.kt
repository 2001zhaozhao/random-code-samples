package com.guncolony.client.render.texture

import com.guncolony.client.MainJS
import com.guncolony.common.minecraft.model.parser.ParsedTexture
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Image
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import three.js.CanvasTexture
import three.js.Texture
import three.js.Vector2
import kotlin.js.Promise

/**
 * The Three.js implementation of a dynamic texture atlas.
 *
 * @see DynamicTextureAtlas
 */
@Suppress("UnsafeCastFromDynamic", "MemberVisibilityCanBePrivate")
class ThreeDynamicTextureAtlas(
    numAtlasSectionsX: Int, numAtlasSectionsY: Int
) : DynamicTextureAtlas(numAtlasSectionsX, numAtlasSectionsY) {

    /**
     * The canvas rendering context used to modify the texture.
     */
    val canvasContext: CanvasRenderingContext2D =
        js("document.createElement('canvas').getContext('2d');").unsafeCast<CanvasRenderingContext2D>()
            .apply {
                // Fill with a pink background at first
                canvas.width = atlasTextureSizeX
                canvas.height = atlasTextureSizeY
                fillStyle = "#F0F"
                fillRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
                // Black checkerboard
                fillStyle = "#000"
                for(x in 0 until (atlasTextureSizeX / 8)) {
                    for(y in 0 until (atlasTextureSizeY / 8)) {
                        if((x + y) % 2 == 0)
                            fillRect(x * 8.0, y * 8.0, 8.0, 8.0)
                    }
                }
            }

    /**
     * The Three.js atlas texture image.
     *
     * At atlas creation, it is initialized with an empty image.
     */
    val atlasTexture: CanvasTexture = CanvasTexture(canvasContext.canvas).apply {
        flipY = false
        generateMipmaps = false
        // Nearest texture filter (disable mipmapping)
        @Suppress("UNUSED_VARIABLE")
        val three = js("require('three')")
        minFilter = js("three.NearestFilter")
        magFilter = js("three.NearestFilter")
        anisotropy = 0
    }

    // Calling raw js code causes errors in a suspend function, so it needs to be here instead
    @Suppress("UNUSED_PARAMETER")
    private fun getBlobOfData(data: Uint8Array): Blob {
        return js("new Blob( [data], { type: 'image/png' })").unsafeCast<Blob>()
    }

    override suspend fun addTextureToAtlasImage(texture: ParsedTexture, x: Int, y: Int) {
        // Copy the texture data into a Uint8Array
        val pngData = texture.pngData
        val data = Uint8Array(texture.pngData.size)
        for(i in pngData.indices) data[i] = pngData[i]

        // Load PNG image with the resource blob workaround. (png libraries do not like Kotlin.js)
        // https://stackoverflow.com/questions/9463981/displaying-a-byte-array-as-an-image-using-javascript
        // https://stackoverflow.com/questions/39062595/how-can-i-create-a-png-blob-from-binary-data-in-a-typed-array
        // https://stackoverflow.com/questions/9421202/how-do-i-make-an-image-load-synchronously
        val blob: Blob = getBlobOfData(data)
        val imageUrl = URL.createObjectURL(blob)

        // Draw the image into canvas
        val image = Image()
        // Suspend until the image gets loaded by the browser
        val imageLoadPromise = Promise { resolve, _ ->
            image.onload = {
                resolve(true)
            }
        }
        image.src = imageUrl

        canvasContext.clearRect(x.toDouble(), y.toDouble(),
            texture.pngSize.toDouble(), texture.pngSize.toDouble())
        imageLoadPromise.await()

        // Create a canvas just for this texture
        val imageTexture = Texture(image)
        // Copy this texture into the atlas using a fast method that only sends the changed part to GPU
        MainJS.three.renderer.copyTextureToTexture(Vector2(x, y), imageTexture, atlasTexture)
    }
}
