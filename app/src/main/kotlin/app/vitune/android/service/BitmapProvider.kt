package app.vitune.android.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import app.vitune.android.utils.thumbnail
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

class BitmapProvider(
    private val getBitmapSize: () -> Int,
    private val getColor: (isDark: Boolean) -> Int,
    private val context: Context
) {
    @set:Synchronized
    var lastUri: Uri? = null
        private set

    @set:Synchronized
    private var lastBitmap: Bitmap? = null
        get() = field?.takeUnless { it.isRecycled }
        set(value) {
            field = value
            notifyListeners(value)
        }
    private var lastIsSystemInDarkMode = false
    private var currentTask: Disposable? = null

    private lateinit var defaultBitmap: Bitmap
    val bitmap get() = lastBitmap ?: defaultBitmap

    private var singleListener: ((Bitmap?) -> Unit)? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Bitmap?) -> Unit>()

    private fun notifyListeners(value: Bitmap?) {
        singleListener?.invoke(value)
        listeners.forEach { callback ->
            runCatching { callback.invoke(value) }
        }
    }

    init {
        setDefaultBitmap()
    }

    fun setDefaultBitmap(): Boolean {
        val isSystemInDarkMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        var oldBitmap: Bitmap? = null
        if (::defaultBitmap.isInitialized) {
            if (isSystemInDarkMode == lastIsSystemInDarkMode) return false
            oldBitmap = defaultBitmap
        }

        lastIsSystemInDarkMode = isSystemInDarkMode

        val size = getBitmapSize()
        defaultBitmap = createBitmap(size, size).applyCanvas {
            drawColor(getColor(isSystemInDarkMode))
        }
        oldBitmap?.recycle()

        return lastBitmap == null
    }

    fun load(
        uri: Uri?,
        onDone: (Bitmap) -> Unit = { }
    ) {
        if (lastUri == uri && lastBitmap != null) {
            onDone(bitmap)
            return
        }

        lastUri = uri
        lastBitmap = null

        if (uri == null) {
            onDone(bitmap)
            return
        }

        val oldTask = currentTask
        val primaryUri = uri.thumbnail(getBitmapSize()) ?: uri

        fun enqueueRequest(targetUri: Uri, allowFallback: Boolean) {
            currentTask = context.applicationContext.imageLoader.enqueue(
                ImageRequest.Builder(context.applicationContext)
                    .data(targetUri)
                    .allowHardware(false)
                    .listener(
                        onError = { _, _ ->
                            if (allowFallback && targetUri != uri) {
                                enqueueRequest(uri, allowFallback = false)
                            } else {
                                lastBitmap = null
                                onDone(bitmap)
                            }
                        },
                        onSuccess = { _, result ->
                            lastBitmap = result.image.run { toBitmap(width, height) }
                            onDone(bitmap)
                        }
                    )
                    .build()
            )
        }

        enqueueRequest(primaryUri, allowFallback = true)
        oldTask?.dispose()
    }

    fun setListener(callback: ((Bitmap?) -> Unit)?) {
        singleListener = callback
        singleListener?.invoke(lastBitmap)
    }

    fun addListener(callback: (Bitmap?) -> Unit) {
        listeners.add(callback)
        callback.invoke(lastBitmap)
    }

    fun removeListener(callback: (Bitmap?) -> Unit) {
        listeners.remove(callback)
    }
}
