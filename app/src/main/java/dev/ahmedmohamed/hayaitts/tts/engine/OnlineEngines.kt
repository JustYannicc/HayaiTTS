package dev.ahmedmohamed.hayaitts.tts.engine

import android.content.Context

/** Factory for online engines; Edge plugs in here. */
internal object OnlineEngines {
    fun create(context: Context): OnlineEngine? = null
}
