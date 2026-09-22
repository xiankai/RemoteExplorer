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

// The buffer FFmpeg reads through. Every refill crosses JNI and then the network, so this
// is the I/O granularity on the share. It is one constant because the previous code
// allocated 32 KB and then told avio_alloc_context 4096, so the intended increase never
// took effect and every read stayed a 4 KB round trip.
#define AVIO_BUFFER_SIZE 32768

/**
 * Bridges FFmpeg's AVIO to a RemoteRandomAccess on the Kotlin side — the same seekable
 * file the platform extractor is handed through MediaDataSource.
 *
 * What matters here is the LENGTH. This used to wrap a byte[] holding a 2 MB prefix of the
 * file and answer AVSEEK_SIZE with that array's length, so the demuxer was told every video
 * was 2 MB long. A file whose moov atom sits at the end — anything written without
 * +faststart — could not be reached at all: the demuxer tried to seek there, correctly, and
 * was refused for being past an end that was not the real one. FFmpeg's seeking was never
 * the problem; it was reading a truncated buffer impersonating a whole file.
 */
typedef struct {
    JavaVM *vm;
    jobject reader;       // Local ref: valid for the whole native call, which is the only
                          // window in which these callbacks run.
    jmethodID read_at;
    jbyteArray transfer;  // readAt fills a Java array, so its bytes need staging somewhere
                          // before they can be copied into FFmpeg's own buffer.
    jint transfer_size;
    int64_t position;
    int64_t length;
} ReaderContext;

