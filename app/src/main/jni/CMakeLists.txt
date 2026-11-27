cmake_minimum_required(VERSION 3.22)
project(archvocoder C CXX)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(BLIP25 ${CMAKE_CURRENT_SOURCE_DIR}/libs/arm64-v8a/libarch_blip25.a)
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/arm64-v8a/libopus.a)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(BLIP25 ${CMAKE_CURRENT_SOURCE_DIR}/libs/armeabi-v7a/libarch_blip25.a)
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/armeabi-v7a/libopus.a)
else()
  set(BLIP25 ${CMAKE_CURRENT_SOURCE_DIR}/libs/x86_64/libarch_blip25.a)
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/x86_64/libopus.a)
endif()

add_library(archvocoder SHARED vocoder_jni.c opus_jni.c)
# Android 5.x old dynamic linker needs a SysV hash table as well as GNU hash.
target_link_options(archvocoder PRIVATE -Wl,--hash-style=sysv)
target_include_directories(archvocoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/opus/include)
target_link_libraries(archvocoder ${BLIP25} ${OPUS} log m)

file(GLOB_RECURSE DSTAR_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar/mbe/*.c
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar/mbe/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar/imbe_vocoder/*.cc)
file(GLOB YSF_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/ysf/*.cpp)
add_library(archdstar SHARED dstar_vocoder_jni.cpp ${YSF_SOURCES} ${DSTAR_SOURCES})
target_include_directories(archdstar PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar/mbe
    ${CMAKE_CURRENT_SOURCE_DIR}/dstar/imbe_vocoder
    ${CMAKE_CURRENT_SOURCE_DIR}/ysf)
target_compile_options(archdstar PRIVATE -O3)
target_link_libraries(archdstar ${BLIP25} log m)

add_library(archpcm SHARED
    pcm_frontend_jni.c
    speexdsp/libspeexdsp/resample.c
    speexdsp/libspeexdsp/preprocess.c
    speexdsp/libspeexdsp/fftwrap.c
    speexdsp/libspeexdsp/filterbank.c
    speexdsp/libspeexdsp/mdf.c
    speexdsp/libspeexdsp/kiss_fft.c
    speexdsp/libspeexdsp/kiss_fftr.c)
target_include_directories(archpcm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp/libspeexdsp)
target_compile_options(archpcm PRIVATE -O3 -DFLOATING_POINT -DEXPORT= -DUSE_KISS_FFT)
target_link_libraries(archpcm log m)
