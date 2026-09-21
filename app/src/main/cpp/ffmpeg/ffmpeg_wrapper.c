#include "ffmpeg_wrapper.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>

#include <android/log.h>

#define LOG_TAG "FFmpegWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Custom IO context for reading from memory
typedef struct {
    uint8_t *buffer;
    size_t buffer_size;
    size_t position;
} MemoryContext;

// Read callback for custom IO
static int read_packet(void *opaque, uint8_t *buf, int buf_size) {
    if (!opaque || !buf)
        return AVERROR(EINVAL);

    MemoryContext *ctx = (MemoryContext *) opaque;

    if (!ctx->buffer)
        return AVERROR(EINVAL);

    // Check if we're at or past the end of the buffer
    if (ctx->position >= ctx->buffer_size)
        return AVERROR_EOF;

    // Calculate how many bytes we can actually read
    buf_size = FFMIN(buf_size, ctx->buffer_size - ctx->position);
    if (buf_size <= 0)
        return AVERROR_EOF;

    // Copy the data
    memcpy(buf, ctx->buffer + ctx->position, buf_size);
    ctx->position += buf_size;

    LOGI("Read %d bytes at position %zu", buf_size, ctx->position - buf_size);

    return buf_size;
}

// Seek callback for custom IO
static int64_t seek_packet(void *opaque, int64_t offset, int whence) {
    if (!opaque)
        return AVERROR(EINVAL);

    MemoryContext *ctx = (MemoryContext *) opaque;

    if (!ctx->buffer)
        return AVERROR(EINVAL);

    int64_t new_position;

    switch (whence) {
        case SEEK_SET:
            new_position = offset;
            break;
        case SEEK_CUR:
            new_position = ctx->position + offset;
            break;
        case SEEK_END:
            new_position = ctx->buffer_size + offset;
            break;
        case AVSEEK_SIZE:
            return ctx->buffer_size;
        default:
            LOGE("Invalid whence value: %d", whence);
            return AVERROR(EINVAL);
    }

    if (new_position < 0 || new_position > ctx->buffer_size) {
        LOGE("Invalid seek position: %lld (buffer size: %zu)", (long long) new_position,
             ctx->buffer_size);
        return AVERROR(EINVAL);
    }

    ctx->position = new_position;
    LOGI("Seek to position %zu", ctx->position);

    return ctx->position;
}

// Fits (width, height) inside a max_size box, preserving aspect ratio.
//
// Never enlarges: max_size <= 0, or a frame already inside the box, comes back unchanged.
// Upscaling here would only move work that resizeBitmap on the Kotlin side already does,
// and doing it in two places is how the two drift apart.
static void fit_within(int width, int height, int max_size, int *out_width, int *out_height) {
    int longest = width > height ? width : height;
    if (max_size <= 0 || longest <= max_size) {
        *out_width = width;
        *out_height = height;
        return;
    }

    double scale = (double) max_size / (double) longest;
    // At least one pixel each way: a 4000x1 frame would otherwise round to zero height and
    // sws_getContext would fail on it.
    int w = (int) (width * scale);
    int h = (int) (height * scale);
    *out_width = w > 0 ? w : 1;
    *out_height = h > 0 ? h : 1;
}

