# Video Buffer Converter

The VideoBufferConverter provides fast pixel format conversions between WebRTC's internal I420 video frame buffers and other formats identified by a FourCC. Conversions are delegated to optimized native routines.

API: `dev.onvoid.webrtc.media.video.VideoBufferConverter`

## When to use it
- Rendering frames in UI toolkits that expect interleaved RGB(A) byte layouts.
- Preparing frames for encoders/decoders that require specific pixel formats.
- Importing external pixel data (e.g., RGBA, NV12) into the WebRTC pipeline as I420.

See also: [Video Capture](/tools/video/video-capturer), [Custom Video Source](/guide/video/custom-video-source).

## Supported operations

1) From I420 to other pixel formats
- `convertFromI420(VideoFrameBuffer src, byte[] dst, FourCC fourCC)`
- `convertFromI420(VideoFrameBuffer src, ByteBuffer dst, FourCC fourCC)`

2) From other pixel formats to I420
- `convertToI420(byte[] src, I420Buffer dst, FourCC fourCC)`
- `convertToI420(ByteBuffer src, I420Buffer dst, FourCC fourCC)`

Notes:
- The VideoFrameBuffer is internally converted to I420 if necessary using `VideoFrameBuffer#toI420()` before transformation.
- When using ByteBuffer destinations/sources, direct buffers use a zero-copy native path for best performance; otherwise, the method will use the backing array or a temporary array.

## FourCC formats

The target/source pixel layout is selected with `dev.onvoid.webrtc.media.FourCC`. Common values include:
- `FourCC.RGBA` – 4 bytes per pixel, RGBA order (commonly used with BufferedImage TYPE_4BYTE_ABGR interop, see example below)
- `FourCC.ARGB`, `FourCC.ABGR`, `FourCC.BGRA` – other 32-bit packed variants
- `FourCC.NV12`, `FourCC.NV21` – 4:2:0 semi-planar YUV formats

Consult the FourCC enum in your version for the complete list.

## Buffer sizing

You must allocate destination buffers large enough for the chosen format:
- For 32-bit RGBA-like formats: `width * height * 4` bytes
- For NV12/NV21: `width * height * 3 / 2` bytes
- For other layouts, compute according to their specification

Attempting to convert into undersized buffers will result in an error.

## Example: Convert VideoFrame to BufferedImage

This example demonstrates converting a WebRTC `VideoFrame` to a Java `BufferedImage` using RGBA output.

```java
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

public void onVideoFrame(VideoFrame frame) {
    try {
        // Get frame dimensions
        VideoFrameBuffer frameBuffer = frame.buffer;
        int frameWidth = frameBuffer.getWidth();
        int frameHeight = frameBuffer.getHeight();
        
        // Create a BufferedImage with ABGR format (compatible with RGBA conversion)
        BufferedImage image = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_4BYTE_ABGR);
        
        // Get the underlying byte array from the BufferedImage
        byte[] imageBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        
        // Convert the frame buffer from I420 format to RGBA format
        VideoBufferConverter.convertFromI420(frameBuffer, imageBuffer, FourCC.RGBA);
        
        // Now you can use the BufferedImage for display or further processing
        // e.g., display in Swing/JavaFX
    }
    catch (Exception e) {
        // Handle conversion errors
        e.printStackTrace();
    }
    finally {
        // Always release the frame when done
        frame.release();
    }
}
```

How it works:
1. Create a `BufferedImage` sized to the frame.
2. Access its backing `byte[]` via `DataBufferByte`.
3. Convert the VideoFrameBuffer from I420 to RGBA into the image buffer.

Tip: If you have a direct NIO ByteBuffer (e.g., for native interop), use the ByteBuffer overload to keep a direct native path.

## Example: Import RGBA data into I420

```java
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

public void onImage(BufferedImage image) {
    // Get image dimensions
    int imageWidth = image.getWidth();
    int imageHeight = image.getHeight();

    // Create a I420Buffer
    NativeI420Buffer i420 = NativeI420Buffer.allocate(imageWidth, imageHeight);

    // Get the underlying byte array from the BufferedImage
    byte[] imageBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

    try {
        // In this example, we assume the BufferedImage is in 4 byte ABGR format
        VideoBufferConverter.convertToI420(imageBuffer, i420, FourCC.RGBA);

        // Now you can use the I420Buffer, e.g., wrap in a VideoFrame
    }
    catch (Exception e) {
        // Handle conversion errors
        e.printStackTrace();
    }
    finally {
        // Always release the buffer when done
        i420.release();
    }
}
```

## Error handling and edge cases
- All methods throw `NullPointerException` if src/dst is null; ensure proper checks.
- `ByteBuffer` destinations must be writable (not read-only) for `convertFromI420`.
- Ensure the correct FourCC is used for the actual memory layout you pass/expect.
- Beware of frame rotation metadata; conversions do not rotate pixels. Handle `VideoFrame.rotation` separately if your renderer requires upright images.

## Related
- [Video Capture](/tools/video/video-capturer)
- [Custom Video Source](/guide/video/custom-video-source)
- [Screen Capturer](/tools/desktop/screen-capturer)
- [Window Capturer](/tools/desktop/window-capturer)
