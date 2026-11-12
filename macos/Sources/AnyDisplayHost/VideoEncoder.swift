import CoreMedia
import CoreVideo
import AnyDisplayProtocol
import Foundation
import VideoToolbox

public enum EncoderError: Error, CustomStringConvertible {
    case create(OSStatus)

    public var description: String {
        switch self {
        case .create(let s): return "VTCompressionSessionCreate failed (\(s))"
        }
    }
}

/// Low-latency H.264/HEVC encoder producing Annex-B access units with in-band parameter sets on keyframes.
public final class VideoEncoder {
    public struct Output {
        public let accessUnit: Data
        public let keyframe: Bool
        public let pts: CMTime
    }

    public let codec: Codec
    public let width: Int
    public let height: Int
    public private(set) var isHardware = false
    private var session: VTCompressionSession?
    private let onOutput: (Output) -> Void
    private let onDrop: () -> Void

    /// - Parameters:
    ///   - onOutput: called on a VideoToolbox thread, in submission order.
    ///   - onDrop: called when a submitted frame produced no output.
    public init(codec: Codec, width: Int, height: Int, fps: Int, bitrateKbps: Int,
                onOutput: @escaping (Output) -> Void, onDrop: @escaping () -> Void) throws {
        self.codec = codec
        self.width = width
        self.height = height
        self.onOutput = onOutput
        self.onDrop = onDrop

        let codecType = codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264
        // Hardware without VideoToolbox's low-latency rate control first. Measured on this Mac at
        // 2944x1840 90fps: low-latency rate control dropped ~9.5% of frames in a standalone test and
        // collapsed to ~20fps in the app once content hit the bitrate target, while the same session
        // without it produced every frame. It is only a fallback when plain hardware is unavailable.
        let attempts: [[CFString: Any]] = [
            [kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true],
            [kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true,
             kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true],
            [:],
        ]
        var status: OSStatus = -1
        var hardwareRequired = false
        for spec in attempts {
            var s: VTCompressionSession?
            status = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height),
                                                codecType: codecType, encoderSpecification: spec as CFDictionary,
                                                imageBufferAttributes: nil, compressedDataAllocator: nil,
                                                outputCallback: nil, refcon: nil, compressionSessionOut: &s)
            if status == noErr, let s {
                session = s
                hardwareRequired = spec[kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder] != nil
                Log.debug("encoder spec \(spec.keys.map { $0 as String }) ok")
                break
            }
        }
        guard let session else { throw EncoderError.create(status) }

        func set(_ key: CFString, _ value: Any) {
            let st = VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
            if st != noErr { Log.debug("VTSessionSetProperty \(key) failed: \(st)") }
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue!)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse!)
        set(kVTCompressionPropertyKey_ProfileLevel,
            codec == .hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
        if codec == .h264 { set(kVTCompressionPropertyKey_H264EntropyMode, kVTH264EntropyMode_CABAC) }
        set(kVTCompressionPropertyKey_ExpectedFrameRate, fps)
        // Keyframes come on demand (new stream, KEYFRAME_REQ); a periodic one bounds recovery time.
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, fps * 30)
        set(kVTCompressionPropertyKey_AverageBitRate, bitrateKbps * 1000)
        // The measured complex-content stream sat exactly on AverageBitRate and VideoToolbox dropped
        // frames during bursts. Allow a 2x one-second burst so transient complexity is not clipped,
        // while the average target (and therefore steady-state bandwidth) stays unchanged.
        let bytesPerSecond = bitrateKbps * 1000 / 8
        set(kVTCompressionPropertyKey_DataRateLimits, [bytesPerSecond * 2, 1] as CFArray)
        set(kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, kCFBooleanTrue!)
        set(kVTCompressionPropertyKey_MaximizePowerEfficiency, kCFBooleanFalse!)

        var hw: CFBoolean?
        let hwStatus = withUnsafeMutablePointer(to: &hw) {
            VTSessionCopyProperty(session, key: kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
                                  allocator: nil, valueOut: $0)
        }
        isHardware = hardwareRequired || (hwStatus == noErr && hw == kCFBooleanTrue)
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    deinit {
        invalidate()
    }

    public func invalidate() {
        if let session {
            VTCompressionSessionInvalidate(session)
            self.session = nil
        }
    }

    public func encode(_ pixelBuffer: CVPixelBuffer, pts: CMTime, forceKeyframe: Bool) {
        guard let session else { onDrop(); return }
        let props: CFDictionary? = forceKeyframe ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue!] as CFDictionary : nil
        let codec = self.codec
        let onOutput = self.onOutput
        let onDrop = self.onDrop
        let status = VTCompressionSessionEncodeFrame(session, imageBuffer: pixelBuffer, presentationTimeStamp: pts,
                                                     duration: .invalid, frameProperties: props, infoFlagsOut: nil) {
            status, _, sample in
            guard status == noErr, let sample, let au = Self.annexB(sample, codec: codec) else {
                if status != noErr { Log.error("encode failed: \(status)") }
                onDrop()
                return
            }
            onOutput(Output(accessUnit: au.data, keyframe: au.keyframe, pts: CMSampleBufferGetPresentationTimeStamp(sample)))
        }
        if status != noErr {
            Log.error("VTCompressionSessionEncodeFrame: \(status)")
            onDrop()
        }
    }

    // MARK: - AVCC -> Annex-B

    private static func parameterSet(_ format: CMFormatDescription, _ codec: Codec, _ index: Int,
                                     _ ptr: UnsafeMutablePointer<UnsafePointer<UInt8>?>?, _ size: UnsafeMutablePointer<Int>?,
                                     _ count: UnsafeMutablePointer<Int>?, _ nalLength: UnsafeMutablePointer<Int32>?) -> OSStatus {
        if codec == .hevc {
            return CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                format, parameterSetIndex: index, parameterSetPointerOut: ptr, parameterSetSizeOut: size,
                parameterSetCountOut: count, nalUnitHeaderLengthOut: nalLength)
        }
        return CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: index, parameterSetPointerOut: ptr, parameterSetSizeOut: size,
            parameterSetCountOut: count, nalUnitHeaderLengthOut: nalLength)
    }

    private static let startCode = Data([0, 0, 0, 1])

    static func annexB(_ sample: CMSampleBuffer, codec: Codec) -> (data: Data, keyframe: Bool)? {
        guard let block = CMSampleBufferGetDataBuffer(sample), let format = CMSampleBufferGetFormatDescription(sample) else {
            return nil
        }
        var keyframe = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
           let first = attachments.first, let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool {
            keyframe = !notSync
        }

        var nalLengthSize: Int32 = 4
        var count = 0
        guard parameterSet(format, codec, 0, nil, nil, &count, &nalLengthSize) == noErr else { return nil }

        let total = CMBlockBufferGetDataLength(block)
        var out = Data()
        out.reserveCapacity(total + 64)
        if keyframe {
            for i in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size = 0
                guard parameterSet(format, codec, i, &ptr, &size, nil, nil) == noErr, let ptr else { return nil }
                out.append(startCode)
                out.append(ptr, count: size)
            }
        }

        let lenSize = Int(nalLengthSize)
        // A contiguous block buffer is parsed in place; a non-contiguous one uses a bounded copy.
        if CMBlockBufferIsRangeContiguous(block, atOffset: 0, length: total) {
            var ptr: UnsafeMutablePointer<Int8>?
            var available = 0
            let status = CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                                    totalLengthOut: &available, dataPointerOut: &ptr)
            if status == noErr, let ptr, available >= total {
                appendNals(&out, UnsafeRawPointer(ptr).assumingMemoryBound(to: UInt8.self), total, lenSize)
                return (out, keyframe)
            }
        }
        var avcc = [UInt8](repeating: 0, count: total)
        let copied = avcc.withUnsafeMutableBytes {
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: total, destination: $0.baseAddress!)
        }
        guard copied == noErr else { return nil }
        avcc.withUnsafeBufferPointer { appendNals(&out, $0.baseAddress!, total, lenSize) }
        return (out, keyframe)
    }

    /// Converts the format's length-prefixed NAL units to Annex-B start codes without intermediate slices.
    private static func appendNals(_ out: inout Data, _ base: UnsafePointer<UInt8>, _ total: Int, _ lenSize: Int) {
        var offset = 0
        while offset + lenSize <= total {
            var nalLength = 0
            for i in 0..<lenSize { nalLength = nalLength << 8 | Int(base[offset + i]) }
            offset += lenSize
            guard nalLength > 0, offset + nalLength <= total else { break }
            out.append(startCode)
            out.append(base + offset, count: nalLength)
            offset += nalLength
        }
    }
}
