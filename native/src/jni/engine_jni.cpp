// Thin JNI bridge between Kotlin (com.brekwol.daybreak.engine.NativeEngine) and the engine.
// No image logic lives here. C++ errors become Java exceptions; no C++ exception
// ever crosses this boundary.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "daybreak/edit.hpp"
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

// EXIF from a camera JPEG as an "Exif\0\0" + TIFF block with Orientation set to 1,
// for containers that store rotation separately (HEIC). Null when there is none.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_exifBlock(JNIEnv* env, jobject /* this */,
                                                        jbyteArray jpeg) {
  if (jpeg == nullptr) return nullptr;
  std::vector<std::uint8_t> tiff = daybreak::exif_tiff_from_jpeg(to_vector(env, jpeg));
  if (tiff.empty() || !daybreak::normalize_exif_orientation(tiff)) return nullptr;
  std::vector<std::uint8_t> block = {'E', 'x', 'i', 'f', 0, 0};
  block.insert(block.end(), tiff.begin(), tiff.end());
  return to_java(env, block);
}

// ------------------------------------------------------------------ editor
//
// The recipe arrives as a float array in the layout of EditParams.kt (keep the
// two in sync) and the curves as [count, x0, y0, x1, y1, ...] per channel.

namespace {

constexpr jsize kEditParamCount = 55;

bool read_recipe(JNIEnv* env, jfloatArray params, jfloatArray curves, daybreak::EditRecipe& recipe) {
  if (params == nullptr || env->GetArrayLength(params) != kEditParamCount) {
    throw_java(env, "java/lang/IllegalArgumentException", "Edit parameters have the wrong length");
    return false;
  }
  float p[kEditParamCount];
  env->GetFloatArrayRegion(params, 0, kEditParamCount, p);
  recipe.exposure = p[0];
  recipe.brightness = p[1];
  recipe.contrast = p[2];
  recipe.highlights = p[3];
  recipe.shadows = p[4];
  recipe.whites = p[5];
  recipe.blacks = p[6];
  recipe.clarity = p[7];
  recipe.vignette = p[8];
  recipe.temperature = p[9];
  recipe.tint = p[10];
  recipe.vibrance = p[11];
  recipe.saturation = p[12];
  for (int i = 0; i < daybreak::kMixerBands; ++i) {
    recipe.mixer[i].hue = p[13 + i * 3];
    recipe.mixer[i].saturation = p[14 + i * 3];
    recipe.mixer[i].luminance = p[15 + i * 3];
  }
  recipe.shadow_hue = p[37];
  recipe.shadow_amount = p[38];
  recipe.highlight_hue = p[39];
  recipe.highlight_amount = p[40];
  recipe.grading_balance = p[41];
  recipe.sharpen = p[42];
  recipe.sharpen_radius = p[43];
  recipe.noise_luma = p[44];
  recipe.noise_color = p[45];
  recipe.grain = p[46];
  recipe.grain_size = p[47];
  recipe.quarter_turns = static_cast<int>(p[48]);
  recipe.flip = p[49] != 0.0f;
  recipe.straighten = p[50];
  recipe.crop_left = p[51];
  recipe.crop_top = p[52];
  recipe.crop_right = p[53];
  recipe.crop_bottom = p[54];

  if (curves != nullptr) {
    const jsize length = env->GetArrayLength(curves);
    std::vector<float> c(static_cast<std::size_t>(length));
    if (length > 0) env->GetFloatArrayRegion(curves, 0, length, c.data());
    std::size_t at = 0;
    for (int channel = 0; channel < daybreak::kCurveChannels && at < c.size(); ++channel) {
      const auto count = static_cast<std::size_t>(std::max(0.0f, c[at++]));
      if (at + count * 2 > c.size()) break;
      auto& points = recipe.curves[channel];
      points.clear();
      for (std::size_t i = 0; i < count; ++i) points.push_back({c[at + i * 2], c[at + i * 2 + 1]});
      at += count * 2;
    }
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_editOutputSize(JNIEnv* env, jobject /* this */, jint width,
                                                             jint height, jfloatArray params,
                                                             jboolean apply_crop) {
  daybreak::EditRecipe recipe;
  if (!read_recipe(env, params, nullptr, recipe)) return nullptr;
  const auto size = daybreak::edit_output_size(static_cast<std::uint32_t>(std::max(0, width)),
                                               static_cast<std::uint32_t>(std::max(0, height)), recipe,
                                               apply_crop == JNI_TRUE);
  const jint out[2] = {static_cast<jint>(size.width), static_cast<jint>(size.height)};
  jintArray array = env->NewIntArray(2);
  if (array != nullptr) env->SetIntArrayRegion(array, 0, 2, out);
  return array;
}

extern "C" JNIEXPORT void JNICALL
Java_com_brekwol_daybreak_engine_NativeEngine_renderEdit(JNIEnv* env, jobject /* this */, jobject source,
                                                         jobject destination, jfloatArray params,
                                                         jfloatArray curves, jint full_long_side,
                                                         jboolean apply_crop) {
  daybreak::EditRecipe recipe;
  if (!read_recipe(env, params, curves, recipe)) return;

  AndroidBitmapInfo src_info{};
  AndroidBitmapInfo dst_info{};
  if (AndroidBitmap_getInfo(env, source, &src_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_getInfo(env, destination, &dst_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    throw_java(env, "java/lang/IllegalArgumentException", "Could not read the bitmaps");
    return;
  }
  if (src_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dst_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    throw_java(env, "java/lang/IllegalArgumentException", "Bitmaps must be ARGB_8888");
    return;
  }
  void* src_pixels = nullptr;
  void* dst_pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, source, &src_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    throw_java(env, "java/lang/IllegalStateException", "Could not lock the source bitmap");
    return;
  }
  if (AndroidBitmap_lockPixels(env, destination, &dst_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    AndroidBitmap_unlockPixels(env, source);
    throw_java(env, "java/lang/IllegalStateException", "Could not lock the destination bitmap");
    return;
  }
  const daybreak::RgbaView src_view{
      std::span<const std::uint8_t>(static_cast<const std::uint8_t*>(src_pixels),
                                    std::size_t{src_info.stride} * src_info.height),
      src_info.width, src_info.height, src_info.stride};
  const daybreak::MutableRgbaView dst_view{
      std::span<std::uint8_t>(static_cast<std::uint8_t*>(dst_pixels), std::size_t{dst_info.stride} * dst_info.height),
      dst_info.width, dst_info.height, dst_info.stride};
  daybreak::RenderOptions options;
  options.apply_crop = apply_crop == JNI_TRUE;
  options.full_resolution_long_side = static_cast<std::uint32_t>(std::max(0, full_long_side));
  const auto result = daybreak::render_edit(src_view, recipe, dst_view, options);
  AndroidBitmap_unlockPixels(env, destination);
  AndroidBitmap_unlockPixels(env, source);
  if (!result) throw_java(env, "java/lang/IllegalStateException", result.error().message);
}
