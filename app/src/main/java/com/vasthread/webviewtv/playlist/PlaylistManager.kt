package com.vasthread.webviewtv.playlist

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.vasthread.webviewtv.misc.application
import com.vasthread.webviewtv.misc.preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

object PlaylistManager {

    private const val TAG = "PlaylistManager"
    private const val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L
    private const val KEY_PLAYLIST_URL = "playlist_url"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_RETRY_DELAY = 10 * 1000L

    private val client =
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .build()
    private val gson = GsonBuilder().setPrettyPrinting().create()!!
    private val jsonTypeToken = object : TypeToken<List<Channel>>() {}
    private val playlistFile = File(application.filesDir, "playlist.json")

    //增加默认节目列表防止应用启动崩溃问题
    private val builtInPlaylists = arrayListOf(
        "full" to "https://raw.githubusercontent.com/linlin515/iptv_test/refs/heads/main/full.json"
    )

    var onPlaylistChange: ((Playlist) -> Unit)? = null
    var onUpdatePlaylistJobStateChange: ((Boolean) -> Unit)? = null
    private var updatePlaylistJob: Job? = null
    private var isUpdating = false
        set(value) {
            onUpdatePlaylistJobStateChange?.invoke(value)
        }

    fun getBuiltInPlaylists() = builtInPlaylists

    fun setPlaylistUrl(url: String) {
        preference.edit()
            .putString(KEY_PLAYLIST_URL, url)
            .putLong(KEY_LAST_UPDATE, 0)
            .apply()
        requestUpdatePlaylist()
    }

    fun getPlaylistUrl() = preference.getString(KEY_PLAYLIST_URL, builtInPlaylists[0].second)!!

    fun setLastUpdate(time: Long, requestUpdate: Boolean = false) {
        preference.edit().putLong(KEY_LAST_UPDATE, time).apply()
        if (requestUpdate) requestUpdatePlaylist()
    }

