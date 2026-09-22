#ifndef FFMPEG_WRAPPER_H
#define FFMPEG_WRAPPER_H

#include <jni.h>

#ifdef __cplusplus
extern "C"
{
#endif

  // Decode a frame from the file behind `reader` and return bitmap data.
  //
  // `reader` is any object exposing readAt(J[BII)I and size()J — RemoteRandomAccess on the
  // Kotlin side. It replaces the byte[] this used to take: a prefix of the file could not
  // answer for the whole of it, so a video whose moov atom sits at the end was unreachable.
  JNIEXPORT jbyteArray JNICALL
  // maxSize is the longest edge the returned frame may have, passed in rather than
  // duplicated here so THUMBNAIL_SIZE stays defined in exactly one place.
  Java_com_bespectacledcyborg_remoteexplorer_data_VideoDecoder_decodeVideoFrameNative(JNIEnv *env, jobject thiz,
                                                                                      jobject reader,
                                                                                      jint maxSize);

#ifdef __cplusplus
}
#endif

#endif // FFMPEG_WRAPPER_H
