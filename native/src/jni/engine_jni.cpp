// Thin JNI bridge between Kotlin (com.brekwol.daybreak.engine.NativeEngine) and the engine.
// No image logic lives here. C++ errors become Java exceptions; no C++ exception
// ever crosses this boundary.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "daybreak/engine.hpp"

namespace {

constexpr const char* kTag = "DaybreakEngine";

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
  jclass cls = env->FindClass(class_name);
  if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

std::vector<std::uint8_t> to_vector(JNIEnv* env, jbyteArray array) {
  std::vector<std::uint8_t> out;
  if (array == nullptr) return out;
  const jsize length = env->GetArrayLength(array);
  out.resize(static_cast<std::size_t>(length));
  if (length > 0) {
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(out.data()));
  }
  return out;
}

jbyteArray to_java(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
  const auto length = static_cast<jsize>(bytes.size());
  jbyteArray array = env->NewByteArray(length);
  if (array == nullptr) return nullptr;  // OutOfMemoryError already pending
  env->SetByteArrayRegion(array, 0, length, reinterpret_cast<const jbyte*>(bytes.data()));
  return array;
}

jobject new_argb_bitmap(JNIEnv* env, std::uint32_t width, std::uint32_t height) {
  jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
  jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
  if (bitmap_class == nullptr || config_class == nullptr) return nullptr;
  jfieldID argb_field =
      env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jmethodID create = env->GetStaticMethodID(
      bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  if (argb_field == nullptr || create == nullptr) return nullptr;
  jobject config = env->GetStaticObjectField(config_class, argb_field);
  return env->CallStaticObjectMethod(bitmap_class, create, static_cast<jint>(width),
                                     static_cast<jint>(height), config);
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_encodeBitmap(JNIEnv* env, jobject /* this */,
                                                           jobject bitmap, jint rotation_degrees,
                                                           jfloat quality, jint effort,
                                                           jboolean lossless,
                                                           jbyteArray exif_source_jpeg) {
  AndroidBitmapInfo info{};
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    throw_java(env, "java/lang/IllegalArgumentException", "Could not read the bitmap");
    return nullptr;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    throw_java(env, "java/lang/IllegalArgumentException", "Bitmap must be ARGB_8888");
    return nullptr;
  }

  std::vector<std::uint8_t> exif;
  if (exif_source_jpeg != nullptr) {
    const std::vector<std::uint8_t> jpeg = to_vector(env, exif_source_jpeg);
    exif = daybreak::exif_tiff_from_jpeg(jpeg);
    if (!exif.empty() && !daybreak::normalize_exif_orientation(exif)) exif.clear();
  }

  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      pixels == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "Could not lock the bitmap pixels");
    return nullptr;
  }
  const daybreak::RgbaView view{
      std::span<const std::uint8_t>(static_cast<const std::uint8_t*>(pixels),
                                    std::size_t{info.stride} * info.height),
      info.width, info.height, info.stride};
  daybreak::EncodeOptions options;
  options.quality = quality;
  options.effort = effort;
  options.lossless = lossless == JNI_TRUE;
  auto result = daybreak::encode_jxl(view, daybreak::orientation_from_degrees(rotation_degrees),
                                     options, exif);
  AndroidBitmap_unlockPixels(env, bitmap);

  if (!result) {
    throw_java(env, "java/lang/IllegalStateException", result.error().message);
    return nullptr;
  }
  return to_java(env, *result);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_decodeToBitmap(JNIEnv* env, jobject /* this */,
                                                             jbyteArray data,
                                                             jint max_dimension) {
  const std::vector<std::uint8_t> bytes = to_vector(env, data);
  auto result =
      daybreak::decode_jxl(bytes, max_dimension > 0 ? static_cast<std::uint32_t>(max_dimension) : 0);
  if (!result) {
    throw_java(env, "java/lang/IllegalArgumentException", result.error().message);
    return nullptr;
  }
  const daybreak::Image& image = *result;

  jobject bitmap = new_argb_bitmap(env, image.width, image.height);
  if (bitmap == nullptr || env->ExceptionCheck()) return nullptr;

  AndroidBitmapInfo info{};
  void* pixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      pixels == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "Could not fill the bitmap");
    return nullptr;
  }
  const std::size_t row_bytes = std::size_t{image.width} * 4;
  for (std::uint32_t y = 0; y < image.height; ++y) {
    std::memcpy(static_cast<std::uint8_t*>(pixels) + std::size_t{y} * info.stride,
                image.rgba.data() + std::size_t{y} * row_bytes, row_bytes);
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  return bitmap;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_readSize(JNIEnv* env, jobject /* this */,
                                                       jbyteArray data) {
  const std::vector<std::uint8_t> bytes = to_vector(env, data);
  auto result = daybreak::read_jxl_info(bytes);
  if (!result) {
    throw_java(env, "java/lang/IllegalArgumentException", result.error().message);
    return nullptr;
  }
  jintArray out = env->NewIntArray(2);
  if (out == nullptr) return nullptr;
  const jint values[2] = {static_cast<jint>(result->width), static_cast<jint>(result->height)};
  env->SetIntArrayRegion(out, 0, 2, values);
  return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_libjxlVersion(JNIEnv* env, jobject /* this */) {
  return env->NewStringUTF(daybreak::libjxl_version().c_str());
}