    private fun requestUpdatePlaylist() {
        val lastJobCompleted = updatePlaylistJob?.isCompleted
        if (lastJobCompleted != null && !lastJobCompleted) {
            Log.i(TAG, "A job is executing, ignore!")
            return
        }
        updatePlaylistJob = CoroutineScope(Dispatchers.IO).launch {
            var times = 0
            val needUpdate = {
                System.currentTimeMillis() - preference.getLong(
                    KEY_LAST_UPDATE,
                    0L
                ) > CACHE_EXPIRATION_MS
            }
            isUpdating = true
            while (needUpdate()) {
                ++times
                Log.i(TAG, "Updating playlist... times=${times}")
                try {
                    val request = Request.Builder()
                        .url(getPlaylistUrl())
//                        .addHeader(
//                            "Accept",
//                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
//                        )
//                        .addHeader("Accept-Encoding", "gzip, deflate, br") // 允许压缩，服务器可能校验
//                        .addHeader("Accept-Language", "zh-CN,zh;q=0.9") // 语言偏好
//                        .addHeader("Host", "gitee.com") // 必须匹配域名，否则可能被拦截
//                        .addHeader(
//                            "Referer",
//                            "https://gitee.com/linruihang/iptv_source/blob/master/full.json"
//                        ) // 来源页（blob地址）
//                        .addHeader(
//                            "User-Agent",
//                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
//                        ) // 完整浏览器UA
//                        .addHeader("Connection", "keep-alive")
//                        .addHeader("Upgrade-Insecure-Requests", "1")
//                        .addHeader("Cookie","user_locale=zh-CN; oschina_new_user=false; remote_way=http; BEC=1f1759df3ccd099821dcf0da6feb0357; Hm_lvt_24f17767262929947cc3631f99bfd274=1761616087,1763264662; HMACCOUNT=2B58796639638630; user_return_to_0=%2F; tz=Asia%2FShanghai; remember_user_token=BAhbCFsGaQM0tBxJIiIkMmEkMTAkLm13TDc2TEQxZ2F4YXBZNFFjZWkuTwY6BkVUSSIWMTc2MzI2NTA2OS43NjAxMTEGOwBG--88ab465dc87ba6feee2eb9dd4e12cd7618a5db5b; gitee_user=true; csrf_token=coldgotEMWHPajif3HE2i2LqKqRFUJz%2FAzqBgkOQhjpQQ%2FvBRrnW4GypHewisRGtb%2Fh71A6QXZ4V88Ruigw3cw%3D%3D; Hm_lpvt_24f17767262929947cc3631f99bfd274=1763267733; gitee-session-n=OE9QYlgzalhGeVZ2THBWQ0UvcTd2VmdYS21FZzBwQVVBbWNvbG50OTJ1UytIQ0ZDemhKdHRSL1FKZnhoSzN3b1AyRXpqUmJrOExQeENQU0pHY0ExTkt1K3dYZ08wZWN3NXBVdmtzUngrSGlvZ3IxYmxSbXg3MFFqbG0xRHNwYTBzenIraWdsM2h2Z0JzNTNCUXRBNElWNHc3SmJyNlFnZTlVSUpDMTJNMlhjbGZDNzBmWEtOMXVQRXlldnZWTXQ1OXRtSkQzcVdXM3R6MW84WDM2WEZJcnRQK2NaS0hmVHgvTlFJcmVpd1l5aGtTYUpoSzdrZWF4bFpyOXdHejFPZEl6RFBJd0x0aS9YM2VuQmVhY1dZeUNXODRpWlJmYmcydWhFQzM4MFh0Z201UGpBd0hhVkE3Q1NjVFNkSlUrTnVkWWxKZkFVRjk0M0VqeGpISkJoZWM0NktWVEhNMHhEWkpHeTBvYVEzdU9RM2FDdXNwNGl2eG5tc3l4ZHU2RkNrRGJoVVpaVXJML1NYZU5ma3lyMzYvY1VOQ0JxZFJtblZITjBqZDJCcUFTZWpoeEZ1cUdBSmJYbnZ4akZyOVpVWnJoOUtBNm1CNWdMakxZeWdYZjl4Um94NUJmazJJZTBLSlZYMjZRRlZsSjdZemdWV0NnTGpQV052TzJFemxSMXhTakFIaSsxVTVnekQrRDdnRnJTbGE1SVJlSlliSndIUU1hZC8xV3g3Z0RtU2N5ZkdBZkRMeENXZ3VLNkdlOVB5ZCtzT3F1bDhxeXYySjRxdW9uN0EwZytoblVJdXh0S2Zya2hPWkhCM01JZ1VuVWw3N05FZnN0N3VCclZya3kzeC0tb2laalprTHlpcElxTVY0MmFPMDRGZz09--0eefab260bbbae1f2af563f1734326ea88c021df")
                        .get().build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Response code ${response.code}")

                    val remote = response.body!!.string()
                    val local = runCatching { playlistFile.readText() }.getOrNull()
                    if (remote != local) {
                        playlistFile.writeText(remote)
                        onPlaylistChange?.invoke(createPlaylistFromJson(remote))
                    }

                    setLastUpdate(System.currentTimeMillis())
                    Log.i(TAG, "Update playlist successfully.")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot update playlist, reason: ${e.message}")
                }
                if (needUpdate()) {
                    delay(UPDATE_RETRY_DELAY)
                }
            }
            isUpdating = false
        }
    }

    private fun createPlaylistFromJson(json: String): Playlist {
        val channels = gson.fromJson(json, jsonTypeToken)
        return Playlist.createFromAllChannels("default", channels)
    }

    private fun loadBuiltInPlaylist() = createPlaylistFromJson("[]")

    fun loadPlaylist(): Playlist {
        return try {
            val json = playlistFile.readText()
            createPlaylistFromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load playlist, reason: ${e.message}")
            setLastUpdate(0L)
            loadBuiltInPlaylist()
        } finally {
            requestUpdatePlaylist()
        }
    }

}