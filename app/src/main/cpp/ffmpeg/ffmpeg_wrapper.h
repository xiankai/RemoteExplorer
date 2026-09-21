#ifndef FFMPEG_WRAPPER_H
#define FFMPEG_WRAPPER_H

#include <jni.h>

#ifdef __cplusplus
extern "C"
{
#endif

  // Decode a frame from the provided video bytes and return bitmap data
  JNIEXPORT jbyteArray JNICALL
  // maxSize is the longest edge the returned frame may have, passed in rather than
  // duplicated here so THUMBNAIL_SIZE stays defined in exactly one place.
  Java_com_bespectacledcyborg_remoteexplorer_data_VideoDecoder_decodeVideoFrameNative(JNIEnv *env, jobject thiz,
                                                                                      jbyteArray videoBytes,
                                                                                      jint maxSize);

#ifdef __cplusplus
}
#endif

#endif // FFMPEG_WRAPPER_H
