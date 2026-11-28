cmake_minimum_required(VERSION 3.22)
project(archvocoder C CXX)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/arm64-v8a/libopus.a)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/armeabi-v7a/libopus.a)
else()
  set(OPUS ${CMAKE_CURRENT_SOURCE_DIR}/libs/x86_64/libopus.a)
endif()

# Only the dedicated ArchOpus bridge belongs in this library. libarchdmr.so keeps
# its own private copy of libopus, and both libraries are loaded in the same process.
add_library(archvocoder SHARED opus_jni.c)
set_target_properties(archvocoder PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(archvocoder PRIVATE
    -Wl,--hash-style=sysv
    -Wl,--exclude-libs,ALL)
target_include_directories(archvocoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/opus/include)
target_link_libraries(archvocoder ${OPUS} log m)

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
target_link_libraries(archdstar log m)

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

