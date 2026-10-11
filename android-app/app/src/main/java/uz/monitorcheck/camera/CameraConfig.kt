package uz.monitorcheck.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.util.Range
import android.util.Size
import kotlin.math.abs

/**
 * Tanlangan orqa kamera va uning imkoniyatlari.
 */
data class CameraConfig(
    val cameraId: String,
    /** Miltillash tahlili uchun YUV tasvir o'lchami (sensor yo'nalishida). */
    val yuvSize: Size,
    /** Jonli markerlarni topish uchun kichik YUV o'lchami. */
    val liveSize: Size,
    /** Piksel/bir tekislik tahlili uchun JPEG o'lchami (≤ 8.5 MP — xotira cheklovi). */
    val jpegSize: Size,
    val previewSize: Size,
    val sensorOrientation: Int,
    /** MANUAL_SENSOR imkoniyati e'lon qilingan. */
    val manualSensor: Boolean,
    /** CONTROL_AE_MODE_OFF ruxsat etilgan (qo'lda ekspozitsiyani sinab ko'rish mumkin). */
    val aeOffSupported: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    val aeCompensationRange: Range<Int>?,
    /** Bitta kompensatsiya qadami, EV. */
    val aeCompensationStep: Double,
    /** Eng yuqori kadrlar chastotasi oralig'i (avtomatik ekspozitsiyani qisqartirish uchun). */
    val fastFpsRange: Range<Int>?,
    val hasSkew: Boolean,
    val linearTonemap: Boolean,
    /** Fokusni qo'lda berish mumkin (LENS_FOCUS_DISTANCE). */
    val manualFocus: Boolean,
    val noiseReductionOff: Boolean,
    val edgeOff: Boolean,
    val hardwareLevel: Int,
    /** JPEG tasvir uchun fokus masofasi, piksel (pinhole model: f_px = f_mm / sensor_kengligi_mm × W). */
    val jpegFocalPx: Double?,
) {
    val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL"
        }

    /** Kalibrovkani saqlash kaliti: kamera + tasvir o'lchami (t_row o'lchamga bog'liq). */
    val calibrationKey: String get() = "$cameraId:${yuvSize.width}x${yuvSize.height}"

    /** EV qiymatini kompensatsiya indeksiga aylantirish (qurilma oralig'iga keltiriladi). */
    fun evIndex(ev: Double): Int {
        val r = aeCompensationRange ?: return 0
        if (aeCompensationStep <= 0) return 0
        return Math.round(ev / aeCompensationStep).toInt().coerceIn(r.lower, r.upper)
    }

    companion object {
        /** Maqsadli ekspozitsiya: 1/8000 s (125 µs) — qurilma ruxsat bergan oraliqqa keltiriladi. */
        const val TARGET_EXPOSURE_NS = 125_000L

        /** Qo'lda rejimdagi eng uzun ekspozitsiya: 1/5000 s — qamrov ≥ 3000 Hz (sinc ≥ 0.5). */
        const val MAX_EXPOSURE_NS = 200_000L

        fun select(manager: CameraManager): CameraConfig? {
            val candidates = manager.cameraIdList.mapNotNull { id ->
                val ch = manager.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                build(id, ch)
            }
            // Bir xil ball bo'lsa — ro'yxatdagi birinchisi (odatda asosiy kamera)
            return candidates.withIndex().maxByOrNull { (i, c) ->
                (if (c.manualSensor) 8 else 0) + (if (c.aeOffSupported) 4 else 0) + (if (c.hasSkew) 2 else 0) +
                    (if (c.linearTonemap) 1 else 0) - i * 0.01
            }?.value
        }

        private fun aspect(s: Size) = s.width.toDouble() / s.height

        private fun build(id: String, ch: CameraCharacteristics): CameraConfig? {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val activeAspect = if (active != null && active.height() > 0) active.width().toDouble() / active.height() else 4.0 / 3

            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            if (yuv.isEmpty()) return null
            val sameAspect = yuv.filter { abs(aspect(it) - activeAspect) < 0.02 }
            // Tasvir sensor bilan bir xil nisbatda bo'lsa, u to'liq o'qish oralig'ini qamraydi (skew → t_row)
            val yuvSize = (sameAspect.filter { it.height <= 2000 }.ifEmpty { yuv.filter { it.height <= 2000 } }.ifEmpty { yuv })
                .maxBy { it.width.toLong() * it.height }
            val liveSize = (sameAspect.filter { it.width <= 1280 }.ifEmpty { yuv.filter { it.width <= 1280 } }.ifEmpty { yuv })
                .maxBy { it.width.toLong() * it.height }

            val jpegs = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val jpegSize = (jpegs.filter { abs(aspect(it) - activeAspect) < 0.02 && it.width.toLong() * it.height <= 8_500_000 }
                .ifEmpty { jpegs.filter { it.width.toLong() * it.height <= 8_500_000 } }.ifEmpty { jpegs })
                .maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1440)

            val prevSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val previewSize = prevSizes
                .filter { abs(aspect(it) - aspect(yuvSize)) < 0.02 && it.width <= 1280 }
                .maxByOrNull { it.width * it.height }
                ?: prevSizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                ?: Size(640, 480)

            val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet() ?: emptySet()
            val tonemapModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet() ?: emptySet()
            val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
            val edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet() ?: emptySet()
            val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()

            val focalMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalPx = if (focalMm != null && phys != null && phys.width > 0) focalMm / phys.width * jpegSize.width.toDouble() else null

            return CameraConfig(
                cameraId = id,
                yuvSize = yuvSize,
                liveSize = liveSize,
                jpegSize = jpegSize,
                previewSize = previewSize,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                manualSensor = manual,
                aeOffSupported = manual || CameraMetadata.CONTROL_AE_MODE_OFF in aeModes,
                exposureRangeNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                aeCompensationRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
                aeCompensationStep = step?.toDouble() ?: 0.0,
                fastFpsRange = fps.maxWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper })),
                hasSkew = ch.availableCaptureResultKeys.contains(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                linearTonemap = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes,
                manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
                noiseReductionOff = CameraMetadata.NOISE_REDUCTION_MODE_OFF in nrModes,
                edgeOff = CameraMetadata.EDGE_MODE_OFF in edgeModes,
                hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                jpegFocalPx = focalPx,
            )
        }
    }
}
