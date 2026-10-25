package com.fangyi.classpp.ui.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp

/**
 * 重力视差（iOS 锁屏壁纸 Perspective 同款）：手机倾斜时，作为背景的整幅图片朝倾斜
 * 反方向轻微平移——像隔着一扇窗看一张比窗口更大的固定场景，倾斜换角度时窗口里的
 * 取景跟着移动，产生细腻的景深感。
 *
 * 数据链路刻意分成两个频率（这是顺滑手感的全部关键）：传感器回调（SENSOR_DELAY_UI，
 * 约 16Hz）只更新**目标位移**——直接按事件写渲染值会把运动量化成每秒十几个台阶，
 * 看起来一顿一顿；帧循环（[TiltParallaxState.runFrameLoop]，挂在组合协程上）在每个
 * 渲染帧用指数追踪把渲染值推向目标，任何刷新率下都连续，收敛后自动休眠、有新目标
 * 才被唤醒——运动全程满帧率、静止零帧开销。位移只在 [tiltParallax] 的 graphicsLayer
 * 里读，倾斜全程零重组。
 *
 * 传感器注册由 [rememberTiltParallax] 全权管理：页面侧门控 [active]、用户开关
 * [enabled]、生命周期 RESUMED 与设备有传感器同时满足才注册，任一失效即注销——
 * active 失效（浮层打开/切后台/离开 01）画面**定格原地**不跳变，enabled 失效
 * （用户关开关）平滑滑回中心；后台零传感器耗电不依赖调用方自觉。重力传感器
 * （[Sensor.TYPE_GRAVITY]，系统融合输出，天然平滑且无瞬时抖动）优先，缺失回退
 * 加速度计并自带重力分离滤波。重力/加速度传感器不需要任何权限。
 */

/** 重力归一化基准（m/s²）。 */
private const val GravityNorm = 9.81f

/** 倾斜响应饱和点：归一化重力分量超过 ±0.5（约 ±30°）后位移封顶，再倾也不动。 */
private const val Saturation = 0.5f

/** 指数追踪时间常数（秒）：渲染值朝目标收 63% 所需时长，约 0.2s，形成略带尾随的
 *  iOS 手感。传感器端（融合重力）已平滑，这一级只负责把 16Hz 的目标更新铺满渲染
 *  帧率——顺滑来源是「逐帧追踪」，不是更快的传感器。 */
private const val PursuitTauSeconds = 0.2f

/** 慢回中基准系数：每事件把基准朝当前重力挪 0.4%（τ≈15s），两轴同用。竖持是重力
 *  纵分量的饱和姿态（天然 ≈ -1）、斜靠支架则横滚分量常驻——不做回中，静止姿态就会
 *  把位移钉死在饱和区。首帧直接定格当前姿态（静止开机不带满位移），此后缓慢跟随
 *  持机姿态，只对「相对当前姿态的倾斜」响应；刻意保持倾斜约 15s 后气泡慢慢回中，
 *  与水平仪气泡趋平的行为一致。 */
private const val BaselineAlpha = 0.004f

/** 死区：渲染值与目标的残差不足该归一化量（≈0.3px，亚像素）时不写状态——包括收敛
 *  贴合那一次；静止时传感器噪声的微移被整个吸收成零重绘（平放不动 = 帧循环只被
 *  唤醒不做任何写），这是功耗控制的关键一环。 */
private const val WriteEpsilon = 0.01f

/** 视差位移上限：单侧最大平移该图层尺寸的 6%（明显档——倾斜时位移一眼可见，
 *  又不至喧宾夺主；iOS 锁屏壁纸约在 3%~6% 区间）。 */
private const val MaxShiftFraction = 0.06f

/** 兜边放大：图层恒定放大 12%，两侧各留 6% 余量包住 ±6% 的最大位移——平移任何时刻
 *  都不露出图层外的底色。代价是取景比原始 Crop 略紧约 12%（绘制期 GPU 放大，照片
 *  不可感知；解码尺寸不动，Coil 请求不受影响）。改 [MaxShiftFraction] 必须同调：
 *  本值 ≥ 1 + 2×[MaxShiftFraction]。 */
private const val OverscanScale = 1.12f