// Demuxing runs entirely on the thread that called in — avcodec's worker threads decode
// packets that have already been read, and never touch the AVIOContext — so the env is
// always found. It is fetched per call rather than cached so that a caller who breaks that
// assumption fails here instead of using a JNIEnv belonging to another thread.
static JNIEnv *reader_env(ReaderContext *ctx) {
    JNIEnv *env = NULL;
    if ((*ctx->vm)->GetEnv(ctx->vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return NULL;
    return env;
}

// Read callback for custom IO
static int read_packet(void *opaque, uint8_t *buf, int buf_size) {
    if (!opaque || !buf || buf_size <= 0)
        return AVERROR(EINVAL);

    ReaderContext *ctx = (ReaderContext *) opaque;

    JNIEnv *env = reader_env(ctx);
    if (!env) {
        LOGE("read_packet called from a thread with no JNI env");
        return AVERROR(EIO);
    }

    if (ctx->position >= ctx->length)
        return AVERROR_EOF;

    if (ctx->transfer_size < buf_size) {
        // FFmpeg normally asks for exactly AVIO_BUFFER_SIZE, but a caller wanting more than
        // that goes straight through avio_read rather than being split into refills.
        jbyteArray grown = (*env)->NewByteArray(env, buf_size);
        if (!grown) {
            LOGE("Could not allocate a %d byte transfer array", buf_size);
            return AVERROR(ENOMEM);
        }
        if (ctx->transfer)
            (*env)->DeleteLocalRef(env, ctx->transfer);
        ctx->transfer = grown;
        ctx->transfer_size = buf_size;
    }

    jint read = (*env)->CallIntMethod(env, ctx->reader, ctx->read_at,
                                      (jlong) ctx->position, ctx->transfer, 0, buf_size);

    if ((*env)->ExceptionCheck(env)) {
        // The share threw. Clear it so the JNI calls that unwind after this one still work:
        // the reader has already recorded the cause on the Kotlin side, which is where the
        // caller looks to tell a share that went away from a file with no frame in it.
        // Reporting EIO rather than EOF also stops FFmpeg treating a broken connection as a
        // short file and "succeeding" with whatever it had.
        (*env)->ExceptionClear(env);
        LOGE("readAt threw at position %lld", (long long) ctx->position);
        return AVERROR(EIO);
    }

    if (read <= 0)
        return AVERROR_EOF;

    (*env)->GetByteArrayRegion(env, ctx->transfer, 0, read, (jbyte *) buf);
    ctx->position += read;

    return read;
}

// Seek callback for custom IO
static int64_t seek_packet(void *opaque, int64_t offset, int whence) {
    if (!opaque)
        return AVERROR(EINVAL);

    ReaderContext *ctx = (ReaderContext *) opaque;

    int64_t new_position;

    // AVSEEK_FORCE is a flag riding along with the mode, not a mode of its own. Nothing here
    // streams, so every seek is already forceable and the flag can simply be masked off —
    // left in, it falls through to the default and fails a seek that should have worked.
    switch (whence & ~AVSEEK_FORCE) {
        case SEEK_SET:
            new_position = offset;
            break;
        case SEEK_CUR:
            new_position = ctx->position + offset;
            break;
        case SEEK_END:
            new_position = ctx->length + offset;
            break;
        case AVSEEK_SIZE:
            // The whole point of this rewrite: the real size on the share, so the demuxer
            // can reach a trailing moov atom instead of being told the file stops early.
            return ctx->length;
        default:
            LOGE("Invalid whence value: %d", whence);
            return AVERROR(EINVAL);
    }

    // Landing exactly on the end is legal — it means the next read reports EOF.
    if (new_position < 0 || new_position > ctx->length) {
        LOGE("Seek outside the file: %lld (length %lld)", (long long) new_position,
             (long long) ctx->length);
        return AVERROR(EINVAL);
    }

    ctx->position = new_position;

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
                                                                                    jobject reader,
                                                                                    jint maxSize) {
    jbyteArray bitmapData = NULL;
    AVFormatContext *format_ctx = NULL;
    AVCodecContext *codec_ctx = NULL;
    const AVCodec *codec = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVIOContext *avio_ctx = NULL;
    uint8_t *avio_buffer = NULL;
    ReaderContext reader_ctx = {0};
    jclass reader_class = NULL;
    jmethodID size_method = NULL;
    jlong length = 0;
    int video_stream_idx = -1;
    int ret = 0;

    if (!reader) {
        LOGE("No reader supplied");
        goto cleanup;
    }

    if ((*env)->GetJavaVM(env, &reader_ctx.vm) != JNI_OK) {
        LOGE("Could not reach the JavaVM");
        goto cleanup;
    }

    // Looked up by name and signature rather than against a fixed class, so the reader is
    // whatever the caller passes. RemoteRandomAccess is the only one today; keeping the
    // binding this loose is what lets it and the platform extractor share one reader.
    reader_class = (*env)->GetObjectClass(env, reader);
    reader_ctx.read_at = (*env)->GetMethodID(env, reader_class, "readAt", "(J[BII)I");
    size_method = (*env)->GetMethodID(env, reader_class, "size", "()J");
    if (!reader_ctx.read_at || !size_method) {
        LOGE("Reader is missing readAt(J[BII)I or size()J");
        (*env)->ExceptionClear(env);
        goto cleanup;
    }

    // Asking for the length first also forces the file open, so a share that has gone away
    // fails here — before any of FFmpeg's state exists — rather than inside a callback.
    length = (*env)->CallLongMethod(env, reader, size_method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE("size() threw");
        goto cleanup;
    }

    if (length <= 0) {
        LOGE("Invalid input video data (empty file)");
        goto cleanup;
    }

    LOGI("Decoding a %lld byte video", (long long) length);

    reader_ctx.reader = reader;
    reader_ctx.length = length;
    reader_ctx.position = 0;

    // Allocate format context
    format_ctx = avformat_alloc_context();
    if (!format_ctx) {
        LOGE("Could not allocate format context");
        goto cleanup;
    }

    // Allocate custom IO buffer
    avio_buffer = av_malloc(AVIO_BUFFER_SIZE);
    if (!avio_buffer) {
        LOGE("Could not allocate AVIO buffer");
        goto cleanup;
    }

    // Create custom IO context
    avio_ctx = avio_alloc_context(
            avio_buffer, AVIO_BUFFER_SIZE, 0, &reader_ctx,
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
    // Nothing owns a copy of the file any more — the reader on the Kotlin side does, and
    // render() closes it. What is left here is FFmpeg's own state plus the staging array.
    if (reader_ctx.transfer)
        (*env)->DeleteLocalRef(env, reader_ctx.transfer);

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
