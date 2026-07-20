#!/bin/bash
#
# Copyright (C) 2016 The CyanogenMod Project
# Copyright (C) 2017-2020 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

set -e

DEVICE=OP5231
VENDOR=oppo

# Load extract_utils and do some sanity checks
MY_DIR="${BASH_SOURCE%/*}"
if [[ ! -d "${MY_DIR}" ]]; then MY_DIR="${PWD}"; fi

ANDROID_ROOT="${MY_DIR}/../../.."

HELPER="${ANDROID_ROOT}/tools/extract-utils/extract_utils.sh"
if [ ! -f "${HELPER}" ]; then
    echo "Unable to find helper script at ${HELPER}"
    exit 1
fi
source "${HELPER}"

# Default to sanitizing the vendor folder before extraction
CLEAN_VENDOR=true

KANG=
SECTION=

while [ "${#}" -gt 0 ]; do
    case "${1}" in
        -n | --no-cleanup )
                CLEAN_VENDOR=false
                ;;
        -k | --kang )
                KANG="--kang"
                ;;
        -s | --section )
                SECTION="${2}"; shift
                CLEAN_VENDOR=false
                ;;
        * )
                SRC="${1}"
                ;;
    esac
    shift
done

if [ -z "${SRC}" ]; then
    SRC="adb"
fi

function blob_fixup() {
    case "${1}" in
        vendor/lib64/libril-qc-hal-qmi.so)
            "${PATCHELF}" --add-needed "libshims_ocsclk.so" "${2}"
            ;;
         vendor/lib64/vendor.qti.hardware.camera.postproc@1.0-service-impl.so)
            [ "$2" = "" ] && return 0
            "${SIGSCAN}" -p "AB 0B 00 94" -P "1F 20 03 D5" -f "${2}"
            ;;
         vendor/bin/hw/android.hardware.vibrator-service.mediatek)
            "$PATCHELF" --replace-needed "android.hardware.vibrator-V2-ndk_platform.so" "android.hardware.vibrator-V2-ndk.so" "$2"
            ;;
         vendor/bin/hw/android.hardware.gnss-service.mediatek)
            "$PATCHELF" --replace-needed "android.hardware.gnss-V1-ndk_platform.so" "android.hardware.gnss-V1-ndk.so" "$2"
            ;;
         vendor/lib64/hw/android.hardware.gnss-impl-mediatek.so)
            "$PATCHELF" --replace-needed "android.hardware.gnss-V1-ndk_platform.so" "android.hardware.gnss-V1-ndk.so" "$2"
            ;;
         vendor/lib64/hw/vendor.mediatek.hardware.pq@2.13-impl.so | vendor/lib/hw/vendor.mediatek.hardware.pq@2.13-impl.so)
            "$PATCHELF" --replace-needed "libutils.so" "libutils-v32.so" "$2"
            ;;
         vendor/bin/hw/camerahalserver)
            "$PATCHELF" --replace-needed "libutils.so" "libutils-v32.so" "$2"
            ;;
         vendor/lib64/hw/android.hardware.camera.provider@2.6-impl-mediatek.so)
         	"$PATCHELF" --add-needed "libcamera_metadata_shim.so" "$2"
         	;;
         vendor/lib64/libkeystore-engine-wifi-hidl.so)
            "$PATCHELF" --replace-needed "android.system.keystore2-V1-ndk_platform.so" "android.system.keystore2-V1-ndk.so" "$2"
            ;;
         odm/bin/hw/vendor.oplus.hardware.biometrics.face@1.0-service)
            "$PATCHELF" --replace-needed "android.hardware.biometrics.face-V1-ndk_platform.so" "android.hardware.biometrics.face-V1-ndk.so" "$2"
            "$PATCHELF" --replace-needed "android.hardware.biometrics.common-V1-ndk_platform.so" "android.hardware.biometrics.common-V1-ndk.so" "$2"
            ;;
         vendor/etc/init/android.hardware.bluetooth@1.1-service-mediatek.rc)
            sed -i '/vts.native_server.on/,+1d' "$2"
            ;;
    esac
}

# Initialize the helper
setup_vendor "${DEVICE}" "${VENDOR}" "${ANDROID_ROOT}" false "${CLEAN_VENDOR}"

extract "${MY_DIR}/proprietary-files.txt" "${SRC}" "${KANG}" --section "${SECTION}"