/**
 * 视差状态：传感器事件（[consume]，任意线程）只更新目标并请求帧；帧循环
 * （[runFrameLoop]，组合协程）逐帧追踪并产出两轴归一化位移（[-1,1]，方向语义 =
 * 图层平移方向）。
 *
 * 目标计算与逐帧追踪都是纯数学、不碰 Android API，可单测：归一化 → 双轴慢回中基准
 * → 饱和 → 保号 smoothstep（中心响应软、两端饱和）→ 逐帧指数追踪 → 死区写抑制。
 *
 * 方向符号（窗口望向固定场景 / 相机视差 / 水平仪气泡三种模型推得的同一结论）：
 * 横轴取反——右缘下沉时窗口视线来自右侧，远处的图反而向左平移；纵轴取正——后仰
 * （顶边远离）时视线抬高，图随之下移；平放抬顶边则图上浮（气泡趋高）。真机手感
 * 若不对，翻这两处符号即可。
 */
@Stable
internal class TiltParallaxState {
    var shiftX by mutableFloatStateOf(0f)
        private set
    var shiftY by mutableFloatStateOf(0f)
        private set

    // 追踪状态只由帧循环（主线程）读写；目标只由传感器回调（binder 线程）写、主线程读，
    // 跨线程所以 @Volatile。基准只被传感器回调触碰
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineSet = false

    // 并发请求帧：传感器 16Hz 打点，帧循环收 1 个请求就把当前目标追到收敛
    //（CONFLATED 收敛重复请求），追完休眠等下一个——动满帧率、静止零开销
    private val frameRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * 消费一帧重力采样（m/s²，设备自然竖屏坐标系；加速度计回退时应先做重力分离滤波
     * 再喂进来）。只算目标、不写渲染值，注册/滤波归 [rememberTiltParallax] 管。
     */
    fun consume(gravityX: Float, gravityY: Float) {
        val nx = gravityX / GravityNorm
        val ny = gravityY / GravityNorm
        // 基准：首帧直接定格当前姿态（避免静止开机即带满位移），此后缓慢跟随
        if (!baselineSet) {
            baselineX = nx
            baselineY = ny
            baselineSet = true
        } else {
            baselineX += (nx - baselineX) * BaselineAlpha
            baselineY += (ny - baselineY) * BaselineAlpha
        }
        targetX = -responseCurve(nx - baselineX)
        targetY = responseCurve(ny - baselineY)
        frameRequests.trySend(Unit)
    }

    /**
     * 帧循环：等请求 → 用组合的帧时钟逐帧 [pursueStep] 到收敛 → 回去等下一个请求。
     * 挂在组合协程上（LaunchedEffect）即拥有帧时钟；组合销毁随协程取消。
     */
    suspend fun runFrameLoop() {
        while (coroutineContext.isActive) {
            frameRequests.receive()
            var lastNanos = 0L
            while (true) {
                val moving = withFrameNanos { now ->
                    val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
                    lastNanos = now
                    pursueStep(dt)
                }
                if (!moving) break
            }
        }
    }

    /**
     * 追踪一步：指数逼近目标（dt=0 首帧只取相位不移动），残差进死区不写状态，
     * 收敛时贴合目标（不留亚像素残差）并返回 false 让帧循环休眠。
     */
    internal fun pursueStep(dt: Float): Boolean {
        val factor = if (dt <= 0f) 0f else 1f - exp(-dt / PursuitTauSeconds)
        smoothedX += (targetX - smoothedX) * factor
        smoothedY += (targetY - smoothedY) * factor
        val dx = abs(targetX - smoothedX)
        val dy = abs(targetY - smoothedY)
        if (dx >= WriteEpsilon) shiftX = smoothedX
        if (dy >= WriteEpsilon) shiftY = smoothedY
        if (dx < WriteEpsilon && dy < WriteEpsilon) {
            // 收敛：贴合目标并休眠。贴合写同样过死区——静止时传感器噪声让目标每事件
            // 微移亚像素量，无条件写会以 16Hz 持续触发图层属性失效/重绘；残差不足阈值
            // （<0.3px）保持旧值即可，肉眼不可见
            smoothedX = targetX
            smoothedY = targetY
            if (abs(targetX - shiftX) >= WriteEpsilon) shiftX = targetX
            if (abs(targetY - shiftY) >= WriteEpsilon) shiftY = targetY
            return false
        }
        return true
    }

    /**
     * 暂停定格：页面侧门控失效（浮层打开/切后台/离开 01）时调用——把目标贴到当前
     * 渲染值（在途追踪收敛即停，画面原地不动，不跳回中心），并作废基准；重挂后
     * 首个事件按当下姿态重新定格基准，旧位移经追踪平滑续接归位，全程无跳变。
     */
    fun pause() {
        targetX = smoothedX
        targetY = smoothedY
        baselineSet = false
    }

