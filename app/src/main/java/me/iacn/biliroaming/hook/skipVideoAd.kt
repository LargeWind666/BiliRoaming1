package me.iacn.biliroaming.hook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.BilibiliSponsorBlock
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodAs
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.mossResponseHandlerReplaceProxy
import me.iacn.biliroaming.utils.sPrefs
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.SeekBar
import java.lang.ref.WeakReference

class SkipVideoAd(classLoader: ClassLoader) : BaseHook(classLoader) {

    private var lastSeekTime = 0L
    private var playerRef: WeakReference<Any>? = null
    private val player get() = playerRef?.get()
    private var duration: Int = -1
    private var segments : List<BilibiliSponsorBlock.Segment>? = null
    private var bvid: String = ""
    private var cid: String = ""
    private var waitTime = 1000
    private var seekBarRef: WeakReference<SeekBar>? = null

    override fun startHook() {
        if (!sPrefs.getBoolean("skip_video_ad", true)) return

        Log.d("startHook: SkipVideoAd")

        instance.playerMossClass?.apply {
            hookBeforeMethod("executePlayViewUnite",
                instance.playViewUniteReqClass
            ) { param ->
                val req = param.args[0]
                bvid = req.callMethodAs("getBvid")
                val vod = req.callMethod("getVod")?:return@hookBeforeMethod
                if (bvid.isEmpty()){
                    val aid = vod.callMethodAs<Long>("getAid")
                    if (aid==-1L){
                        return@hookBeforeMethod
                    }
                    bvid = av2bv(aid)
                }
                cid = vod.callMethodAs<Long>("getCid").toString()
            }

            hookBeforeMethod("playViewUnite",
                instance.playViewUniteReqClass,
                instance.mossResponseHandlerClass
            ){ param ->
                param.args[1] = param.args[1].mossResponseHandlerReplaceProxy { reply ->
                    reply ?: return@mossResponseHandlerReplaceProxy null
                    val playArc = reply.callMethod("getPlayArc")?:return@mossResponseHandlerReplaceProxy null
                    cid = playArc.callMethodAs<Long>("getCid").toString()
                    val aid = playArc.callMethodAs<Long>("getAid")?:-1L
                    if (aid==-1L){
                        return@mossResponseHandlerReplaceProxy null
                    }
                    bvid = av2bv(aid)
                    null
                }
            }
        }

        instance.playerCoreServiceV2Class?.apply {
            hookAfterMethod("G1", Int::class.java) { param ->
                playerRef = WeakReference(param.thisObject)
                val state = param.args[0] as Int
                if (state in 3..5 && duration<=0) {
                    duration = (player?.callMethodAs<Int>("getDuration") ?: -1)
                }
                if(state == 2) {
                    duration = -1
                    segments = null
                    CoroutineScope(Dispatchers.IO).launch{
                        var retryCount = 0
                        val maxRetries = 3
                        while (retryCount < maxRetries) {
                            segments = BilibiliSponsorBlock(bvid, cid).getSegments()
                            if (segments.isNullOrEmpty()) {
                                retryCount++
                                delay(1000)
                            } else {
                                break
                            }
                        }
                        if (segments == null){
                            return@launch
                        }
                        // 加载广告片段后刷新进度条
                        seekBarRef?.get()?.invalidate()
                    }
                }
            }

            hookAfterMethod("getCurrentPosition") { param ->
                val now = System.currentTimeMillis()
                if (now - lastSeekTime > waitTime) {
                    lastSeekTime = now
                    waitTime = if(seekTo(param.result as Int)) 3000 else 1000
                }
            }
        }

        // Hook 进度条来绘制广告片段标记
        hookSeekBarDrawing()
    }

    private fun hookSeekBarDrawing() {
        // 查找并Hook SeekBar的onDraw方法来绘制广告片段
        try {
            val seekBarClass = Class.forName("android.widget.SeekBar")
            
            // Hook SeekBar的绘制方法
            hookAfterMethod(seekBarClass, "onDraw", Canvas::class.java) { param ->
                val seekBar = param.thisObject as? SeekBar ?: return@hookAfterMethod
                seekBarRef = WeakReference(seekBar)
                
                val canvas = param.args[0] as Canvas
                drawAdSegments(canvas, seekBar)
            }
        } catch (e: Exception) {
            Log.e("Failed to hook SeekBar: ${e.message}")
        }
    }

    private fun drawAdSegments(canvas: Canvas, seekBar: SeekBar) {
        if (segments.isNullOrEmpty() || duration <= 0) return

        val paint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 2f
            isAntiAlias = true
        }

        val seekBarWidth = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val seekBarHeight = seekBar.height
        val thumbOffset = seekBar.paddingLeft

        for (segment in segments!!) {
            val startSeconds = segment.segment[0]
            val endSeconds = segment.segment[1]

            // 计算在进度条上的像素位置
            val startPixel = thumbOffset + (startSeconds / duration * seekBarWidth).toInt()
            val endPixel = thumbOffset + (endSeconds / duration * seekBarWidth).toInt()

            // 绘制绿色矩形表示广告片段
            if (startPixel < endPixel) {
                canvas.drawRect(
                    startPixel.toFloat(),
                    (seekBarHeight / 4).toFloat(),
                    endPixel.toFloat(),
                    (seekBarHeight * 3 / 4).toFloat(),
                    paint
                )
            }
        }
    }

    private fun seekTo(position: Int?): Boolean {
        if (position != null) {
            if (position > duration) return  false
        }

        if (segments != null) {
            for (segment in segments) {
                val start = (segment.segment[0]*1000).toInt()
                val end = (segment.segment[1]*1000).toInt()
                if (position in start..<end) {
                    Log.toast("已跳过广告片段")
                    player?.callMethod("seekTo", end)
                    return true
                }
            }
        }
        return false
    }
}
