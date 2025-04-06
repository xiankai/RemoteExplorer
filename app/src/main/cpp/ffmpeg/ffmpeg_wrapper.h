#ifndef FFMPEG_WRAPPER_H
#define FFMPEG_WRAPPER_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Decode a frame from the provided video bytes and return bitmap data
JNIEXPORT jbyteArray JNICALL
Java_com_bespectacledcyborg_remoteexplorer_data_VideoDecoder_decodeVideoFrameNative(JNIEnv *env, jobject thiz,
                                                         jbyteArray videoBytes);

// Optional: Initialize FFmpeg (if needed)
JNIEXPORT jboolean JNICALL
Java_com_bespectacledcyborg_remoteexplorer_data_VideoDecoder_initFFmpeg(JNIEnv *env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // FFMPEG_WRAPPER_H