    /** 归中目标：目标设回 0 并作废基准（用户关掉开关后由 [runGlideHome] 平滑回正，
     *  之后若重新开启，也从当下姿态重新定基准，不带旧姿态残留）。 */
    internal fun retargetHome() {
        targetX = 0f
        targetY = 0f
        baselineSet = false
    }

    /**
     * 归中滑行：逐帧追踪到中心后协程退出（用户关掉开关时画面平滑回正——关闭效果
     * 不该把画面钉在半路）。监听已随开关注销，没有传感器事件打扰，收敛即静止。
     */
    suspend fun runGlideHome() {
        retargetHome()
        var lastNanos = 0L
        while (true) {
            val moving = withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
                lastNanos = now
                pursueStep(dt)
            }
            if (!moving) break
        }
    }

    /** 饱和 + 保号 smoothstep：中心段响应软（微小倾斜不出画）、逼近饱和时平滑封顶。 */
    private fun responseCurve(normalized: Float): Float {
        val u = (normalized / Saturation).coerceIn(-1f, 1f)
        val a = abs(u)
        return kotlin.math.sign(u) * (a * a * (3f - 2f * a))
    }
}

/**
 * 创建视差状态并管理传感器注册。仅当 [active]（页面侧门控——本页可见、浮层未开、
 * 停在出效果的停留态）、[enabled]（用户开关）、宿主 RESUMED 且设备有可用传感器同时
 * 满足才注册。[active] 失效即注销并 [TiltParallaxState.pause]——画面定格原地（浮层
 * 打开时背景静止不跳变），重挂后从当下姿态重新定基准平滑续接；[enabled] 失效即注销
 * 并 [TiltParallaxState.runGlideHome]——关掉效果画面平滑回正。采样率 SENSOR_DELAY_UI
 * （约 16Hz）：手持倾斜是 ~1Hz 级的慢运动，目标更新的粒度由帧循环的逐帧追踪铺满
 * 渲染帧率，又比 GAME（50Hz）省下持续唤醒；真机若嫌目标响应迟钝可上调采样率。
 */
@Composable
internal fun rememberTiltParallax(active: Boolean, enabled: Boolean): TiltParallaxState {
    val state = remember { TiltParallaxState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 帧循环与传感器同生命周期：门控全开时逐帧追踪；仅 active 失效（浮层/切后台/
    // 离开 01）什么都不做——画面定格由传感器注销侧的 pause 完成；enabled 失效则
    // 平滑滑回中心（关掉效果画面该回正，不能钉在半路）
    LaunchedEffect(state, active, enabled, resumed) {
        when {
            active && enabled && resumed -> state.runFrameLoop()
            !enabled -> state.runGlideHome()
            else -> Unit
        }
    }
    DisposableEffect(context, active, enabled, resumed) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelerometer = if (gravity == null) {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            null
        }
        if (gravity == null && accelerometer == null) {
            // 无重力/加速度传感器的设备（部分平板/模拟器）静默不出效果
            onDispose { }
        } else {
            var separatedX = 0f
            var separatedY = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val x = event.values[0]
                    val y = event.values[1]
                    if (gravity != null) {
                        state.consume(x, y)
                    } else {
                        // 加速度计回退：读数混有手部线加速度，经典重力分离滤波
                        // （0.9 旧值 + 0.1 新值）先把重力抽出来再喂视差
                        separatedX += (x - separatedX) * 0.1f
                        separatedY += (y - separatedY) * 0.1f
                        state.consume(separatedX, separatedY)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(
                listener,
                gravity ?: accelerometer,
                SensorManager.SENSOR_DELAY_UI,
            )
            onDispose {
                manager.unregisterListener(listener)
                state.pause()
            }
        }
    }
    return state
}

/**
 * 应用视差位移：图层恒定放大 [OverscanScale] 兜边，按状态位移 × 图层尺寸 ×
 * [MaxShiftFraction] 平移。[gate] 是效果闸门（0~1，如 1 - 转场进度）——转场收缩
 * 时把视差量收敛到 0，落定态像素精确回到几何落位，不与转场几何打架；闸门归零后
 * 图层静止，但兜边放大保留（取景一致性）。全部在绘制阶段读，逐帧不触发重组。
 */
internal fun Modifier.tiltParallax(
    state: TiltParallaxState,
    gate: () -> Float,
): Modifier = graphicsLayer {
    scaleX = OverscanScale
    scaleY = OverscanScale
    val gateValue = gate().coerceIn(0f, 1f)
    translationX = state.shiftX * size.width * MaxShiftFraction * gateValue
    translationY = state.shiftY * size.height * MaxShiftFraction * gateValue
}