// Function to convert frame to RGBA for Android bitmap and include dimensions
//
// The scale happens HERE, in the colour conversion that has to run anyway, rather than
// after the frame has crossed JNI. sws_scale resizes and converts in one pass, so asking
// it for the thumbnail size costs nothing extra and shrinks everything downstream: a 4K
// frame handed back whole is ~33 MB of RGBA, which was then copied again by copyOfRange in
// VideoDecoder and finally thrown away at 128 px. At the target size it is ~64 KB.
static jbyteArray frame_to_bitmap_bytes(JNIEnv *env, AVFrame *frame, int max_size) {
    int dst_width, dst_height;
    fit_within(frame->width, frame->height, max_size, &dst_width, &dst_height);

    // Create a SwsContext for the colour conversion AND the resize
    struct SwsContext *sws_ctx = sws_getContext(
            frame->width, frame->height, frame->format,   // Source format
            dst_width, dst_height,
            AV_PIX_FMT_RGBA, // Destination format (Android bitmap format)
            SWS_BILINEAR, NULL, NULL, NULL);

    if (!sws_ctx) {
        LOGE("Could not initialize SwsContext");
        return NULL;
    }

    // Allocate an AVFrame for the output RGBA data
    AVFrame *rgba_frame = av_frame_alloc();
    if (!rgba_frame) {
        LOGE("Could not allocate RGBA frame");
        sws_freeContext(sws_ctx);
        return NULL;
    }

    // Set dimensions and format
    rgba_frame->width = dst_width;
    rgba_frame->height = dst_height;
    rgba_frame->format = AV_PIX_FMT_RGBA;

    // Calculate required buffer size and allocate
    int buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, dst_width, dst_height, 1);
    uint8_t *buffer = (uint8_t *) av_malloc(buffer_size);
    if (!buffer) {
        LOGE("Could not allocate RGBA buffer");
        av_frame_free(&rgba_frame);
        sws_freeContext(sws_ctx);
        return NULL;
    }

    // Setup the data pointers and linesize for the output frame
    av_image_fill_arrays(rgba_frame->data, rgba_frame->linesize, buffer,
                         AV_PIX_FMT_RGBA, dst_width, dst_height, 1);

    // Convert the frame from its current format to RGBA, at the destination size.
    // srcSliceH stays the SOURCE height — it describes how much of the input is being fed
    // in, not how big the output is.
    sws_scale(sws_ctx, (const uint8_t *const *) frame->data, frame->linesize, 0,
              frame->height, rgba_frame->data, rgba_frame->linesize);

    LOGI("Converted frame %dx%d -> %dx%d (%d bytes)", frame->width, frame->height,
         dst_width, dst_height, buffer_size);

    // Create Java byte array for the RGBA data with extra 8 bytes for width and height
    jbyteArray result = (*env)->NewByteArray(env, buffer_size + 8);
    if (result) {
        // First 4 bytes: width (int). The DESTINATION size — the caller builds a bitmap of
        // exactly these dimensions and copies the pixels in, so reporting the source size
        // here would overrun.
        jbyte width_bytes[4];
        width_bytes[0] = (dst_width >> 24) & 0xFF;
        width_bytes[1] = (dst_width >> 16) & 0xFF;
        width_bytes[2] = (dst_width >> 8) & 0xFF;
        width_bytes[3] = dst_width & 0xFF;

        // Next 4 bytes: height (int)
        jbyte height_bytes[4];
        height_bytes[0] = (dst_height >> 24) & 0xFF;
        height_bytes[1] = (dst_height >> 16) & 0xFF;
        height_bytes[2] = (dst_height >> 8) & 0xFF;
        height_bytes[3] = dst_height & 0xFF;

        // Set the width and height bytes
        (*env)->SetByteArrayRegion(env, result, 0, 4, width_bytes);
        (*env)->SetByteArrayRegion(env, result, 4, 4, height_bytes);

        // Set the actual bitmap data
        (*env)->SetByteArrayRegion(env, result, 8, buffer_size, (jbyte *) buffer);
    } else {
        LOGE("Could not create Java byte array");
    }

    // Clean up
    av_free(buffer);
    av_frame_free(&rgba_frame);
    sws_freeContext(sws_ctx);

    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bespectacledcyborg_remoteexplorer_data_VideoDecoder_decodeVideoFrameNative(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jbyteArray videoBytes,
                                                                                    jint maxSize) {
    jbyteArray bitmapData = NULL;
    AVFormatContext *format_ctx = NULL;
    AVCodecContext *codec_ctx = NULL;
    const AVCodec *codec = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVIOContext *avio_ctx = NULL;
    uint8_t *avio_buffer = NULL;
    MemoryContext mem_ctx = {0};
    int video_stream_idx = -1;
    int ret = 0;

    // Get input video bytes
    jsize video_length = (*env)->GetArrayLength(env, videoBytes);

    if (video_length <= 0) {
        LOGE("Invalid input video data (empty array)");
        goto cleanup;
    }

    LOGI("Received %d bytes of video data", video_length);

    // Create a copy of the Java byte array to ensure memory safety
    uint8_t *video_data_copy = (uint8_t *) av_malloc(video_length);
    if (!video_data_copy) {
        LOGE("Could not allocate memory for video data copy");
        goto cleanup;
    }

    // Get the Java byte array elements and copy them
    jbyte *video_buffer = (*env)->GetByteArrayElements(env, videoBytes, NULL);
    if (!video_buffer) {
        LOGE("Could not access Java byte array");
        av_free(video_data_copy);
        goto cleanup;
    }

    // Copy the data
    memcpy(video_data_copy, video_buffer, video_length);

    // Release the Java byte array immediately after copying
    (*env)->ReleaseByteArrayElements(env, videoBytes, video_buffer, JNI_ABORT);
    video_buffer = NULL; // Set to NULL to avoid using it later

    // Initialize memory context with our safe copy
    mem_ctx.buffer = video_data_copy;
    mem_ctx.buffer_size = video_length;
    mem_ctx.position = 0;

    // Allocate format context
    format_ctx = avformat_alloc_context();
    if (!format_ctx) {
        LOGE("Could not allocate format context");
        goto cleanup;
    }

    // Allocate custom IO buffer with a larger size for better performance
    avio_buffer = av_malloc(32768); // Increased from 4096 to 32KB
    if (!avio_buffer) {
        LOGE("Could not allocate AVIO buffer");
        goto cleanup;
    }

    // Create custom IO context
    avio_ctx = avio_alloc_context(
            avio_buffer, 4096, 0, &mem_ctx,
            &read_packet, NULL, &seek_packet);
    if (!avio_ctx) {
        LOGE("Could not allocate AVIO context");
        goto cleanup;
    }

    // Set custom IO context
    format_ctx->pb = avio_ctx;

    // Open input stream
    ret = avformat_open_input(&format_ctx, NULL, NULL, NULL);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Could not open input: %s", errbuf);
        goto cleanup;
    }

    LOGI("Opened input stream");

    // Find stream information
    ret = avformat_find_stream_info(format_ctx, NULL);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Could not find stream info: %s", errbuf);
        goto cleanup;
    }

    LOGI("Found stream info");

    // Find video stream
    for (int i = 0; i < format_ctx->nb_streams; i++) {
        if (format_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_idx = i;
            break;
        }
    }

    if (video_stream_idx == -1) {
        LOGE("Could not find video stream");
        goto cleanup;
    }

    // Get codec
    codec = avcodec_find_decoder(format_ctx->streams[video_stream_idx]->codecpar->codec_id);
    if (!codec) {
        LOGE("Could not find codec");
        goto cleanup;
    }

    // Allocate codec context
    codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) {
        LOGE("Could not allocate codec context");
        goto cleanup;
    }

    // Copy codec parameters
    ret = avcodec_parameters_to_context(codec_ctx, format_ctx->streams[video_stream_idx]->codecpar);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Could not copy codec parameters: %s", errbuf);
        goto cleanup;
    }

    // Open codec
    ret = avcodec_open2(codec_ctx, codec, NULL);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Could not open codec: %s", errbuf);
        goto cleanup;
    }

    // Allocate packet and frame
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) {
        LOGE("Could not allocate packet/frame");
        goto cleanup;
    }

    // Read one frame and decode it
    while (av_read_frame(format_ctx, packet) >= 0) {
        if (packet->stream_index == video_stream_idx) {
            // Send packet to decoder
            ret = avcodec_send_packet(codec_ctx, packet);
            if (ret < 0) {
                char errbuf[AV_ERROR_MAX_STRING_SIZE];
                av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
                LOGE("Error sending packet to decoder: %s", errbuf);
                av_packet_unref(packet);
                goto cleanup;
            }

            // Receive frame
            ret = avcodec_receive_frame(codec_ctx, frame);
            if (ret < 0) {
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    av_packet_unref(packet);
                    continue; // Need more packets
                }
                char errbuf[AV_ERROR_MAX_STRING_SIZE];
                av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
                LOGE("Error receiving frame: %s", errbuf);
                av_packet_unref(packet);
                goto cleanup;
            }

            // Successfully decoded a frame
            LOGI("Decoded frame (width: %d, height: %d)", frame->width, frame->height);

            // Convert to bitmap format (RGBA) with embedded dimensions
            bitmapData = frame_to_bitmap_bytes(env, frame, (int) maxSize);

            av_packet_unref(packet);
            break; // Stop after first frame
        }

        av_packet_unref(packet);
    }

    if (bitmapData) {
        LOGI("Successfully converted frame to bitmap");
    } else {
        LOGE("Failed to convert frame to bitmap");
    }

    cleanup:
    // Release video bytes if not already released
    if (video_buffer) {
        (*env)->ReleaseByteArrayElements(env, videoBytes, video_buffer, JNI_ABORT);
    }

    // Free our copy of the video data if it was allocated
    if (mem_ctx.buffer && mem_ctx.buffer != (uint8_t *) video_buffer) {
        av_free(mem_ctx.buffer);
    }

    // Clean up FFmpeg resources
    if (frame)
        av_frame_free(&frame);
    if (packet)
        av_packet_free(&packet);
    if (codec_ctx)
        avcodec_free_context(&codec_ctx);
    if (format_ctx)
        avformat_close_input(&format_ctx);

    if (avio_ctx) {
        if (avio_ctx->buffer)
            av_freep(&avio_ctx->buffer);
        av_freep(&avio_ctx);
    } else if (avio_buffer) {
        av_freep(&avio_buffer);
    }

    return bitmapData;
}