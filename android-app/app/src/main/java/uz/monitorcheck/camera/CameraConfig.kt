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
 * Miltillash o'lchovi uchun tanlangan kamera va uning imkoniyatlari.
 */
data class CameraConfig(
    val cameraId: String,
    /** Tahlil uchun YUV tasvir o'lchami (sensor yo'nalishida). */
    val yuvSize: Size,
    val previewSize: Size,
    val sensorOrientation: Int,
    /** MANUAL_SENSOR: ekspozitsiya va ISO ni qo'lda berish mumkin. */
    val manualSensor: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    /** CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW mavjud. */
    val hasSkew: Boolean,
    /** TONEMAP_MODE_CONTRAST_CURVE — chiziqli javob berish mumkin. */
    val linearTonemap: Boolean,
    val manualFocus: Boolean,
    val noiseReductionOff: Boolean,
    val edgeOff: Boolean,
    val hardwareLevel: Int,
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

    companion object {
        /** Maqsadli ekspozitsiya: 1/8000 s (125 µs) — qurilma ruxsat bergan oraliqqa keltiriladi. */
        const val TARGET_EXPOSURE_NS = 125_000L

        /** Eng uzun ruxsat etilgan ekspozitsiya (sinc susayishi kuchli bo'lmasligi uchun): 1/2000 s. */
        const val MAX_EXPOSURE_NS = 500_000L

        /**
         * Orqa kameralar ichidan eng mosini tanlash: qo'lda ekspozitsiya + skew + chiziqli tonemap
         * imkoniyatlari bo'yicha ball beriladi.
         */
        fun select(manager: CameraManager): CameraConfig? {
            val candidates = manager.cameraIdList.mapNotNull { id ->
                val ch = manager.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                build(id, ch)
            }
            return candidates.maxByOrNull { c ->
                (if (c.manualSensor) 4 else 0) + (if (c.hasSkew) 2 else 0) + (if (c.linearTonemap) 1 else 0)
            }
        }

        private fun build(id: String, ch: CameraCharacteristics): CameraConfig? {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val activeAspect = if (active != null && active.height() > 0) active.width().toDouble() / active.height() else 4.0 / 3

            // Tasvir sensor bilan bir xil nisbatda bo'lsa, u to'liq o'qish oralig'ini qamraydi
            // (skew → t_row hisobi uchun muhim). Qatorlar ko'p bo'lsa — vaqt bo'yicha ajratish yaxshi.
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            if (yuv.isEmpty()) return null
            val sameAspect = yuv.filter { abs(it.width.toDouble() / it.height - activeAspect) < 0.02 && it.height <= 2000 }
            val yuvSize = (sameAspect.ifEmpty { yuv.filter { it.height <= 2000 } }.ifEmpty { yuv })
                .maxBy { it.width.toLong() * it.height }

            val prevSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val previewSize = prevSizes
                .filter { abs(it.width.toDouble() / it.height - yuvSize.width.toDouble() / yuvSize.height) < 0.02 && it.width <= 1280 }
                .maxByOrNull { it.width * it.height }
                ?: prevSizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                ?: Size(640, 480)

            val tonemapModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet() ?: emptySet()
            val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
            val edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet() ?: emptySet()
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            return CameraConfig(
                cameraId = id,
                yuvSize = yuvSize,
                previewSize = previewSize,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                manualSensor = manual,
                exposureRangeNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                hasSkew = ch.availableCaptureResultKeys.contains(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                linearTonemap = manual && CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes,
                manualFocus = manual && minFocus > 0f,
                noiseReductionOff = CameraMetadata.NOISE_REDUCTION_MODE_OFF in nrModes,
                edgeOff = CameraMetadata.EDGE_MODE_OFF in edgeModes,
                hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            )
        }
    }
}
